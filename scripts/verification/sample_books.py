"""Run a bounded, explicit local-app PDF sample plan; never resume uncertain POSTs.

The plan is private JSON: {books:[{sourcePdf, samples:[{pdfPage, purpose}]}]}.
Credentials stay in the Java service, not this script. It imports each PDF into
an isolated service, processes only the selected pages once, and exports them.
No automatic overwrite, cloud retry, reprocessing or whole-book OCR.
"""
import argparse
import json
import os
from pathlib import Path
import time
from urllib.parse import urlparse

import requests


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def run(base, plan, out, provider, assist):
    parsed = urlparse(base)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("仅允许独立本地测试服务")
    books = plan.get("books", [])
    pages_total = sum(len(book.get("samples", [])) for book in books)
    if not books or not 1 <= pages_total <= 24:
        raise ValueError("抽页计划必须包含 1–24 页；扩大范围需要另行确认")
    for book in books:
        source = Path(book["sourcePdf"])
        pages = [sample["pdfPage"] for sample in book["samples"]]
        if not source.is_file() or source.suffix.lower() != ".pdf" or not pages or len(set(pages)) != len(pages):
            raise ValueError("无效文件或重复选页")
        if any(type(page) is not int or not 1 <= page <= book["totalPages"] for page in pages):
            raise ValueError("选页越界")
    os.umask(0o077)
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    session = requests.Session()
    session.trust_env = False  # Never route local documents through HTTP proxy settings.
    config = session.get(base + "/api/config", timeout=15)
    config.raise_for_status()
    if not any(p.get("id") == provider and p.get("available") for p in config.json()["providers"]):
        raise ValueError("选定 OCR 通道未配置；禁止静默换通道")
    if assist and not config.json().get("qwenAssist", {}).get("configured"):
        raise ValueError("计划要求 Qwen 辅助，但未配置")
    report = {"provider": provider, "assist": assist, "selectedPages": pages_total, "books": []}
    for index, book in enumerate(books, 1):
        source = Path(book["sourcePdf"])
        pages = [sample["pdfPage"] for sample in book["samples"]]
        entry = {"filename": source.name, "sourcePdf": str(source), "pages": pages, "phase": "UPLOADING"}
        report["books"].append(entry)
        save(out / "report.json", report)
        print(f"[{index}/{len(books)}] 导入 {source.name}；仅处理 PDF 页 {pages}", flush=True)
        try:
            # POSTs are sent once. On timeout the report records uncertainty; never resubmit.
            with source.open("rb") as stream:
                response = session.post(base + "/api/books", files={"file": (source.name, stream, "application/pdf")}, timeout=180)
            response.raise_for_status()
            imported = response.json()
            book_id = imported["id"]
            entry.update(bookId=book_id, phase="SUBMITTING", totalPages=imported["totalPages"])
            save(out / "report.json", report)
            job_base = base + f"/api/books/{book_id}"
            response = session.post(job_base + "/jobs", json={"pages": ",".join(map(str, pages)),
                "provider": provider, "layout": "auto", "splitSpreads": True, "assist": assist, "force": False}, timeout=20)
            response.raise_for_status()
            entry.update(jobId=response.json()["id"], phase="PROCESSING")
            save(out / "report.json", report)
            deadline, previous = time.monotonic() + 1800, None
            while time.monotonic() < deadline:
                response = session.get(job_base + "/job", timeout=20)
                response.raise_for_status()
                job = response.json()
                if job.get("id") != entry["jobId"]:
                    raise ValueError("作业身份变化；停止，避免把别的作业当作本次结果")
                fingerprint = (job.get("status"), job.get("completed"), job.get("currentPage"))
                if fingerprint != previous:
                    print(f"  {fingerprint}", flush=True)
                    entry["job"] = job
                    save(out / "report.json", report)
                    previous = fingerprint
                if job["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                    break
                time.sleep(4)
            else:
                raise TimeoutError("达到本书抽页期限，未自动取消或重提，需核实作业状态")
            for page in pages:
                response = session.get(job_base + f"/pages/{page}", timeout=30)
                response.raise_for_status()
                save(out / f"book-{index}-page-{page}.json", response.json())
            response = session.get(job_base + "/outline", timeout=30)
            response.raise_for_status()
            save(out / f"book-{index}-outline.json", response.json())
            with session.get(job_base + "/export", params={"pages": ",".join(map(str, pages))}, stream=True, timeout=180) as response:
                response.raise_for_status()
                with (out / f"book-{index}.zip").open("xb") as archive:
                    for chunk in response.iter_content(65536):
                        archive.write(chunk)
            entry["phase"] = "EXPORTED"
        except Exception as error:
            entry["phase"] = "FAILED_OR_UNCERTAIN"
            entry["errorType"] = type(error).__name__
            print(f"  请求未完成（{type(error).__name__}），不自动重试。请核对隔离服务日志/任务。", flush=True)
            save(out / "report.json", report)
            raise  # A possibly running job must not be followed by another submission.
        save(out / "report.json", report)
    print(f"样页结果已保存：{out}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--provider", default="paddle-aistudio")
    parser.add_argument("--assist", action="store_true")
    args = parser.parse_args()
    run(args.base.rstrip("/"), json.loads(args.plan.read_text()), args.out, args.provider, args.assist)


if __name__ == "__main__":
    main()
