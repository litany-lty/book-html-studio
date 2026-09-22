#!/usr/bin/env python3
"""Offline, redacted credential guard. Never print matched bytes, snippets, or values.

Exit 1: findings; exit 2: incomplete scan. This is not a guarantee against encoded,
unknown-format, binary-container secrets or inaccessible/deleted remote history.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from collections import Counter
from math import log2

RULES = (
    ("private-key", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")),
    ("github-token", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b")),
    ("provider-key", re.compile(rb"\bsk-(?:proj-|ant-api\d+-)?[A-Za-z0-9_-]{24,}\b")),
    ("aws-access-key", re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("google-api-key", re.compile(rb"\bAIza[A-Za-z0-9_-]{30,}\b")),
    ("bearer-literal", re.compile(rb"\bBearer\s+[A-Za-z0-9._~-]{24,}")),
)
ASSIGNMENT = re.compile(
    rb"(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|client[_-]?secret|password|passwd)"
    rb"[\"']?\s*[:=]\s*[\"']([A-Za-z0-9+/_=.!~:-]{20,})[\"']"
)
ENV_ASSIGNMENT = re.compile(
    rb"(?im)^[ \t]*[A-Za-z0-9_.-]*(?:api[_-]?key|secret[_-]?key|access[_-]?token|client[_-]?secret|password)"
    rb"[ \t]*[:=][ \t]*([A-Za-z0-9+/_=.!~:-]{20,})[ \t]*$"
)
PLACEHOLDER = re.compile(rb"(?i)^(?:test[-_]|fake[-_]|mock[-_]|dummy[-_]|example[-_]|your[-_]|replace[-_]|<|\$\{)")
MAX_BLOB = 64 * 1024 * 1024


def entropy(value: bytes) -> float:
    return -sum((count / len(value)) * log2(count / len(value)) for count in Counter(value).values())


def findings(data: bytes, location: str) -> list[dict]:
    if b"\0" in data[:8192]:
        return []
    output = []
    for rule, pattern in RULES:
        for match in pattern.finditer(data):
            output.append({"location": location, "line": data.count(b"\n", 0, match.start()) + 1, "rule": rule})
    for match in list(ASSIGNMENT.finditer(data)) + list(ENV_ASSIGNMENT.finditer(data)):
        value = match.group(1)
        if not PLACEHOLDER.match(value) and entropy(value) >= 3.6:
            output.append({"location": location, "line": data.count(b"\n", 0, match.start()) + 1,
                           "rule": "high-entropy-credential-assignment"})
    return output


def git(*args: str) -> bytes:
    # stderr is deliberately not echoed: Git diagnostics can contain remote URLs.
    return subprocess.run(["git", *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def tracked_scan() -> tuple[list[dict], int, int]:
    output, count, binary = [], 0, 0
    root = Path(git("rev-parse", "--show-toplevel").decode().strip())
    for item in git("ls-files", "-z").split(b"\0"):
        if not item:
            continue
        relative = item.decode("utf-8", "surrogateescape")
        path = root / relative
        if not path.is_file():
            continue  # staged deletion
        if path.is_symlink():
            raise ValueError("Tracked symlink requires manual inspection")
        if path.stat().st_size > MAX_BLOB:
            raise ValueError("Tracked file exceeds scan bound")
        if path.name == ".env" or (path.name.startswith(".env.") and not path.name.endswith("example")):
            output.append({"location": relative, "line": 1, "rule": "tracked-runtime-env"})
        data = path.read_bytes(); count += 1
        binary += int(b"\0" in data[:8192])
        output.extend(findings(data, relative))
    return output, count, binary


def history_scan() -> tuple[list[dict], int, int]:
    # Scan every unique reachable blob, even if it was deleted from the current tree.
    ids = list(dict.fromkeys(line.split(b" ", 1)[0] for line in git("rev-list", "--objects", "--all").splitlines()))
    output, count, binary = [], 0, 0
    with subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as process:
        assert process.stdin is not None and process.stdout is not None
        for oid in ids:
            process.stdin.write(oid + b"\n"); process.stdin.flush()
            header = process.stdout.readline().split()
            if len(header) != 3:
                raise ValueError("Missing Git object")
            size = int(header[2])
            if size > MAX_BLOB:
                raise ValueError("Git object exceeds scan bound")
            data = process.stdout.read(size)
            if len(data) != size or process.stdout.read(1) != b"\n":
                raise ValueError("Truncated Git object")
            if header[1] != b"blob":
                continue
            count += 1; binary += int(b"\0" in data[:8192])
            output.extend(findings(data, "blob:" + oid.decode("ascii")))
        process.stdin.close()
        if process.wait(timeout=30) != 0:
            raise ValueError("Git object scan failed")
    return output, count, binary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    result = {"mode": "tracked+reachable-history" if args.history else "tracked", "status": "PASS",
              "findings": [], "tracked_files": 0, "history_blobs": 0, "binary_files_or_blobs": 0,
              "limitations": "Pattern-based. Binary containers, encoded/unknown secret formats, unavailable remote refs and deleted/unreachable history are not certified clean."}
    code = 0
    try:
        output, count, binary = tracked_scan()
        result.update(findings=output, tracked_files=count, binary_files_or_blobs=binary)
        if args.history:
            output, count, binary = history_scan()
            result["findings"].extend(output); result["history_blobs"] = count
            result["binary_files_or_blobs"] += binary
        if result["findings"]:
            result["status"] = "FINDINGS"; code = 1
    except Exception:
        # No exception text: it might itself contain sensitive data.
        result["status"] = "INCOMPLETE"; code = 2
    encoded = json.dumps(result, ensure_ascii=True, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True); args.out.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return code


if __name__ == "__main__":
    sys.exit(main())
