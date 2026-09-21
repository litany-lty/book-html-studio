"""M3/J11 第二意见：Qwen 裁图复识别（付费，经授权；失败记 FAILED 不循环追问）。

两步：
  1. --sample：从 paddle 存档按种子抽样单字跨度（否定/数字窗口优先 50，其余随机 100），
     输出 <out>/spans.json（span 坐标为 paddle 系文本坐标，不推理）。
  2. --transcribe：逐跨度渲染裁图（196px 下限，同产品逻辑）调 Qwen OCR，
     输出 <out>/opinions.json（qwen 全裁图转录原文；对齐由冻结阶段程序复核）。
环境变量：DASHSCOPE_API_KEY（只放内存请求头，不打印不落盘）。
用法见各步 --help。私密正文仅本地。
"""
import argparse
import base64
import io
import json
import os
import random
import sys
import time
import urllib.request

import fitz

CJK = set("不未非无莫勿否别没弗毋")
QWEN_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"


def load_spans(runs, bookmap, seed, n_hard=50, n_rand=100):
    random.seed(seed)
    hard, rest = [], []
    for book_id, meta in bookmap.items():
        pdf = os.path.join(PDFDIR, meta["pdf"])
        for page in meta["pages"]:
            name = os.path.join(runs, f"paddle-{book_id[:8]}-{page}.json")
            if not os.path.exists(name):
                continue
            data = json.load(open(name))
            for block in data.get("blocks", []) or []:
                if not block or block.get("type") not in ("text", "heading"):
                    continue
                text = block.get("original") or ""
                for offset, char in enumerate(text):
                    if char.strip() and "\u4e00" <= char <= "\u9fff":
                        window = text[max(0, offset - 8):offset + 8]
                        target = hard if any(c in CJK for c in window) or any(c.isdigit() for c in window) else rest
                        target.append({"book": book_id, "sourcePage": page,
                                       "blockId": block["id"], "bbox": block["bbox"],
                                       "span": [offset, offset + 1], "paddle": char,
                                       "window": window})
    random.shuffle(hard)
    random.shuffle(rest)
    return hard[:n_hard] + rest[:n_rand]


PDFDIR = "/Users/litany/Downloads/pdf"


def render_crop(bookmap, span):
    meta = bookmap[span["book"]]
    doc = fitz.open(os.path.join(PDFDIR, meta["pdf"]))
    try:
        pg = doc[span["sourcePage"] - 1]
        zoom = 3.0
        pix = pg.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
        pw, ph = pix.width, pix.height
        x, y, w, h = span["bbox"]
        full = None
        for runs_file in (os.environ.get("EVAL_RUNS", "/tmp/eval-runs"),):
            name = os.path.join(runs_file, f"paddle-{span['book'][:8]}-{span['sourcePage']}.json")
            if os.path.exists(name):
                for b in json.load(open(name)).get("blocks", []):
                    if b and b.get("id") == span["blockId"]:
                        full = b.get("original") or ""
        total = len(full) if full else 1
        s0, s1 = span["span"]
        f = (s0 + s1) / 2 / total
        vertical = h > w * 2
        if vertical:
            yc = (y + f * h) * ph
            half = max(0.06 * h * ph, 98)
            box = (max(0, int((x - 0.02) * pw)), max(0, int(yc - half)),
                   min(pw, int((x + w + 0.02) * pw)), min(ph, int(yc + half)))
        else:
            xc = (x + f * w) * pw
            half = max(0.10 * w * pw, 98)
            box = (max(0, int(xc - half)), max(0, int((y - 0.02) * ph)),
                   min(pw, int(xc + half)), min(ph, int((y + h + 0.02) * ph)))
        from PIL import Image
        img = Image.frombytes("RGB", [pw, ph], pix.samples).crop(box)
        if min(img.width, img.height) < 196:
            scale = 196 / max(1, min(img.width, img.height))
            img = img.resize((min(1200, int(img.width * scale)), min(1200, int(img.height * scale))))
        buffer = io.BytesIO()
        img.save(buffer, format="PNG")
        return buffer.getvalue(), img.width, img.height
    finally:
        doc.close()


