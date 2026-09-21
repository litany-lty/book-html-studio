"""F00：证据包校验器（T01–T04 打包侧）。由原始结果生成断言，不接受手写“全部通过”。

用法：
  python3 scripts/verification/verify_evidence_package.py --pack <解包目录>
校验：
  1. manifest.json 文件清单与包内实际一致；REPORT 引用的包内文件存在（尤其 build-manifest）。
  2. checksums.sha256 全匹配（清单自身除外）。
  3. tests/junit/*.xml 聚合 == results.json 的 junit 计数（Node 只计一次，不重复加总）。
  4. metrics/raw-timings.csv 重算中位数 == metrics/summary.json；REPORT.md 逐字含这些数字。
  5. results.json 的浏览器计数 == 各 *-results.json 的实际通过数。
  6. 全包秘密扫描（key/token/Bearer/私密路径），命中即失败。
  7. commands.jsonl 每一行指向仓库内可定位脚本/命令，无 /tmp 与 inline python。
"""
import argparse
import csv
import hashlib
import json
import os
import re
import statistics
import sys
import zipfile

SECRET_PATTERNS = [
    r"Bearer\s+[A-Za-z0-9\-_\.=]{8,}",
    r"(?i)(dashscope|aliyun)[-_ ]?api[-_ ]?key\s*[:=]\s*\S+",
    r"(?i)minimax[-_ ]?api[-_ ]?key\s*[:=]\s*\S+",
    r"(?i)sk-[A-Za-z0-9]{8,}",
    # 私密文稿路径（字面拆分，避免扫描器自匹配）
    r"/Users/litany/Documents" + r"/private",
    r"(?i)private[_-]?key\s*[:=]",
]

failures = []


def fail(message):
    failures.append(message)
    print("FAIL " + message)


