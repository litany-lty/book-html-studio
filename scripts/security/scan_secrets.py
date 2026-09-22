#!/usr/bin/env python3
"""Offline, redacted credential guard for tracked text and reachable Git blobs.

Reports locations/rules only, never matched values. Heuristics are not proof that
all credentials are absent or revoked. No provider/API calls are made.
"""
from __future__ import annotations
import argparse
import json
import math
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

PREFIX = re.compile(r"(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16})")
PRIVATE = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")
LITERAL = re.compile(r'''(?i)(?:api[-_]?key|access[-_]?token|secret[-_]?key|client[-_]?secret|password|setApiKey|setAccessToken|setSecretKey)\s*["']?\s*[:=(]\s*["']([A-Za-z0-9_+/=.\-]{16,})["']''')
BEARER = re.compile(r"(?i)Bearer\s+([A-Za-z0-9_+/=.\-]{24,})")
PLACEHOLDER = re.compile(r"(?i)(?:redacted|placeholder|example|dummy|test[-_]|fake[-_]|your[-_]|replace[-_]|not[-_]a[-_]real)")
MAX_TEXT = 8 * 1024 * 1024

def plausible(value: str) -> bool:
    if PLACEHOLDER.search(value): return False
    counts = Counter(value)
    entropy = -sum((n / len(value)) * math.log2(n / len(value)) for n in counts.values())
    return len(counts) > 8 and entropy >= 3.0

def findings(text: str) -> list[tuple[int, str]]:
    hits = set()
    for rule, pattern in [('private-key', PRIVATE), ('provider-token', PREFIX), ('credential-literal', LITERAL), ('bearer-literal', BEARER)]:
        for match in pattern.finditer(text):
            value = match.group(1) if match.lastindex else match.group()
            if rule != 'private-key' and not plausible(value): continue
            hits.add((text.count('\n', 0, match.start()) + 1, rule))
    return sorted(hits)

def git(*args: str) -> bytes:
    return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL)

def scan(history: bool) -> dict:
    issues, seen, skipped, texts = [], set(), 0, 0
    # Working tree checks include staged/untracked candidates, excluding ignored runtime files.
    names = git('ls-files', '-z', '--cached', '--others', '--exclude-standard').decode().split('\0')
    for name in sorted(set(names)):
        if not name: continue
        path = Path(name)
        if not path.is_file() or path.is_symlink(): continue
        raw = path.read_bytes()
        if len(raw) > MAX_TEXT or b'\0' in raw: skipped += 1; continue
        try: text = raw.decode('utf-8')
        except UnicodeDecodeError: skipped += 1; continue
        texts += 1
        for line, rule in findings(text): issues.append({'path': name, 'line': line, 'rule': rule, 'scope': 'worktree'})
        if (path.name == '.env' or path.name.startswith('.env.')) and path.name != '.env.example':
            issues.append({'path': name, 'line': 1, 'rule': 'tracked-runtime-env', 'scope': 'worktree'})
    if history:
        objects = git('rev-list', '--objects', '--all').decode().splitlines()
        proc = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        assert proc.stdin is not None and proc.stdout is not None
        for item in objects:
            oid, _, name = item.partition(' ')
            if oid in seen: continue
            seen.add(oid)
            proc.stdin.write((oid + '\n').encode()); proc.stdin.flush()
            header = proc.stdout.readline().decode().strip().split()
            if len(header) != 3: raise RuntimeError('Cannot read reachable object')
            kind, size = header[1], int(header[2])
            raw = proc.stdout.read(size); proc.stdout.read(1)
            if kind != 'blob': continue
            if size > MAX_TEXT or b'\0' in raw: skipped += 1; continue
            try: text = raw.decode('utf-8')
            except UnicodeDecodeError: skipped += 1; continue
            texts += 1
            for line, rule in findings(text): issues.append({'object': oid[:12], 'path': name, 'line': line, 'rule': rule, 'scope': 'reachable-history'})
        proc.stdin.close(); proc.stdout.close()
        if proc.wait() != 0: raise RuntimeError('Git object scan failed')
    return {'schemaVersion': 1, 'historyIncluded': history, 'textObjectsChecked': texts,
            'binaryOrOversizedSkipped': skipped, 'findings': issues,
            'limitations': 'Heuristic UTF-8 text scan; binary/oversized/unreachable/remote-fork objects and provider revocation are not verified.'}

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--history', action='store_true')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    report = scan(args.history)
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True); args.out.write_text(encoded + '\n')
    print(encoded)
    return 1 if report['findings'] else 0

if __name__ == '__main__':
    sys.exit(main())
