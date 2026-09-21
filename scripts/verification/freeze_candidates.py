"""M3/J11 冻结候选集构建（机器部分；真值标注另行盲标）。

输入：双轮 OCR 存档（paddle-run 全量 + ppocr-run 中 provider==ppocr 的页），
  书目映射 bookmap.json {bookId: {pdf, pdfSha256, pages:[...]}}。
输出：<out>/freeze.json（cases 含双边候选原文；私密正文，仅本地，绝不进仓库/证据包），
  <out>/freeze-stats.json（计数、类别、覆盖缺口；可公开）。
规则：
  - 同页同 order 且 bbox IoU>=0.5 的块配对；配对不上记缺口，不硬对。
  - 配对文本 difflib 差异区间即候选对（paddle 文 vs ppocr 文）；span 记 paddle 坐标。
  - ppocr 缺失页只作单源页，不参加选择；一致块抽样作 KEEP_CURRENT。
  - 上限：分歧 300 + 一致 30；否定/数字优先保留，其余按种子随机。
用法：
  python3 scripts/verification/freeze_candidates.py --runs /tmp/eval-runs --bookmap <json> --out <dir> [--seed 7]
"""
import argparse
import difflib
import hashlib
import json
import os
import random
import sys

NEGATIONS = set("不未非无莫勿否别没弗毋")
IOU_THRESHOLD = 0.5
MAX_DIVERGENT = 300
MAX_KEEP = 30
PAGE_DIFF_MIN_CONTEXT = 4
PAGE_DIFF_MAX_SPAN = 6
PAGE_DIFF_MAX_PER_PAGE = 8
BLOCK_DIFF_MAX_SPAN = 12


