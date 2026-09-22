#!/usr/bin/env python3
"""Offline repository credential guard. Reports locations/rule names, never matching values.

Heuristic scanning is a gate, not proof that every possible credential is absent.
--history scans all reachable blobs in the fetched refs; unreachable/deleted server objects are out of scope.
"""
from __future__ import annotations
import argparse
import collections
import math
import pathlib
import re
import subprocess
import sys

RULES = {
    'provider-token': re.compile(r'\b(?:sk-[A-Za-z0-9_-]{24,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,}|AKIA[A-Z0-9]{16})\b'),
    'private-key': re.compile(r'-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----'),
    'credential-assignment': re.compile(r'''(?ix)(?:["']?(?:api[_-]?key|secret[_-]?key|access[_-]?token|password|client[_-]?secret)["']?)\s*[:=]\s*["']([A-Za-z0-9_+/=.-]{24,})["']'''),
    'bearer-token': re.compile(r'(?i)\bBearer\s+([A-Za-z0-9_+/=.-]{32,})'),
}

def entropy(value: str) -> float:
    counts = collections.Counter(value)
    return -sum((n / len(value)) * math.log2(n / len(value)) for n in counts.values())

def findings(data: bytes):
    if b'\0' in data[:8192]:
        return []
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        return []
    result = []
    for name, rule in RULES.items():
        for match in rule.finditer(text):
            value = match.group(1) if match.lastindex else match.group(0)
            # Reviewed synthetic sentinels in the repository's separate guard-test branches.
            # Exact equality only: never exempt a whole file, path, or provider prefix.
            if value in {'your-placeholder-value-not-a-real-secret',
                         'your-api-key-replace-with-secret',
                         'test-credential-placeholder-only'}:
                continue
            if name != 'private-key' and (entropy(value) < 3.2 or '${' in value):
                continue
            result.append((name, text.count('\n', 0, match.start()) + 1))
    return result

def git(*args: str) -> bytes:
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)

def scan_current():
    examined = 0
    for raw in git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0'):
        if not raw:
            continue
        path = pathlib.Path(raw.decode('utf-8', 'surrogateescape'))
        if not path.is_file() or path.is_symlink():
            continue
        examined += 1
        for rule, line in findings(path.read_bytes()):
            yield f'{path.as_posix()}:{line} [{rule}]'
    print(f'current_files_examined={examined}')

def scan_history():
    if git('rev-parse', '--is-shallow-repository').strip() == b'true':
        raise RuntimeError('history scan requires fetch-depth: 0; shallow history is not a passing scan')
    objects = git('rev-list', '--objects', '--all').splitlines()
    process = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    examined = 0
    try:
        assert process.stdin and process.stdout
        for item in objects:
            oid, _, path = item.partition(b' ')
            process.stdin.write(oid + b'\n'); process.stdin.flush()
            header = process.stdout.readline().split()
            if len(header) != 3:
                raise RuntimeError('unreadable Git object; incomplete history scan')
            size = int(header[2]); data = process.stdout.read(size)
            if len(data) != size or process.stdout.read(1) != b'\n':
                raise RuntimeError('truncated Git object; incomplete history scan')
            if header[1] != b'blob':
                continue
            examined += 1
            for rule, line in findings(data):
                label = path.decode('utf-8', 'replace') or '(unnamed blob)'
                yield f'{oid.decode()[:12]}:{label}:{line} [{rule}]'
    finally:
        if process.stdin: process.stdin.close()
        process.wait(timeout=10)
    print(f'history_blobs_examined={examined}; refs=all-fetched-reachable')

def self_test():
    synthetic = 'sk' + '-' + 'aB3dE6gH9jK2mN5pQ8sT1vW4yZ7cF0iL'
    assert findings(synthetic.encode())
    assert findings(('apiKey="' + 'aB3dE6gH9jK2mN5pQ8sT1vW4yZ7cF0iL' + '"').encode())
    assert findings(('-' * 5 + 'BEGIN PRIVATE KEY' + '-' * 5).encode())
    assert not findings(b'apiKey=""\napi-key: ${QWEN_API_KEY:}\napiKey="test-key"')
    for sentinel in ('your-placeholder-value-not-a-real-secret',
                     'your-api-key-replace-with-secret', 'test-credential-placeholder-only'):
        assert not findings(('apiKey="' + sentinel + '"').encode())
    assert findings(('apiKey="' + synthetic + '"').encode())
    assert not findings(b'\x00\xffbinary')
    print('secret_scan_selftest=PASS')

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--history', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test(); return 0
    try:
        hits = list(scan_current())
        if args.history: hits.extend(scan_history())
        hits = sorted(set(hits))
        for hit in hits[:100]: print(f'REVIEW_REQUIRED {hit}')
        print(f'credential_findings={len(hits)}; values=REDACTED')
        return 1 if hits else 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'SCAN_INCOMPLETE {type(error).__name__}', file=sys.stderr)
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
