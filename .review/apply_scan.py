"""Apply UTF-8 source deltas only after exact input AND output hashes match.
Temporary transfer helper, removed from the final delivery tree. No eval/exec/network.
"""
import hashlib
import json
from pathlib import Path
import sys

root = Path.cwd().resolve()
data = json.loads((root / '.review/scan-edits.json').read_text(encoding='utf-8'))
check_only = sys.argv[1:] == ['--check-output']
if sys.argv[1:] and not check_only:
    raise SystemExit('Invalid argument')


def target(name):
    path = Path(name)
    if path.is_absolute() or '..' in path.parts or path.parts[0] not in {'src', 'scripts', 'docs'}:
        raise ValueError('Unsafe source path')
    full = root / path
    if full.is_symlink() or full.resolve() != full or not full.is_file():
        raise ValueError('Missing or indirect source path: ' + name)
    return full


def digest(blob):
    return hashlib.sha256(blob).hexdigest()


outputs = []
for name, expected in data['existingNewFiles'].items():
    if digest(target(name).read_bytes()) != expected:
        raise ValueError('New source integrity mismatch: ' + name)
for record in data['files']:
    path = target(record['path'])
    old = path.read_bytes()
    expected = record['after'] if check_only else record['before']
    if digest(old) != expected:
        raise ValueError('Source integrity mismatch: ' + record['path'])
    if check_only:
        continue
    text = old.decode('utf-8')
    previous = 0
    for begin, end, replacement in record['edits']:
        if type(begin) is not int or type(end) is not int or not previous <= begin <= end <= len(text) or not isinstance(replacement, str):
            raise ValueError('Invalid source edit')
        previous = end
    for begin, end, replacement in reversed(record['edits']):
        text = text[:begin] + replacement + text[end:]
    encoded = text.encode('utf-8')
    if digest(encoded) != record['after']:
        raise ValueError('Reviewed output mismatch: ' + record['path'])
    outputs.append((path, encoded))
for path, encoded in outputs:
    path.write_bytes(encoded)
print('Exact source input/output integrity: PASS')
