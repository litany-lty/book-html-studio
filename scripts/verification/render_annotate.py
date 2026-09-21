"""M3/J11 盲标看板：将冻结候选渲染成原图拼板（本地，无外呼）。

用法：
  python3 scripts/verification/render_annotate.py --freeze <freeze.json> --runs <eval-runs>
      --pdfdir <pdf目录> --bookmap <json> --out <dir> [--cols 2] [--rows 4]
产物：<out>/montage-NN.png + <out>/order.json（屏上顺序）。
真值由标注者看原图判定后写入 truth.json {caseId: <正确转录|null>}，
null=原图不可辨认；单人盲标，偏差在报告注明。
"""
import argparse
import json
import os
import sys

import fitz
from PIL import Image, ImageDraw, ImageFont

PDFDIR_DEFAULT = "/Users/litany/Downloads/pdf"


def load_font(size):
    # 同一 ttc 多 face 缺字情况不同：用探针串选非白像素最多的
    probe = "陰陽變攝松揭院限郵0123456789，。"
    best, best_score = None, -1
    for path, index in [
        ("/System/Library/Fonts/Supplemental/Songti.ttc", 0),
        ("/System/Library/Fonts/Supplemental/Songti.ttc", 1),
        ("/System/Library/Fonts/Supplemental/Songti.ttc", 2),
        ("/System/Library/Fonts/Supplemental/Songti.ttc", 3),
        ("/System/Library/Fonts/PingFang.ttc", 0),
    ]:
        try:
            font = ImageFont.truetype(path, size, index=index)
            img = Image.new("RGB", (400, 40), "white")
            ImageDraw.Draw(img).text((5, 5), probe, fill="black", font=font)
            px = img.load()
            score = sum(1 for x in range(400) for y in range(40) if px[x, y] != (255, 255, 255))
            if score > best_score:
                best, best_score = font, score
        except Exception:
            continue
    return best if best is not None else ImageFont.load_default()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--freeze", required=True)
    parser.add_argument("--runs", required=True)
    parser.add_argument("--pdfdir", default=PDFDIR_DEFAULT)
    parser.add_argument("--bookmap", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--cols", type=int, default=2)
    parser.add_argument("--rows", type=int, default=4)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    freeze = json.load(open(args.freeze))
    bookmap = json.load(open(args.bookmap))
    cases = freeze["cases"]

    docs = {}
    blocks = {}
    for book_id, meta in bookmap.items():
        path = os.path.join(args.pdfdir, meta["pdf"])
        docs[book_id] = fitz.open(path)
        for page in meta["pages"]:
            for prefix in ("paddle",):
                name = f"{prefix}-{book_id[:8]}-{page}.json"
                full = os.path.join(args.runs, name)
                if not os.path.exists(full):
                    continue
                data = json.load(open(full))
                for block in data.get("blocks", []) or []:
                    if block and block.get("id"):
                        blocks[(book_id, page, block["id"])] = block

    font = load_font(38)
    small = load_font(28)
    CELL_W, CROP_H, TEXT_H = 640, 200, 210
    per_page = args.cols * args.rows
    order = []
    for page_index in range((len(cases) + per_page - 1) // per_page):
        chunk = cases[page_index * per_page:(page_index + 1) * per_page]
        cells = []
        for case in chunk:
            order.append(case["caseId"])
            block = blocks.get((case["book"], case["sourcePage"], case["blockId"]))
            crop = Image.new("RGB", (CELL_W - 10, CROP_H), "white")
            span = case.get("span") or [0, 0]
            s0, s1 = span[0], span[1]
            if block and block.get("bbox"):
                doc = docs[case["book"]]
                pg = doc[case["sourcePage"] - 1]
                zoom = 3.0
                pix = pg.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
                pw, ph = pix.width, pix.height
                x, y, w, h = block["bbox"]
                full = block.get("original") or ""
                total = len(full) or 1
                f = (s0 + s1) / 2 / total
                vertical = h > w * 2
                if vertical:
                    yc = (y + f * h) * ph
                    half = 0.20 * h * ph
                    x0 = max(0, int((x - 0.02) * pw))
                    x1 = min(pw, int((x + w + 0.02) * pw))
                    y0 = max(0, int(yc - half))
                    y1 = min(ph, int(yc + half))
                else:
                    xc = (x + f * w) * pw
                    half = 0.28 * w * pw
                    y0 = max(0, int((y - 0.02) * ph))
                    y1 = min(ph, int((y + h + 0.02) * ph))
                    x0 = max(0, int(xc - half))
                    x1 = min(pw, int(xc + half))
                if x1 > x0 and y1 > y0:
                    page_img = Image.frombytes("RGB", [pw, ph], pix.samples)
                    crop_img = page_img.crop((x0, y0, x1, y1))
                    scale = min((CELL_W - 10) / max(1, crop_img.width), CROP_H / max(1, crop_img.height))
                    crop_img = crop_img.resize((max(1, int(crop_img.width * scale)),
                                                max(1, int(crop_img.height * scale))))
                    crop.paste(crop_img, (0, 0))
                    red = ImageDraw.Draw(crop)
                    if vertical:
                        yy = int(((s0 + s1) / 2 / total * h * ph - (y0 - y * ph)) * scale) if scale else 0
                        red.line([0, yy, crop_img.width, yy], fill="red", width=2)
                    else:
                        xx = int(((s0 + s1) / 2 / total * w * pw - (x0 - x * pw)) * scale) if scale else 0
                        red.line([xx, 0, xx, crop_img.height], fill="red", width=2)
            cands = case.get("candidates", [])
            t0 = cands[0].get("text", "") if len(cands) > 0 else ""
            t1 = cands[1].get("text", "") if len(cands) > 1 else ""
            cats = ",".join(case.get("category", []))
            # 目标区间估计红框（聚焦框内按字符偏移线性映射），仅定位辅助
            win = ""
            try:
                span = case.get("span") or [0, 0]
                full = (block or {}).get("original") or ""
                total = len(full) or 1
                s0, s1 = span[0], span[1]
                win = full[max(0, s0 - 8):s1 + 8].replace("\n", "⏎")
                if block and block.get("bbox"):
                    x, y, w, h = block["bbox"]
                    vertical = h > w * 2
                    red = ImageDraw.Draw(crop)
                    if vertical:
                        yy0 = int((s0 / total) * crop.size[1])
                        yy1 = int(max(s0 + 1, s1) / total * crop.size[1])
                        red.rectangle([0, yy0, crop.size[0] - 1, max(yy0 + 2, yy1)], outline="red", width=2)
                    else:
                        xx0 = int((s0 / total) * crop.size[0])
                        xx1 = int(max(s0 + 1, s1) / total * crop.size[0])
                        red.rectangle([xx0, 0, max(xx0 + 2, xx1), crop.size[1] - 1], outline="red", width=2)
            except Exception:
                pass
            label = Image.new("RGB", (CELL_W, CROP_H + TEXT_H + 10), (240, 240, 240))
            label.paste(crop, (5, 5))
            draw = ImageDraw.Draw(label)
            ty = CROP_H + 10
            draw.text((8, ty), f"{case['caseId']} {case.get('kind')}/{cats}", fill="black", font=small)
            draw.text((8, ty + 34), f"A:{t0[:28]}", fill="black", font=font)
            draw.text((8, ty + 72), f"B:{t1[:28]}", fill="black", font=font)
            draw.text((8, ty + 110), f"W:{win[:30]}", fill="black", font=small)
            cells.append(label)
            case["_win"] = win
        cols, rows = args.cols, args.rows
        sheet = Image.new("RGB", (cols * CELL_W, rows * (CROP_H + TEXT_H + 10)), "white")
        for index, cell in enumerate(cells):
            sheet.paste(cell, ((index % cols) * CELL_W, (index // cols) * (CROP_H + TEXT_H + 10)))
        sheet.save(os.path.join(args.out, f"montage-{page_index:02d}.png"))
    json.dump(order, open(os.path.join(args.out, "order.json"), "w"))
    print(f"montages={(len(cases) + per_page - 1) // per_page} cases={len(cases)}")


if __name__ == "__main__":
    sys.exit(main())
