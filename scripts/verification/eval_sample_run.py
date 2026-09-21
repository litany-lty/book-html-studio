"""M3/J11 抽样执行驱动（版本化、可续跑、通道核验）。

对 bookmap 每本书的 pages 列表依次跑 paddle（force=false）与 ppocr（force=true），
每页结果存档 <runs>/<prov>-<book8>-<page>.json；已存档页跳过（可续跑）。
每页断言 provider 仅属 paddle 系三家，否则立即停止。
用法：
  python3 scripts/verification/eval_sample_run.py --server http://127.0.0.1:18772 \
      --bookmap <json> --pages <json> --runs <dir> [--only paddle|ppocr]
bookmap: {bookId: {pdf, pdfSha256, pages:[...]}}；pages: {bookId: [pages]}（第二轮）。
"""
import argparse
import json
import os
import sys
import time
import urllib.request

ALLOWED_PROVIDERS = {"paddle", "paddle-aistudio", "ppocr"}


def api(server, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(server + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode())


def wait_job(server, book_id, timeout_s=1800):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        job = api(server, "GET", f"/api/books/{book_id}/job")
        status = job.get("status")
        if status in ("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED"):
            return job
        time.sleep(20)
    raise RuntimeError(f"job timeout {book_id}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--bookmap", required=True)
    parser.add_argument("--pages", required=True)
    parser.add_argument("--runs", required=True)
    parser.add_argument("--only", default="both", choices=["paddle", "ppocr", "both"])
    args = parser.parse_args()
    os.makedirs(args.runs, exist_ok=True)
    bookmap = json.load(open(args.bookmap))
    wanted = json.load(open(args.pages))
    providers = ["paddle", "ppocr"] if args.only == "both" else [args.only]
    for book_id, pages in wanted.items():
        if book_id not in bookmap:
            print(f"SKIP unknown book {book_id}")
            continue
        for provider in providers:
            force = provider == "ppocr"
            pending = [p for p in pages
                       if not os.path.exists(os.path.join(
                           args.runs, f"{provider}-{book_id[:8]}-{p}.json"))]
            if not pending:
                print(f"SKIP {book_id[:8]} {provider} (all archived)")
                continue
            pages_str = ",".join(map(str, sorted(pending)))
            print(f"SUBMIT {book_id[:8]} {provider} [{pages_str}] force={force}", flush=True)
            api(args.server, "POST", f"/api/books/{book_id}/jobs",
                {"pages": pages_str, "provider": provider, "layout": "auto",
                 "splitSpreads": False, "force": force, "assist": False})
            job = wait_job(args.server, book_id)
            print(f"DONE {book_id[:8]} {provider}: {job.get('status')} "
                  f"errors={len(job.get('errors', []))}", flush=True)
            for error in job.get("errors", []):
                print(f"  ERR: {error[:150]}")
            for page in sorted(pending):
                request = urllib.request.Request(
                    f"{args.server}/api/books/{book_id}/pages/{page}")
                with urllib.request.urlopen(request, timeout=30) as response:
                    data = json.loads(response.read().decode())
                if data.get("provider") not in ALLOWED_PROVIDERS:
                    raise SystemExit(
                        f"STOP: unexpected provider {data.get('provider')} "
                        f"on {book_id[:8]} p{page}")
                with open(os.path.join(args.runs, f"{provider}-{book_id[:8]}-{page}.json"),
                          "w") as f:
                    json.dump(data, f, ensure_ascii=False)
            print(f"ARCHIVED {book_id[:8]} {provider}", flush=True)
    print("ROUND_DONE")


if __name__ == "__main__":
    sys.exit(main())
