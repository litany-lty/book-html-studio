"""F00：离线导入备份夹具生成（版本化，可重放）。

A1 cdp_import_restart.py 需要与当前种子数据 bookUid 一致的备份文件。
用法：
  python3 scripts/verification/make_import_backup.py --book-uid '<baseId>|<pdfSha>' --out <path>
生成 2 条记录（第 1 页普通修订 + 第 2 页携带 JEV_ASSISTED 来源元数据，验证透传）。
"""
import argparse
import json


def record(book_uid, source_page, block_id, issue_id, original, replacement, resolution):
    return {
        "key": book_uid + "\u0000" + str(source_page) + "\u0000" + block_id + "\u0000" + issue_id,
        "bookUid": book_uid,
        "sourcePage": source_page,
        "blockId": block_id,
        "issueId": issue_id,
        "editRevision": 1,
        "original": original,
        "simplified": original,
        "issueBasis": {"kind": "suspected", "start": 0, "end": 2,
                       "simplifiedStart": 0, "simplifiedEnd": 2,
                       "originalQuote": original[:2], "simplifiedQuote": original[:2]},
        "resolved": True,
        "replacement": replacement,
        "resolution": resolution,
        "updatedAt": 1789963008302,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--book-uid", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    records = [
        record(args.book_uid, 1, "b1", "issue-1", "甲乙丙丁戊己", "TAB2-NEW", None),
        record(args.book_uid, 2, "c1", "issue-2", "寅卯辰巳午未", "寅卯-IMPORT",
               {"origin": "JEV_ASSISTED", "decisionId": "d1", "candidateId": "c1",
                "candidateSetHash": "cs1", "userAttestedSourceCheck": True}),
    ]
    backup = {"schemaVersion": 1, "kind": "book-html-offline-backup",
              "bookUid": args.book_uid, "exportedAt": 1789963008302, "records": records}
    with open(args.out, "w") as f:
        json.dump(backup, f, ensure_ascii=False)
    print("wrote " + args.out)


if __name__ == "__main__":
    main()
