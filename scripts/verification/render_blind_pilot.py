"""Render private JR-14 source-only labeling pages, never OCR choices or model picks.

Example:
  python3 scripts/verification/render_blind_pilot.py \
    --pilot /tmp/jr14-pilot/pilot.json --bookmap /tmp/eval-bookmap.json \
    --runs /tmp/eval-runs --pdfdir /path/to/private-pdfs \
    --out /tmp/jr14-pilot/blind-board

Requires PyMuPDF and Pillow locally. Output stays outside the repository. The
OCR block bounding box is only a locator; no per-character geometry is claimed.
The page image and block crop are rendered from the source PDF, not OCR output.
"""

import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re

import fitz
from PIL import Image


CASE_ID = re.compile(r"[A-Za-z0-9_-]+\Z")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def private_file(path, data):
    with path.open("w", encoding="utf-8") as output:
        output.write(data)
    path.chmod(0o600)


def load_block(runs, book_id, page_number, block_id):
    path = runs / f"paddle-{book_id[:8]}-{page_number}.json"
    page = json.loads(path.read_text(encoding="utf-8"))
    for block in page.get("blocks", []):
        if block.get("id") == block_id:
            return block
    raise ValueError(f"OCR locator missing for page {page_number}, block {block_id}")


def normalized_box(block):
    box = block.get("bbox")
    if not isinstance(box, list) or len(box) != 4:
        raise ValueError("OCR locator lacks normalized bbox")
    x, y, width, height = (float(value) for value in box)
    if not (0 <= x < 1 and 0 <= y < 1 and 0 < width <= 1 and 0 < height <= 1
            and x + width <= 1.01 and y + height <= 1.01):
        raise ValueError("OCR locator bbox is invalid")
    return [x, y, width, height]


def masked_context(original, span):
    if not isinstance(original, str) or not isinstance(span, list) or len(span) != 2:
        raise ValueError("case has no original text/span for masked localization")
    start, end = span
    if not (isinstance(start, int) and isinstance(end, int) and 0 <= start < end <= len(original)):
        raise ValueError("case span is outside original block")
    return original[max(0, start - 12):start] + "【待标】" + original[end:end + 12]


def render_board(pilot_path, bookmap_path, runs, pdfdir, out):
    pilot = json.loads(pilot_path.read_text(encoding="utf-8"))
    bookmap = json.loads(bookmap_path.read_text(encoding="utf-8"))
    cases = pilot.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("pilot contains no cases")
    if out.exists() and any(out.iterdir()):
        raise ValueError("output directory must be new or empty; existing labels will not be overwritten")
    out.mkdir(mode=0o700, parents=True, exist_ok=True)
    out.chmod(0o700)
    (out / "pages").mkdir(mode=0o700)
    (out / "crops").mkdir(mode=0o700)
    documents = {}
    verified_pdfs = {}
    rendered_pages = set()
    public_cases = []
    seen_ids = set()
    try:
        for case in cases:
            case_id = case.get("caseId")
            if not isinstance(case_id, str) or not CASE_ID.fullmatch(case_id) or case_id in seen_ids:
                raise ValueError("case IDs must be unique and filename-safe")
            seen_ids.add(case_id)
            book_id = case["book"]
            page_number = case["sourcePage"]
            meta = bookmap.get(book_id)
            if not isinstance(book_id, str) or not CASE_ID.fullmatch(book_id) or meta is None or not isinstance(page_number, int):
                raise ValueError(f"source mapping missing for {case_id}")
            pdf_name = meta["pdf"]
            if Path(pdf_name).name != pdf_name:
                raise ValueError("bookmap PDF name must not contain a path")
            pdf = pdfdir / pdf_name
            expected_sha = case.get("pdfSha256") or meta.get("pdfSha256")
            if pdf not in verified_pdfs:
                if not pdf.is_file():
                    raise FileNotFoundError(pdf)
                actual_sha = digest(pdf)
                if expected_sha and actual_sha != expected_sha:
                    raise ValueError(f"source PDF hash mismatch for {pdf_name}")
                verified_pdfs[pdf] = actual_sha
                documents[book_id] = fitz.open(pdf)
            if expected_sha and verified_pdfs[pdf] != expected_sha:
                raise ValueError(f"case PDF hash mismatch for {case_id}")
            document = documents[book_id]
            if not (1 <= page_number <= len(document)):
                raise ValueError(f"page number outside PDF for {case_id}")
            block = load_block(runs, book_id, page_number, case["blockId"])
            original = block.get("original")
            if original != case.get("frozenOriginal"):
                raise ValueError(f"frozen source differs from OCR locator for {case_id}")
            bbox = normalized_box(block)
            context = masked_context(original, case["span"])
            page_key = f"{book_id}-{page_number:04d}"
            page_file = out / "pages" / f"{page_key}.jpg"
            source_page = document[page_number - 1]
            if page_key not in rendered_pages:
                pix = source_page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
                image = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
                image.save(page_file, "JPEG", quality=94, subsampling=0)
                page_file.chmod(0o600)
                rendered_pages.add(page_key)
            crop_file = out / "crops" / f"{case_id}.png"
            # Source-only crop: whole OCR block plus margin, never inferred per-glyph coordinates.
            page_rect = source_page.rect
            x, y, width, height = bbox
            margin_x = max(width * 0.08, 0.015)
            margin_y = max(height * 0.08, 0.015)
            clip = fitz.Rect(max(0, x - margin_x) * page_rect.width,
                             max(0, y - margin_y) * page_rect.height,
                             min(1, x + width + margin_x) * page_rect.width,
                             min(1, y + height + margin_y) * page_rect.height)
            pix = source_page.get_pixmap(matrix=fitz.Matrix(3, 3), clip=clip, alpha=False)
            crop = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
            crop.save(crop_file, "PNG")
            crop_file.chmod(0o600)
            public_cases.append({
                "caseId": case_id,
                "book": pdf_name,
                "page": page_number,
                "pageImage": f"pages/{page_key}.jpg",
                "cropImage": f"crops/{case_id}.png",
                "bbox": bbox,
                "maskedContext": context,
            })
    finally:
        for document in documents.values():
            document.close()
    pilot_sha = digest(pilot_path)
    board = {"datasetSha256": pilot_sha, "cases": public_cases}
    private_file(out / "board.json", json.dumps(board, ensure_ascii=False, indent=2))
    private_file(out / "index.html", html_page(board))
    return len(public_cases), len(rendered_pages)


