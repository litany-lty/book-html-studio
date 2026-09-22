"""Reproduce only the pinned, locally reviewed integration tree; no network access."""
import base64
import hashlib
import lzma
from pathlib import Path
import subprocess
import tempfile

EXPECTED_TREE = '40134261c9f8fd00d4b6686d4ed1df8c85069b9b'
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
Path('.github/workflows/reader-integration.yml').write_bytes(workflow)
subprocess.run(['git', 'add', '.github/workflows/reader-integration.yml'], check=True)
subprocess.run(['git', 'rm', '-r', '--', '.integration'], check=True)
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
tree = subprocess.check_output(['git', 'write-tree'], text=True).strip()
if tree != EXPECTED_TREE:
    raise SystemExit('Refusing an unreviewed output tree: ' + tree)
print('Verified integration tree: ' + tree)
