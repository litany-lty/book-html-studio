"""Verify a real exported ZIP using file://, including demand loading and damaged local preferences."""
import asyncio
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "test-results/offline-integrity-browser"


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    reports = list((ROOT / "target/surefire-reports").glob("TEST-*.xml"))
    if not reports:
        raise RuntimeError("Run Maven tests before this export integration check")
    props = ET.parse(reports[0]).getroot().find("properties")
    cp = next(p.attrib["value"] for p in props if p.attrib["name"] == "java.class.path")
    checks, errors, external = [], [], []

    def check(name, condition, detail=None):
        checks.append({"name": name, "pass": bool(condition), "detail": detail})

    with tempfile.TemporaryDirectory(prefix="book-offline-integrity-") as directory:
        temp = Path(directory).resolve()
        classes = temp / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", cp, "-d", str(classes),
                        str(ROOT / "scripts/verification/OfflineReaderQaExport.java")], check=True)
        archive = temp / "offline.zip"
        subprocess.run(["java", "-Djava.awt.headless=true", "-cp", str(classes) + os.pathsep + cp,
                        "OfflineReaderQaExport", str(temp / "data"), str(archive)], check=True)
        exported = temp / "export"
        with zipfile.ZipFile(archive) as zipped:
            zipped.extractall(exported)
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(
                executable_path=os.environ.get("CHROMIUM") or shutil.which("google-chrome") or shutil.which("chromium"),
                headless=True)
            ctx = await browser.new_context(viewport={"width": 1100, "height": 850}, reduced_motion="reduce")
            page = await ctx.new_page()
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.on("request", lambda request: external.append(request.url)
                    if request.url.startswith(("https://", "http://")) else None)
            await page.goto((exported / "index.html").as_uri(), wait_until="load")
            await expect(page.locator("[data-paper]")).to_contain_text("独特简体词1")
            resident = await page.evaluate("Object.keys(globalThis.__BOOK_PAGES__ || {}).length")
            check("table_of_contents_does_not_load_all_pages", resident == 1, resident)
            for query in ("独特简体词14", "獨特繁體詞14"):
                await page.fill("[data-search]", query)
                await page.locator("[data-search-form]").evaluate("form => form.requestSubmit()")
                check("full_text_search_" + query, await page.locator(".search-result").count() == 1)
            await page.locator(".issue-page-button").click()
            await page.locator(".issue-edit").fill("校订文字")
            await page.get_by_role("button", name="保存并标记解决").click()
            await expect(page.locator(".issue-save-status")).to_contain_text("已保存到当前浏览器")
            await page.locator(".issue-dialog").get_by_role("button", name="关闭", exact=True).click()
            check("editing_uses_real_indexeddb", await page.evaluate("globalThis.__reviewForTest.backendMode() === 'indexeddb'"))
            await page.locator(".toc-entry").filter(has_text="第十四章 后段").click()
            await expect(page.locator("[data-paper]")).to_contain_text("独特简体词14")
            try:
                await expect(page.locator('[data-block-id="h14-lower"]')).to_be_focused(timeout=2000)
                check("cold_outline_navigation_focuses_requested_heading", True)
            except AssertionError:
                check("cold_outline_navigation_focuses_requested_heading", False)
            for number in range(2, 15):
                await page.fill("[data-jump]", str(number))
                await page.locator("[data-jump-form]").evaluate("form => form.requestSubmit()")
                await expect(page.locator("[data-paper]")).to_contain_text("独特简体词" + str(number))
            resident = await page.evaluate("Object.keys(globalThis.__BOOK_PAGES__ || {}).length")
            scripts = await page.locator("script[src*='pages-data/']").count()
            check("resident_page_data_is_bounded", resident <= 10, resident)
            check("completed_page_scripts_are_removed", scripts == 0, scripts)
            await page.fill("[data-jump]", "1")
            await page.locator("[data-jump-form]").evaluate("form => form.requestSubmit()")
            await expect(page.locator("[data-paper]")).to_contain_text("独特简体词1")
            check("evicted_page_can_be_revisited", True)
            await expect(page.locator("[data-paper]")).to_contain_text("校订文字")
            check("edits_survive_page_eviction", True)
            backup = await page.evaluate("async () => (await globalThis.__reviewForTest.ensureIdb()).backend.exportBackup()")
            check("backup_contains_saved_revision", any(record.get("replacement") == "校订文字" for record in backup.get("records", [])))
            await page.locator("[data-bookmark]").click()
            await page.reload(wait_until="load")
            await expect(page.locator("[data-bookmark]")).to_have_attribute("aria-pressed", "true")
            check("bookmark_survives_reload", True)
            await page.screenshot(path=str(OUT / "offline-reader.png"), full_page=True)
            # Corrupted but valid JSON must not prevent the book from opening.
            await ctx.add_init_script("""localStorage.setItem('book-html:bbbbbbbb-2222-2222-2222-222222222222:bookmarks', '{}');
                localStorage.setItem('book-html:bbbbbbbb-2222-2222-2222-222222222222:reading',
                    JSON.stringify({page:'invalid',size:'invalid',leading:'invalid'}));""")
            await page.reload(wait_until="load")
            try:
                await expect(page.locator("[data-paper]")).to_contain_text("独特简体词1", timeout=3000)
                check("malformed_local_preferences_recover", True)
            except AssertionError:
                check("malformed_local_preferences_recover", False)
            await ctx.close()
            missing = exported / "assets/pages-data/14.js"
            original = missing.read_bytes()
            missing.unlink()
            failure_context = await browser.new_context()
            failure_page = await failure_context.new_page()
            failed_loads = []
            load_errors = []
            failure_page.on("request", lambda request: failed_loads.append(request.url)
                            if request.url.endswith("/pages-data/14.js") else None)
            failure_page.on("pageerror", lambda error: errors.append(str(error)))
            failure_page.on("requestfailed", lambda request: load_errors.append({"url": request.url, "error": request.failure}))
            await failure_page.goto((exported / "index.html").as_uri(), wait_until="load")
            await failure_page.fill("[data-jump]", "14")
            await failure_page.locator("[data-jump-form]").evaluate("form => form.requestSubmit()")
            try:
                await expect(failure_page.locator("[data-paper]")).to_contain_text("本页离线数据加载失败", timeout=2000)
                check("missing_page_has_recoverable_error", True)
            except AssertionError:
                check("missing_page_has_recoverable_error", False)
            await asyncio.sleep(.2)
            check("missing_page_does_not_retry_forever", len(failed_loads) == 1, len(failed_loads))
            missing.write_bytes(original)
            retry = failure_page.get_by_role("button", name="重试加载本页")
            if await retry.count():
                await retry.click()
                try:
                    await expect(failure_page.locator("[data-paper]")).to_contain_text("独特简体词14")
                    check("restored_page_can_be_retried", True)
                except AssertionError:
                    check("restored_page_can_be_retried", False, {
                        "errors": load_errors,
                        "requests": failed_loads,
                        "pageData": await failure_page.evaluate("globalThis.__BOOK_PAGES__?.[14] || null"),
                        "scripts": await failure_page.locator("script[src*='pages-data/']").evaluate_all("nodes => nodes.map(node => node.src)")})
            else:
                check("restored_page_can_be_retried", False)
            await failure_context.close()
            restore_context = await browser.new_context()
            restore_page = await restore_context.new_page()
            restore_page.on("pageerror", lambda error: errors.append(str(error)))
            await restore_page.goto((exported / "index.html").as_uri(), wait_until="load")
            await expect(restore_page.locator("[data-paper]")).to_contain_text("独特简体词1")
            await restore_page.locator(".issue-page-button").click()
            await restore_page.locator(".import-review summary").click()
            await restore_page.locator(".import-file").set_input_files({
                "name": "revision-backup.json", "mimeType": "application/json",
                "buffer": json.dumps(backup, ensure_ascii=False).encode()})
            await restore_page.get_by_role("button", name="预览并应用").click()
            await expect(restore_page.locator(".import-preview")).to_contain_text("已应用 1")
            await restore_page.reload(wait_until="load")
            await expect(restore_page.locator("[data-paper]")).to_contain_text("校订文字")
            check("backup_restores_into_a_fresh_browser", True)
            await restore_context.close()
            check("no_external_requests", not external, external)
            check("no_javascript_errors", not errors, errors)
            await browser.close()
    result = {"checks": checks, "pageErrors": errors, "externalRequests": external}
    (OUT / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))
    if any(not check["pass"] for check in checks):
        raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main())