def html_page(board):
    data = json.dumps(board, ensure_ascii=False).replace("<", "\\u003c")
    title = html.escape("JR-14 原图盲标")
    return """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>""" + title + """</title><style>
*{box-sizing:border-box}body{margin:0;background:#f3f1eb;color:#252b32;font:16px/1.55 system-ui,sans-serif}
header{position:sticky;top:0;z-index:2;background:#fff;border-bottom:1px solid #d9dfe5;padding:10px 18px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
h1{font-size:20px;margin:0}.small{font-size:13px;color:#5f6b75}.layout{display:grid;grid-template-columns:240px minmax(0,1fr);min-height:calc(100vh - 72px)}
nav{border-right:1px solid #d9dfe5;background:#fff;padding:12px;max-height:calc(100vh - 72px);overflow:auto}
nav button{display:block;width:100%;margin:3px 0;padding:7px 10px;text-align:left;border:1px solid #d9dfe5;border-radius:7px;background:#fff;cursor:pointer}
nav button.active{border-color:#365f8d;background:#e9f1fa}nav button.done::after{content:" ✓";color:#287347}
main{padding:20px;max-width:1250px;width:100%;margin:auto}section{background:#fff;border:1px solid #d9dfe5;border-radius:12px;padding:16px;margin-bottom:16px}
.page{position:relative;display:inline-block;max-width:100%}.page img{display:block;max-width:100%;max-height:65vh}.box{position:absolute;border:3px solid #1769b0;background:#1769b022;pointer-events:none}
.crop{display:block;max-width:100%;max-height:55vh;object-fit:contain}.context{font-size:18px;overflow-wrap:anywhere;background:#f7f8fa;padding:10px;border-radius:8px}
label.option{display:inline-block;margin:7px 15px 7px 0}input[type=text],textarea{width:100%;padding:10px;border:1px solid #aab4bd;border-radius:7px;font:inherit}
textarea{min-height:65px}button{font:inherit}button.primary{background:#235b8d;color:#fff;border:0;border-radius:8px;padding:9px 16px;cursor:pointer}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.warn{color:#913e27}
@media(max-width:760px){.layout{display:block}nav{max-height:135px;display:flex;gap:6px;overflow:auto}nav button{min-width:100px}main{padding:10px}}
</style><header><h1>JR-14 原图盲标</h1><span id="progress" class="small"></span><button id="download" class="primary">下载标注 JSON</button></header>
<div class="layout"><nav id="cases" aria-label="待标案例"></nav><main>
<section><h2 id="case-title"></h2><p id="source" class="small"></p><p class="small warn">蓝框仅是 OCR 块位置，不是精确字框。先看原图；定位不唯一或无法断字时选“无法判定”，不要猜字。此页面不含候选或 JEV 结论。</p>
<p>遮蔽上下文（仅供定位，目标文字不显示）：</p><p id="context" class="context"></p>
<h3>局部原图</h3><a id="crop-link" target="_blank" rel="noopener"><img id="crop" class="crop" alt="原 PDF 的 OCR 块及邻近区域"></a>
<h3>整页原图</h3><a id="page-link" target="_blank" rel="noopener"><span class="page"><img id="page" alt="原 PDF 整页"><span id="box" class="box"></span></span></a>
</section><section><h3>人工判断</h3><div id="choices">
<label class="option"><input type="radio" name="status" value="READABLE_WITH_TRUTH"> 可辨认</label>
<label class="option"><input type="radio" name="status" value="UNREADABLE"> 原图不可辨认</label>
<label class="option"><input type="radio" name="status" value="AMBIGUOUS"> 无法唯一定位或判定</label></div>
<label class="option"><input type="radio" name="status" value="NOT_TEXT"> 仅为换行/列间空白，没有待认文字</label>
<label>原字转录（仅“可辨认”时填写；保持原字，不转简体）<input id="truth" type="text" autocomplete="off"></label>
<label>备注（可选）<textarea id="note"></textarea></label><p id="warning" class="small warn"></p>
<div class="row"><button id="prev">上一案</button><button id="next">下一案</button><span class="small">填写会尝试自动保存在本浏览器；请定期下载 JSON 备份。</span></div>
</section></main></div>
<script>const board=""" + data + """;
const key='jr14-blind-'+board.datasetSha256;let labels={};try{labels=JSON.parse(localStorage.getItem(key)||'{}')}catch(e){}let index=0;
const byId=id=>document.getElementById(id);const valid=['READABLE_WITH_TRUTH','UNREADABLE','AMBIGUOUS','NOT_TEXT'];
function complete(v){return Boolean(v&&valid.includes(v.status)&&(v.status!=='READABLE_WITH_TRUTH'||(v.originalScriptTruth||'').trim().length>0))}
function persist(){try{localStorage.setItem(key,JSON.stringify(labels));byId('warning').textContent=''}catch(e){byId('warning').textContent='浏览器自动保存不可用，请立即下载 JSON 备份。'}}
function save(){const c=board.cases[index],status=document.querySelector('input[name=status]:checked')?.value||'UNLABELED';
labels[c.caseId]={status,originalScriptTruth:status==='READABLE_WITH_TRUTH'?byId('truth').value:'',note:byId('note').value};persist();refreshNav()}
function refreshNav(){const n=board.cases.filter(c=>complete(labels[c.caseId])).length;byId('progress').textContent=n+'/'+board.cases.length+' 已标';
for(const [i,b] of [...byId('cases').children].entries()){b.classList.toggle('active',i===index);b.classList.toggle('done',complete(labels[board.cases[i].caseId]))}}
function show(i){index=Math.max(0,Math.min(board.cases.length-1,i));const c=board.cases[index],v=labels[c.caseId]||{};
byId('case-title').textContent=(index+1)+' / '+board.cases.length+' · '+c.caseId;
byId('source').textContent=c.book+' · PDF 第 '+c.page+' 页';byId('context').textContent=c.maskedContext;
byId('crop').src=c.cropImage;byId('crop-link').href=c.cropImage;byId('page').src=c.pageImage;byId('page-link').href=c.pageImage;
const [x,y,w,h]=c.bbox,box=byId('box');box.style.left=(x*100)+'%';box.style.top=(y*100)+'%';box.style.width=(w*100)+'%';box.style.height=(h*100)+'%';
document.querySelectorAll('input[name=status]').forEach(r=>{r.checked=r.value===v.status});
byId('truth').value=v.originalScriptTruth||'';byId('note').value=v.note||'';byId('truth').disabled=v.status!=='READABLE_WITH_TRUTH';refreshNav();}
for(const [i,c] of board.cases.entries()){const b=document.createElement('button');b.type='button';b.textContent=(i+1)+'. '+c.caseId;b.onclick=()=>show(i);byId('cases').appendChild(b)}
document.querySelectorAll('input[name=status]').forEach(r=>r.onchange=()=>{byId('truth').disabled=r.value!=='READABLE_WITH_TRUTH';save()});
byId('truth').oninput=save;byId('note').oninput=save;byId('prev').onclick=()=>show(index-1);byId('next').onclick=()=>show(index+1);
byId('download').onclick=()=>{save();const result={schemaVersion:'jr14-blind-labels-v1',datasetSha256:board.datasetSha256,
reviewerType:'HUMAN',reviewedByHuman:true,sourceOnlyReview:true,
complete:board.cases.every(c=>complete(labels[c.caseId])),labels:board.cases.map(c=>({caseId:c.caseId,...(labels[c.caseId]||{status:'UNLABELED',originalScriptTruth:'',note:''})}))};
const blob=new Blob([JSON.stringify(result,null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');
a.href=url;a.download='jr14-human-labels.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};show(0);
</script></html>"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pilot", type=Path, required=True)
    parser.add_argument("--bookmap", type=Path, required=True)
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--pdfdir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    count, pages = render_board(args.pilot, args.bookmap, args.runs, args.pdfdir, args.out)
    print(f"source-only board: {count} cases, {pages} PDF pages, {args.out / 'index.html'}")


if __name__ == "__main__":
    main()
