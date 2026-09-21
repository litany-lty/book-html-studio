"""F00：构建清单生成。绑定受测源码、产物 JAR 与关键资源 hash，形成运行产物身份链。

用法：
  python3 scripts/verification/build_manifest.py --jar target/book-html-studio.jar --out artifacts
产物：
  artifacts/build-manifest.json / build-sha.txt / resource-hashes.json
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(args):
    return subprocess.run(["git"] + args, cwd=REPO, capture_output=True, text=True)


KEY_RESOURCES = [
    "src/main/resources/static/reading-layout.js",
    "src/main/resources/static/reader.js",
    "src/main/resources/static/app.js",
    "src/main/resources/static/api.js",
    "src/main/resources/static/store.js",
    "src/main/resources/static/decision.js",
    "src/main/resources/export/issue-review.js",
    "src/main/resources/export/offline-edit-store.js",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    head = git(["rev-parse", "HEAD"])
    status = git(["status", "--porcelain"])
    head_sha = head.stdout.strip()
    dirty = bool(status.stdout.strip())
    jar_sha = sha256_file(args.jar)

    resources = {}
    for rel in KEY_RESOURCES:
        full = os.path.join(REPO, rel)
        blob = git(["hash-object", full]).stdout.strip()
        resources[rel] = {"sha256": sha256_file(full), "gitBlob": blob,
                          "bytes": os.path.getsize(full)}

    manifest = {
        "testedCommit": head_sha,
        "worktreeDirty": dirty,
        "worktreeStatus": status.stdout.strip().splitlines(),
        "jar": {"path": os.path.basename(args.jar), "sha256": jar_sha,
                "bytes": os.path.getsize(args.jar)},
        "keyResources": resources,
        "jdk": os.environ.get("JAVA_HOME", ""),
        "contractVersion": "v1-2026-09-21",
        "questionTemplate": "question-template-v1",
        "policyVersion": "decision-policy-v1",
        "thresholdProfile": "pilot-default-v1",
    }
    with open(os.path.join(args.out, "build-manifest.json"), "w") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    with open(os.path.join(args.out, "build-sha.txt"), "w") as f:
        f.write(jar_sha + "\n")
    with open(os.path.join(args.out, "resource-hashes.json"), "w") as f:
        json.dump(resources, f, ensure_ascii=False, indent=2)
    print(json.dumps({"jarSha256": jar_sha, "commit": head_sha, "dirty": dirty},
                     ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