def qwen_transcribe(png, width, height, key, model="qwen3.5-ocr", timeout=120):
    image = {"image": "data:image/png;base64," + base64.b64encode(png).decode(),
             "min_pixels": 3072, "max_pixels": 8388608, "enable_rotate": False}
    body = {"model": model,
            "input": {"messages": [{"role": "user", "content": [image]}]},
            "parameters": {"ocr_options": {"task": "advanced_recognition"}}}
    request = urllib.request.Request(
        QWEN_URL, data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            status = response.status
    except urllib.error.HTTPError as e:
        return {"ok": False, "http": e.code, "seconds": round(time.monotonic() - start, 1)}
    except Exception as e:
        return {"ok": False, "http": None, "error": type(e).__name__,
                "seconds": round(time.monotonic() - start, 1)}
    elapsed = round(time.monotonic() - start, 1)
    try:
        root = json.loads(raw.decode())
    except Exception:
        return {"ok": False, "http": status, "error": "BAD_JSON", "seconds": elapsed}
    if root.get("output", {}).get("choices", [{}])[0].get("finish_reason") == "length":
        return {"ok": False, "http": status, "error": "TRUNCATED", "seconds": elapsed}
    texts = []

    def find(node):
        if isinstance(node, dict):
            if "words_info" in node and isinstance(node["words_info"], list):
                for word in node["words_info"]:
                    text = (word.get("text") or "").strip() if isinstance(word, dict) else ""
                    if text:
                        texts.append(text)
            for child in node.values():
                find(child)
        elif isinstance(node, list):
            for child in node:
                find(child)

    find(root)
    if not texts:
        return {"ok": False, "http": status, "error": "NO_WORDS", "seconds": elapsed}
    usage = root.get("usage", {})
    return {"ok": True, "http": status, "text": "".join(texts), "words": len(texts),
            "usage": usage or None, "seconds": elapsed}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", required=True)
    parser.add_argument("--bookmap", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--seed", type=int, default=11)
    parser.add_argument("--sample", action="store_true")
    parser.add_argument("--transcribe", action="store_true")
    parser.add_argument("--model", default="qwen3.5-ocr")
    args = parser.parse_args()
    os.environ["EVAL_RUNS"] = args.runs
    os.makedirs(args.out, exist_ok=True)
    if args.sample:
        bookmap = json.load(open(args.bookmap))
        spans = load_spans(args.runs, bookmap, args.seed)
        json.dump(spans, open(os.path.join(args.out, "spans.json"), "w"), ensure_ascii=False)
        print(f"spans={len(spans)}")
    if args.transcribe:
        import urllib.error  # noqa
        key = os.environ.get("DASHSCOPE_API_KEY", "")
        if not key:
            print("MISSING_KEY")
            return 2
        spans = json.load(open(os.path.join(args.out, "spans.json")))
        bookmap = json.load(open(args.bookmap))
        opinions = {}
        calls = fails = 0
        for index, span in enumerate(spans):
            key_id = f"{span['book'][:8]}:{span['sourcePage']}:{span['blockId']}:{span['span'][0]}"
            png, width, height = render_crop(bookmap, span)
            result = qwen_transcribe(png, width, height, key, args.model)
            result["span"] = span
            opinions[key_id] = result
            calls += 1
            if not result["ok"]:
                fails += 1
            print(f"{index + 1}/{len(spans)} {key_id} ok={result['ok']} "
                  f"text={(result.get('text') or '')[:12]}", flush=True)
            time.sleep(1)
        json.dump(opinions, open(os.path.join(args.out, "opinions.json"), "w"), ensure_ascii=False)
        print(f"calls={calls} fails={fails}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
