#!/usr/bin/env python3
"""Offline, redacted secret checks for tracked files and reachable Git history.

Reports contain locations and rule names, never matched values. Heuristics are a
regression guard, not proof that a credential was never exposed or has been revoked.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import re
import subprocess
import sys

RULES = {
    "provider-token": re.compile(r"(?<![\w-])(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{25,}|github_pat_[A-Za-z0-9_]{25,}|AKIA[A-Z0-9]{16})(?![\w-])"),
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "jwt": re.compile(r"\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b"),
}
ASSIGNMENT = re.compile(r'''(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|password)\s*["']?\s*[:=]\s*["']([A-Za-z0-9_+./=-]{20,})["']''')
PLACEHOLDER = re.compile(r"(?i)^(?:example|dummy|test|fake|placeholder)(?:[-_:]|$)")
# Reviewed non-secret fixtures from the independent scanner tests and their Git history.
# Exact values only: never exempt a whole test path, a prefix such as 'your-', or a
# provider/private-key rule. These literals are intentionally not credentials.
REVIEWED_SENTINELS = frozenset({
    "your-placeholder-value-not-a-real-secret",
    "your-api-key-replace-with-secret",
})
MAX_TEXT_BYTES = 4 * 1024 * 1024


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)


def findings(data: bytes, path: str, object_id: str = "") -> list[dict]:
    if b"\x00" in data:
        return []
    try:
        text = data.decode("utf-8")
    except UnicodeError:
        return []
    result = []
    for rule, pattern in RULES.items():
        for match in pattern.finditer(text):
            result.append({"path": path, "line": text.count("\n", 0, match.start()) + 1,
                           "rule": rule, "object": object_id})
    for match in ASSIGNMENT.finditer(text):
        value = match.group(1)
        frequencies = collections.Counter(value)
        entropy = -sum((n / len(value)) * math.log2(n / len(value)) for n in frequencies.values())
        if value in REVIEWED_SENTINELS or PLACEHOLDER.match(value) or re.fullmatch(r"[A-Z_]+", value) or entropy < 3.5:
            continue
        result.append({"path": path, "line": text.count("\n", 0, match.start()) + 1,
                       "rule": "high-entropy-credential", "object": object_id})
    return result


def forbidden_path(path: str) -> bool:
    p = pathlib.PurePosixPath(path)
    name = p.name.lower()
    if name == ".env" or name.startswith(".env."):
        return not name.endswith((".example", ".sample", ".template"))
    return name in {"credentials.json", "service-account.json", "id_rsa", "id_ed25519"} or p.suffix.lower() in {".p12", ".pfx", ".jks", ".keystore"}


def scan(history: bool) -> dict:
    result = {"scope": "reachable-history" if history else "tracked-worktree", "filesScanned": 0,
              "skippedLarge": 0, "findings": [], "limitations": "Heuristic scan; revocation and unreachable/deleted objects are not verified."}
    if not history:
        for raw in git("ls-files", "-z").split(b"\0"):
            if not raw:
                continue
            path = raw.decode("utf-8", "replace")
            if forbidden_path(path):
                result["findings"].append({"path": path, "line": 0, "rule": "private-file-tracked", "object": ""})
            p = pathlib.Path(path)
            if not p.is_file() or p.is_symlink():
                continue
            if p.stat().st_size > MAX_TEXT_BYTES:
                result["skippedLarge"] += 1
                continue
            result["filesScanned"] += 1
            result["findings"].extend(findings(p.read_bytes(), path))
        return result
    objects = git("rev-list", "--objects", "--all").splitlines()
    check = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL)
    try:
        for line in objects:
            parts = line.split(b" ", 1)
            if len(parts) < 2:
                continue
            oid, raw_path = parts
            path = raw_path.decode("utf-8", "replace")
            check.stdin.write(oid + b"\n")
            check.stdin.flush()
            header = check.stdout.readline().split()
            if len(header) != 3:
                raise RuntimeError("unreadable Git object")
            size = int(header[2])
            remaining = size
            chunks = []
            while remaining:
                chunk = check.stdout.read(min(65536, remaining))
                if not chunk:
                    raise RuntimeError("incomplete Git object")
                if size <= MAX_TEXT_BYTES:
                    chunks.append(chunk)
                remaining -= len(chunk)
            check.stdout.read(1)
            if header[1] != b"blob":
                continue
            if forbidden_path(path):
                result["findings"].append({"path": path, "line": 0, "rule": "private-file-in-history", "object": oid.decode()})
            if size > MAX_TEXT_BYTES:
                result["skippedLarge"] += 1
                continue
            result["filesScanned"] += 1
            result["findings"].extend(findings(b"".join(chunks), path, oid.decode()))
    finally:
        check.stdin.close()
        check.stdout.close()
        check.wait()
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true")
    parser.add_argument("--report", type=pathlib.Path)
    args = parser.parse_args()
    try:
        report = scan(args.history)
    except Exception:
        print("SECRET_SCAN_INCOMPLETE: unable to read repository; no raw data printed", file=sys.stderr)
        return 2
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    sys.exit(main())
