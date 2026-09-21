"""F00：性能原始采样（合成小样本观察值）与摘要自动生成。

用法：
  python3 scripts/verification/perf_samples.py --server http://127.0.0.1:18771 --out metrics
产物：
  metrics/raw-timings.csv + metrics/summary.json（摘要恒由 CSV 重算，不手工填写）
注意：样本极少，只算观察值，不算容量或尾延迟验收；三次导出不用于宣称稳定 p95。
"""
import argparse
import csv
import json
import os
import statistics
import sys
import time
import urllib.request

BOOK = "aaaaaaaa-1111-1111-1111-111111111111"


def get(base, path):
    start = time.monotonic()
    with urllib.request.urlopen(base + path, timeout=60) as r:
        r.read()
    return (time.monotonic() - start) * 1000.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rows = []
    for _ in range(3):
        rows.append(("export", get(args.server, f"/api/books/{BOOK}/export?pages=1")))
    for _ in range(10):
        rows.append(("page-get", get(args.server, f"/api/books/{BOOK}/pages/1")))
    for _ in range(10):
        rows.append(("job-status", get(args.server, f"/api/books/{BOOK}/job")))
    csv_path = os.path.join(args.out, "raw-timings.csv")
    with open(csv_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["scenario", "duration_ms"])
        writer.writerows([(scenario, f"{duration:.1f}") for scenario, duration in rows])
    summary = summarize(csv_path)
    with open(os.path.join(args.out, "summary.json"), "w") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False))


def summarize(csv_path):
    groups = {}
    with open(csv_path, newline="") as f:
        for row in csv.DictReader(f):
            groups.setdefault(row["scenario"], []).append(float(row["duration_ms"]))
    summary = {"scenarios": {}, "note": "small-sample observations only; not capacity or tail-latency acceptance"}
    for scenario, values in sorted(groups.items()):
        summary["scenarios"][scenario] = {
            "n": len(values),
            "median_ms": round(statistics.median(values), 1),
            "min_ms": round(min(values), 1),
            "max_ms": round(max(values), 1),
        }
    return summary


if __name__ == "__main__":
    sys.exit(main())