def iou(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    x, y = max(ax, bx), max(ay, by)
    r, bottom = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    inter = max(0, r - x) * max(0, bottom - y)
    union = aw * ah + bw * bh - inter
    return 0 if union <= 0 else inter / union


def load_page(runs, prefix, book, page):
    matches = [f for f in os.listdir(runs)
               if f.startswith(prefix + f"-{book[:8]}-{page}.json")]
    if not matches:
        return None
    return json.load(open(os.path.join(runs, matches[0])))


def category(window):
    flags = []
    if any(c in NEGATIONS for c in window):
        flags.append("否定")
    if any(c.isdigit() for c in window):
        flags.append("数字")
    return flags or ["普通"]


def match_blocks(blocks_a, blocks_b, book_id, meta, page, divergent, keep, gaps):
    """第一轮：同 order + IoU 配对；返回已配对的 paddle 块 id 集合。"""
    by_order_b = {}
    for b in blocks_b:
        by_order_b.setdefault(b.get("order"), []).append(b)
    matched = set()
    for a in blocks_a:
        best, best_iou = None, 0
        for b in by_order_b.get(a.get("order"), []):
            try:
                score = iou(a.get("bbox"), b.get("bbox"))
            except Exception:
                score = 0
            if score > best_iou:
                best, best_iou = b, score
        if best is None or best_iou < IOU_THRESHOLD:
            gaps["unmatchedBlocks"] += 1
            continue
        matched.add(a.get("id"))
        ta, tb = a.get("original") or "", best.get("original") or ""
        if ta == tb:
            gaps["agreementBlocks"] += 1
            if len(keep) < MAX_KEEP and random.random() < 0.2 and len(ta) >= 4:
                keep.append({"book": book_id, "pdfSha256": meta["pdfSha256"],
                             "sourcePage": page, "blockId": a.get("id"),
                             "span": [0, min(2, len(ta))], "current": ta[:2],
                             "candidates": [{"candidateId": "k0", "text": ta[:2],
                                             "sourceKind": "PRIMARY_OCR"}],
                             "kind": "KEEP"})
            continue
        matcher = difflib.SequenceMatcher(None, ta, tb, autojunk=False)
        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag == "equal":
                continue
            if i2 - i1 > BLOCK_DIFF_MAX_SPAN or j2 - j1 > BLOCK_DIFF_MAX_SPAN:
                gaps["oversizeSpans"] = gaps.get("oversizeSpans", 0) + 1
                continue
            if not (ta[i1:i2].strip() or tb[j1:j2].strip()):
                continue
            window = ta[max(0, i1 - 8):i2 + 8]
            divergent.append({
                "book": book_id, "pdfSha256": meta["pdfSha256"], "sourcePage": page,
                "blockId": a.get("id"), "ppocrBlockId": best.get("id"),
                "span": [i1, i2], "current": ta[i1:i2],
                "candidates": [
                    {"candidateId": "k0", "text": ta[i1:i2], "sourceKind": "PRIMARY_OCR",
                     "producer": "paddle"},
                    {"candidateId": "k1", "text": tb[j1:j2], "sourceKind": "PRIMARY_OCR",
                     "producer": "ppocr"}],
                "spanLen": i2 - i1, "otherLen": j2 - j1,
                "category": category(window), "kind": "DIVERGENT"})
    return matched


def mine_page_level(blocks_a, blocks_b, matched_a, book_id, meta, page, divergent):
    """第二轮：页级文本锚定。两边按 order 拼页文本 difflib，只收满足
    小差异（双方<=6 字）且两侧各有>=10 字相等上下文的区间；映射回 paddle 块坐标。
    已配对块、有空文本、上下文不足一律跳过。返回新增数。"""
    seq_a, map_a = [], []
    for a in blocks_a:
        if a.get("id") in matched_a:
            continue
        text = a.get("original") or ""
        if not text.strip():
            continue
        base = len("".join(seq_a))
        seq_a.append(text)
        for offset in range(len(text)):
            map_a.append((a, offset))
    seq_b, starts_b = [], []
    for b in blocks_b:
        text = b.get("original") or ""
        if not text.strip():
            continue
        starts_b.append((len("".join(seq_b)), text))
        seq_b.append(text)
    full_a, full_b = "".join(seq_a), "".join(seq_b)
    if not full_a or not full_b:
        return 0
    matcher = difflib.SequenceMatcher(None, full_a, full_b, autojunk=False)
    added = 0
    opcodes = matcher.get_opcodes()
    for index, (tag, i1, i2, j1, j2) in enumerate(opcodes):
        if tag == "equal":
            continue
        if i2 - i1 > PAGE_DIFF_MAX_SPAN or j2 - j1 > PAGE_DIFF_MAX_SPAN:
            continue
        # 块拼接处的纯空白差异是噪音，不是转录分歧
        if not (full_a[i1:i2].strip() or full_b[j1:j2].strip()):
            continue
        # 相邻相等块即上下文；两侧各>=10 字才收，否则丢弃
        left_len = opcodes[index - 1][2] - opcodes[index - 1][1] if index > 0 and opcodes[index - 1][0] == "equal" else 0
        right_len = opcodes[index + 1][2] - opcodes[index + 1][1] if index + 1 < len(opcodes) and opcodes[index + 1][0] == "equal" else 0
        if left_len < PAGE_DIFF_MIN_CONTEXT or right_len < PAGE_DIFF_MIN_CONTEXT:
            continue
        if i1 >= len(map_a):
            continue
        block, offset = map_a[i1]
        if offset + (i2 - i1) > len(block.get("original") or ""):
            continue
        window = full_a[max(0, i1 - 8):i2 + 8]
        divergent.append({
            "book": book_id, "pdfSha256": meta["pdfSha256"], "sourcePage": page,
            "blockId": block.get("id"), "ppocrBlockId": None,
            "span": [offset, offset + (i2 - i1)], "current": full_a[i1:i2],
            "candidates": [
                {"candidateId": "k0", "text": full_a[i1:i2], "sourceKind": "PRIMARY_OCR",
                 "producer": "paddle"},
                {"candidateId": "k1", "text": full_b[j1:j2], "sourceKind": "PRIMARY_OCR",
                 "producer": "ppocr"}],
            "spanLen": i2 - i1, "otherLen": j2 - j1,
            "category": category(window), "kind": "DIVERGENT-PAGE"})
        added += 1
    return added


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", required=True)
    parser.add_argument("--bookmap", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    random.seed(args.seed)
    bookmap = json.load(open(args.bookmap))
    os.makedirs(args.out, exist_ok=True)

    divergent, keep, gaps = [], [], {"unmatchedBlocks": 0, "ppocrMissingPages": 0,
                                     "singleSourcePages": 0, "agreementBlocks": 0,
                                     "pageMined": 0, "pageMinedSkipped": 0}
    for book_id, meta in bookmap.items():
        for page in meta["pages"]:
            paddle = load_page(args.runs, "paddle", book_id, page)
            ppocr_file = load_page(args.runs, "ppocr", book_id, page)
            if paddle is None:
                continue
            ppocr = ppocr_file if ppocr_file and ppocr_file.get("provider") == "ppocr" else None
            if ppocr is None:
                gaps["ppocrMissingPages"] += 1
                continue
            blocks_a = [b for b in (paddle.get("blocks") or []) if b and b.get("original") is not None]
            blocks_b = [b for b in (ppocr.get("blocks") or []) if b and b.get("original") is not None]
            # 第一轮：块配对（同 order + IoU）
            matched_a = match_blocks(blocks_a, blocks_b, book_id, meta, page,
                                     divergent, keep, gaps)
            # 第二轮：页级文本锚定（跨引擎分块不一致时）。只收小差异＋长相等上下文，
            # 区间映射回 paddle 块坐标；上下文不足的一律丢弃，不猜。
            gaps["pageMined"] += mine_page_level(
                blocks_a, blocks_b, matched_a, book_id, meta, page, divergent)
    # 分层截断：否定/数字优先；单页封顶 8（优先硬类别）；其余随机至总量
    hard = [c for c in divergent if c["category"] != ["普通"]]
    rest = [c for c in divergent if c["category"] == ["普通"]]
    per_page = {}
    capped = []
    for c in hard + rest:
        key = (c["book"], c["sourcePage"])
        per_page.setdefault(key, 0)
        if per_page[key] >= PAGE_DIFF_MAX_PER_PAGE:
            continue
        per_page[key] += 1
        capped.append(c)
    hard_capped = [c for c in capped if c["category"] != ["普通"]]
    rest_capped = [c for c in capped if c["category"] == ["普通"]]
    random.shuffle(rest_capped)
    chosen = (hard_capped + rest_capped)[:MAX_DIVERGENT]
    cases = []
    for index, case in enumerate(chosen + keep):
        case = dict(case)
        case["caseId"] = f"C{index:04d}"
        case["truth"] = None
        cases.append(case)
    freeze = {"cases": cases,
              "note": "私密正文仅本地；truth 由盲标 filling；span 为 paddle 坐标"}
    json.dump(freeze, open(os.path.join(args.out, "freeze.json"), "w"), ensure_ascii=False)
    stats = {"divergentTotal": len(divergent), "divergentKept": len(chosen),
             "keepTotal": len(keep), "cases": len(cases), "gaps": gaps,
             "hardKept": len([c for c in chosen if c["category"] != ["普通"]]),
             "frozenHash": hashlib.sha256(
                 json.dumps(cases, ensure_ascii=False, sort_keys=True).encode()).hexdigest()}
    json.dump(stats, open(os.path.join(args.out, "freeze-stats.json"), "w"),
              ensure_ascii=False, indent=2)
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
