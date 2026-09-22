#!/usr/bin/env python3
"""Offline tracked-file / reachable-history secret guard. Never prints matching values.
This is pattern-based detection, not a guarantee of absence or a credential revocation tool.
"""
from __future__ import annotations
import argparse
import json
import re
import subprocess
from pathlib import Path

RULES = {
    "private-key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "provider-key": re.compile(rb"\bsk-[A-Za-z0-9_-]{24,}\b"),
    "github-token": re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    "aws-access-key": re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "literal-credential": re.compile(
        rb'''(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|client[_-]?secret|password)\s*["']?\s*[:=]\s*["']([A-Za-z0-9_+/=.-]{24,})["']'''),
}
# Only obvious non-secret placeholders; no blanket exclusions for tests, docs, or scripts.
PLACEHOLDERS = re.compile(rb"(?i)^(?:your[-_]|replace[-_]|example[-_]|placeholder|test[-_]|dummy[-_])")


def findings(data: bytes) -> list[dict]:
    result = []
    for rule, pattern in RULES.items():
        for match in pattern.finditer(data):
            value = match.group(1) if rule == "literal-credential" else match.group()
            if rule == "literal-credential" and PLACEHOLDERS.match(value):
                continue
            result.append({"rule": rule, "line": data.count(b"\n", 0, match.start()) + 1})
    return result


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)


def scan_current() -> tuple[list[dict], int]:
    found, count = [], 0
    for name in git("ls-files", "-z").split(b"\0"):
        if not name:
            continue
        path = Path(name.decode("utf-8", "surrogateescape"))
        if not path.is_file() or path.is_symlink():
            continue
        count += 1
        if path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example"):
            found.append({"path": str(path), "rule": "tracked-environment-file", "line": 1})
        found.extend({"path": str(path), **item} for item in findings(path.read_bytes()))
    return found, count


def scan_history() -> tuple[list[dict], int]:
    # Inspect all reachable blobs, including deleted files, without logging their content.
    objects = {}
    for row in git("rev-list", "--objects", "--all").splitlines():
        sha, _, path = row.partition(b" ")
        objects[sha.decode()] = path.decode("utf-8", "replace")
    proc = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    found, count = [], 0
    try:
        for sha, path in objects.items():
            proc.stdin.write((sha + "\n").encode()); proc.stdin.flush()
            header = proc.stdout.readline().split()
            if len(header) != 3:
                raise RuntimeError("Could not read a reachable Git object")
            kind, size = header[1], int(header[2])
            data = proc.stdout.read(size)
            if len(data) != size or proc.stdout.read(1) != b"\n":
                raise RuntimeError("Incomplete Git object stream")
            if kind == b"blob":
                count += 1
                found.extend({"blob": sha, "path": path, **item} for item in findings(data))
        proc.stdin.close()
        if proc.wait() != 0:
            raise RuntimeError("Git object inspection failed")
    finally:
        if proc.poll() is None:
            proc.kill(); proc.wait()
    return found, count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    current, files = scan_current()
    historical, blobs = scan_history() if args.history else ([], 0)
    report = {"schemaVersion": 1, "testedCommit": git("rev-parse", "HEAD").decode().strip(),
              "workingTreeDirty": bool(git("status", "--porcelain").strip()),
              "trackedFilesScanned": files, "reachableBlobsScanned": blobs,
              "historyScanned": args.history, "findings": current + historical,
              "limitation": "Pattern detection only; unpushed refs, local files, remote caches and rotated credential status are outside this result."}
    encoded = json.dumps(report, ensure_ascii=True, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