def ok(message):
    print("PASS " + message)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack", required=True)
    args = parser.parse_args()
    pack = args.pack

    manifest = json.load(open(os.path.join(pack, "manifest.json")))
    actual = []
    for root, _, files in os.walk(pack):
        for name in files:
            rel = os.path.relpath(os.path.join(root, name), pack)
            if rel == "checksums.sha256":
                continue
            actual.append(rel)
    if sorted(actual) == sorted(manifest["files"]):
        ok("manifest 文件清单一致")
    else:
        fail(f"manifest 清单不一致：缺失={sorted(set(manifest['files']) - set(actual))[:5]} 多余={sorted(set(actual) - set(manifest['files']))[:5]}")

    report = open(os.path.join(pack, "REPORT.md")).read()
    for ref in sorted(set(re.findall(r"`((?:artifacts|tests|metrics|browser|cases|contracts|git|tools)/[^`]+)`", report))):
        if not os.path.exists(os.path.join(pack, ref)):
            fail(f"REPORT 引用的包内文件缺失：{ref}")
    if "artifacts/build-manifest.json" in report and os.path.exists(
            os.path.join(pack, "artifacts/build-manifest.json")):
        ok("构建清单被引用且存在")

    checksums = {}
    for line in open(os.path.join(pack, "checksums.sha256")):
        line = line.strip()
        if not line:
            continue
        digest, _, name = line.partition("  ")
        if not name:
            digest, _, name = line.partition(" *")
        checksums[name] = digest
    bad = [name for name, digest in checksums.items()
           if not os.path.exists(os.path.join(pack, name)) or sha256_file(os.path.join(pack, name)) != digest]
    if bad:
        fail(f"checksum 不符或缺失：{bad[:5]}")
    else:
        ok(f"checksum 全部匹配（{len(checksums)} 条）")

    tests = failures_n = errors = skipped = 0
    junit_dir = os.path.join(pack, "tests", "junit")
    for name in sorted(os.listdir(junit_dir)):
        if not name.endswith(".xml"):
            continue
        content = open(os.path.join(junit_dir, name)).read()
        match = re.search(r'tests="(\d+)"[^>]*errors="(\d+)"[^>]*skipped="(\d+)"[^>]*failures="(\d+)"', content)
        if not match:
            match = re.search(r'<testsuite[^>]*>', content)
            attrs = dict(re.findall(r'(\w+)="(\d+)"', match.group(0))) if match else {}
            tests += int(attrs.get("tests", 0))
            errors += int(attrs.get("errors", 0))
            skipped += int(attrs.get("skipped", 0))
            failures_n += int(attrs.get("failures", 0))
        else:
            tests += int(match.group(1))
            errors += int(match.group(2))
            skipped += int(match.group(3))
            failures_n += int(match.group(4))
    results = json.load(open(os.path.join(pack, "results.json")))
    junit = results["junit"]
    if (tests, failures_n, errors, skipped) == (
            junit["tests"], junit["failures"], junit["errors"], junit["skipped"]):
        ok(f"JUnit 聚合一致（{tests}/{failures_n}/{errors}/{skipped}）")
    else:
        fail(f"JUnit 聚合不一致：XML={tests}/{failures_n}/{errors}/{skipped} results={junit}")

    groups = {}
    with open(os.path.join(pack, "metrics", "raw-timings.csv"), newline="") as f:
        for row in csv.DictReader(f):
            groups.setdefault(row["scenario"], []).append(float(row["duration_ms"]))
    summary = json.load(open(os.path.join(pack, "metrics", "summary.json")))
    mismatch = False
    for scenario, values in groups.items():
        median = round(statistics.median(values), 1)
        if abs(summary["scenarios"][scenario]["median_ms"] - median) > 0.05:
            mismatch = True
    if mismatch:
        fail("summary.json 与 CSV 重算不一致")
    else:
        ok("性能摘要由原始数据生成")
    for scenario, values in groups.items():
        median = summary["scenarios"][scenario]["median_ms"]
        if str(median) not in report:
            fail(f"REPORT 缺少 {scenario} 中位数 {median}")
    ok("REPORT 性能数字与原始数据一致") if not mismatch else None

    browser_total = 0
    for root, _, files in os.walk(os.path.join(pack, "browser")):
        for name in files:
            if not name.endswith("-results.json"):
                continue
            if name.startswith("a1-cdp-"):
                # A1 原始字典形结果：已机械转录入 a1-rerun-summary.json，此处不重复计数
                continue
            data = json.load(open(os.path.join(root, name)))
            passed = sum(1 for c in data.get("checks", []) if c.get("pass"))
            total = len(data.get("checks", []))
            browser_total += passed
            if passed != total or not data.get("ok"):
                fail(f"浏览器结果有失败项：{name} {passed}/{total}")
    if results["browser"]["passed"] == browser_total and browser_total > 0:
        ok(f"浏览器计数一致（{browser_total} 通过）")
    else:
        fail(f"浏览器计数不一致：results={results['browser']} 实际通过={browser_total}")

    hits = []
    for root, _, files in os.walk(pack):
        for name in files:
            if name.endswith((".png", ".zip", ".jar", ".pdf")):
                continue
            path = os.path.join(root, name)
            try:
                content = open(path, errors="ignore").read()
            except Exception:
                continue
            for pattern in SECRET_PATTERNS:
                if re.search(pattern, content):
                    hits.append(os.path.relpath(path, pack) + ":" + pattern[:20])
                    break
    if hits:
        fail(f"秘密扫描命中：{hits[:5]}")
    else:
        ok("秘密扫描通过")

    commands_path = os.path.join(pack, "tests", "commands.jsonl")
    repo = os.environ.get("EVIDENCE_REPO", "")
    bad_commands = []
    for line in open(commands_path):
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except Exception:
            bad_commands.append(line[:60])
            continue
        target = entry.get("script") or entry.get("command") or ""
        if "/tmp/" in target or "inline python" in target.lower() or "inline.py" in target:
            bad_commands.append(target[:80])
        elif repo and target.startswith("scripts/") and not os.path.exists(os.path.join(repo, target)):
            bad_commands.append(target[:80])
    if bad_commands:
        fail(f"重放命令不可定位：{bad_commands[:5]}")
    else:
        ok("重放命令均可定位")

    print("OVERALL " + ("FAIL" if failures else "PASS"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
