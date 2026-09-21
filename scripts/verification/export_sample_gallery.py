"""Re-export completed samples through the tool, without OCR or data edits.

Use a new --out directory; prior ZIPs and reader edits are never overwritten.
The input is sample_books.py's private report.json. Only local HTTP is allowed.
"""
import argparse
import html
import json
import os
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse
import uuid
import zipfile

import requests


def export_gallery(base, report, out):
    parsed = urlparse(base)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("仅允许独立本地服务")
    books = report.get("books", [])
    if not books or sum(len(book.get("pages", [])) for book in books) != report["selectedPages"]:
        raise ValueError("抽页结果不完整")
    if any(book.get("phase") != "EXPORTED" for book in books):
        raise ValueError("必须等抽页全部完成后再导出")
    for book in books:
        uuid.UUID(book["bookId"])
        if any(type(page) is not int or page < 1 for page in book["pages"]):
            raise ValueError("页码无效")
    os.umask(0o077)
    out.mkdir(parents=True, mode=0o700, exist_ok=False)
    session = requests.Session()
    session.trust_env = False
    cards = []
    for number, book in enumerate(books, 1):
        endpoint = base + f'/api/books/{book["bookId"]}'
        response = session.get(endpoint + "/job", timeout=20)
        response.raise_for_status()
        job = response.json()
        if job.get("id") != book["jobId"] or job.get("status") not in ("COMPLETED", "COMPLETED_WITH_ERRORS"):
            raise ValueError("作业身份变化或尚未完成；不重跑 OCR")
        archive = out / f"book-{number}.zip"
        with session.get(endpoint + "/export", params={"pages": ",".join(map(str, book["pages"]))},
                         stream=True, timeout=180) as response:
            response.raise_for_status()
            with archive.open("xb") as stream:
                for chunk in response.iter_content(65536):
                    stream.write(chunk)
        destination = out / f"book-{number}"
        destination.mkdir()
        with zipfile.ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                name = PurePosixPath(entry.filename)
                if name.is_absolute() or ".." in name.parts or "\\" in entry.filename:
                    raise ValueError("导出包包含不安全路径")
                if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("导出包不允许符号链接")
            bundle.extractall(destination)
        if not (destination / "index.html").is_file():
            raise ValueError("缺少工具生成的阅读入口")
        for page in book["pages"]:
            response = session.get(endpoint + f"/pages/{page}", timeout=30)
            response.raise_for_status()
            (out / f"book-{number}-page-{page}.json").write_text(
                json.dumps(response.json(), ensure_ascii=False, indent=2), encoding="utf-8")
        title = html.escape(book["filename"])
        pages = html.escape(", ".join(map(str, book["pages"])))
        outcome = "任务完成，文字仍需核对" if job["status"] == "COMPLETED" else "部分页面处理失败，请查看页内提示"
        cards.append(f'<article><h2>{title}</h2><p>原 PDF 页：{pages}</p><p>{outcome}</p>'
                     f'<a href="book-{number}/index.html">打开横排阅读样本</a>'
                     f' · <a href="book-{number}.zip">下载离线包</a></article>')
        print(f"已由工具重新导出第 {number} 本（未调用 OCR）", flush=True)
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "index.html").write_text(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        '<title>图书抽页验收</title><style>body{margin:0;background:#f5f3ed;color:#28343d;font:16px/1.7 system-ui}'
        'main{max-width:960px;margin:auto;padding:24px}article{border:1px solid #ccd4d9;background:white;padding:22px;'
        'margin:18px 0;border-radius:8px}h2{font-size:20px}a{color:#245982}</style><main>'
        f'<h1>{len(books)} 本书 · {report["selectedPages"]} 页真实抽样</h1>'
        '<p>正文、图片、疑点与目录均由同一转换工具生成，未手工修改样本 HTML。'
        '自动识别不等于逐字准确；表格优先保留原图关系，原稿可随时对照。</p>'
        + ''.join(cards) + '</main></html>', encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    export_gallery(args.base.rstrip("/"), json.loads(args.report.read_text()), args.out)
