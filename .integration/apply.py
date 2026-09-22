"""Reproduce only the pinned, locally reviewed integration tree; no network access."""
import base64
import hashlib
import lzma
from pathlib import Path
import subprocess
import tempfile

EXPECTED_TREE = 'ccab7bdb6ee0510f2415c18740ed4db5ee03f41d'
stage = Path('.integration')
compressed = base64.b64decode(''.join((stage / f'part{i}.b64').read_text() for i in range(4)), validate=True)
if hashlib.sha256(compressed).hexdigest() != '659276781f928b1397314842d43af69f6e7d293e766b357a8c36821e70647494':
    raise SystemExit('Integration transfer digest mismatch')
patch = lzma.decompress(compressed)
if len(patch) != 85609:
    raise SystemExit('Integration patch length mismatch')
workflow = (stage / 'final-workflow.yml').read_bytes()
if hashlib.sha256(workflow).hexdigest() != '9bb33cfa4807290d6041b5e5eae3a42bc77bbdb5d723a85d0e1f63d5876bc5b6':
    raise SystemExit('Final read-only workflow digest mismatch')
with tempfile.NamedTemporaryFile(suffix='.patch') as file:
    file.write(patch); file.flush()
    subprocess.run(['git', 'apply', '--index', '--check', file.name], check=True)
    subprocess.run(['git', 'apply', '--index', file.name], check=True)
for name, digest in [('followup.patch', '39a5cc04a387e3ae3ca5eadfa67a79adb037f93113ebfa5e586e77d5d9dbd789'),
                     ('focus-fixture.patch', 'de9252c782849331957b31ab4b5375c4fede9029c8d5ca193445c562ef2150de')]:
    followup = stage / name
    if hashlib.sha256(followup.read_bytes()).hexdigest() != digest:
        raise SystemExit('Followup patch digest mismatch')
    subprocess.run(['git', 'apply', '--index', '--check', str(followup)], check=True)
    subprocess.run(['git', 'apply', '--index', str(followup)], check=True)
patch = lzma.decompress(base64.b64decode((stage / 'dispatch-followup.patch.xz.b64').read_text(), validate=True))
if hashlib.sha256(patch).hexdigest() != '6929b828ac03051eb47521bdc18c38a833cc61e1ce953e307d9f3a3a38e99b7f':
    raise SystemExit('Dispatch probe patch digest mismatch')
with tempfile.NamedTemporaryFile(suffix='.patch') as file:
    file.write(patch); file.flush()
    subprocess.run(['git', 'apply', '--index', '--check', file.name], check=True)
    subprocess.run(['git', 'apply', '--index', file.name], check=True)
Path('.github/workflows/reader-integration.yml').write_bytes(workflow)
subprocess.run(['git', 'add', '.github/workflows/reader-integration.yml'], check=True)
subprocess.run(['git', 'rm', '-r', '--', '.integration'], check=True)
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
tree = subprocess.check_output(['git', 'write-tree'], text=True).strip()
if tree != EXPECTED_TREE:
    raise SystemExit('Refusing an unreviewed output tree: ' + tree)
print('Verified integration tree: ' + tree)
