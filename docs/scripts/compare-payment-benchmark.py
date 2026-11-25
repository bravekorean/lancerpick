"""Compare medians of repeated service benchmark runs; never pool per-run percentiles."""
import argparse
import csv
import statistics
from pathlib import Path


def read(path):
    groups = {}
    with path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            key = row["scenario"], int(row["workers"])
            if int(row["unexpected_errors"]):
                raise ValueError(f"Unexpected failures in {path}")
            groups.setdefault(key, []).append(row)
    return groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    args = parser.parse_args()
    before, after = read(args.before), read(args.after)
    expected = {(s, w) for s in ("DUPLICATE_ORDER", "SHARED_WALLET", "INDEPENDENT_WALLETS", "INSUFFICIENT_BALANCE") for w in (1, 4, 8)}
    if before.keys() != expected or after.keys() != expected:
        raise ValueError("Incomplete benchmark report")
    print("scenario,workers,runs,sql_before,sql_after,locks_before,locks_after,p95_before_ms,p95_after_ms,p95_change_pct,requests_per_sec_before,requests_per_sec_after")
    for key in sorted(expected):
        a, b = before[key], after[key]
        signature = lambda rows: sorted((r["repeat"], r["requests"], r["successful_requests"], r["committed_payments"], r["expected_rejections"]) for r in rows)
        if signature(a) != signature(b):
            raise ValueError(f"Workload mismatch for {key}")
        med = lambda rows, column: statistics.median(float(r[column]) for r in rows)
        ap, bp = med(a, "p95_ms"), med(b, "p95_ms")
        values = [med(a, "sql_per_request"), med(b, "sql_per_request"),
                  med(a, "locking_sql_per_request"), med(b, "locking_sql_per_request"),
                  ap, bp, (bp / ap - 1) * 100,
                  med(a, "requests_per_sec"), med(b, "requests_per_sec")]
        print(f"{key[0]},{key[1]},{len(a)}," + ",".join(f"{v:.3f}" for v in values))


if __name__ == "__main__":
    main()
