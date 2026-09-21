"""M3/J11 准备：样本书只读清点（免费本地，不 OCR、不写、不传）。

用法：
  python3 scripts/verification/inventory_books.py --out <dir> -- <pdf...>
产物：<out>/inventory.json（页数、sha256、原生文字覆盖、繁简启发比、竖排信号、
数字否定计数、图像页比；不含任何正文内容）
疑点数必须经 OCR + 召回后才有，本脚本不断言；费用与冻结见 REPORT。
"""
import argparse
import hashlib
import json
import os
import sys

import fitz

# 启发式探针字表（近似，不作真值；繁简一对多不以此判定）
TRAD_PROBE = set("國學書體實義無為時來這們個們遠過道達運還這邊進遠選連對開關東車門馬鳥魚黃黑紅們們")
SIMP_PROBE = set("国学书体实义无为时来这们个远过道达运还这边进远选连对开关东车门马鸟鱼黄黑红")
NEGATIONS = set("不未非无莫勿否别没弗毋")


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inventory_one(path):
    doc = fitz.open(path)
    pages = doc.page_count
    char_counts = []
    trad = simp = digits = negations = 0
    vertical_spans = total_spans = 0
    image_pages = 0
    low_text_pages = 0
    for page in doc:
        text = page.get_text() or ""
        chars = [c for c in text if not c.isspace()]
        char_counts.append(len(chars))
        if len(chars) < 80:
            low_text_pages += 1
        for c in chars:
            if c in TRAD_PROBE:
                trad += 1
            elif c in SIMP_PROBE:
                simp += 1
            if c.isdigit():
                digits += 1
            if c in NEGATIONS:
                negations += 1
        try:
            data = page.get_text("dict")
            for block in data.get("blocks", []):
                for line in block.get("lines", []):
                    for span in line.get("spans", []):
                        total_spans += 1
                        direction = span.get("dir", [1, 0])
                        if direction != [1, 0]:
                            vertical_spans += 1
        except Exception:
            pass
        try:
            if page.get_images():
                image_pages += 1
        except Exception:
            pass
    doc.close()
    char_counts.sort()
    median = char_counts[len(char_counts) // 2] if char_counts else 0
    size = os.path.getsize(path)
    return {
        "file": os.path.basename(path),
        "bytes": size,
        "sha256": sha256_file(path),
        "pages": pages,
        "nativeCharsMedian": median,
        "nativeCharsTotal": sum(char_counts),
        "lowTextPages": low_text_pages,
        "tradProbe": trad,
        "simpProbe": simp,
        "scriptSignal": "traditional-leaning" if trad > simp * 2 else (
            "simplified-leaning" if simp > trad * 2 else "mixed-or-unknown"),
        "verticalSpanRatio": round(vertical_spans / max(1, total_spans), 4),
        "digitChars": digits,
        "negationChars": negations,
        "imagePages": image_pages,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("pdfs", nargs="+")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    books = []
    for pdf in args.pdfs:
        try:
            books.append(inventory_one(pdf))
            print(f"ok {os.path.basename(pdf)}: "
                  f"{books[-1]['pages']}p median={books[-1]['nativeCharsMedian']}")
        except Exception as e:
            print(f"FAIL {pdf}: {type(e).__name__}")
            books.append({"file": os.path.basename(pdf), "error": type(e).__name__})
    with open(os.path.join(args.out, "inventory.json"), "w") as f:
        json.dump({"books": books, "note": "counts+hashes only; no content; issues require OCR"},
                  f, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
