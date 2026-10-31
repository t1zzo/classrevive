"""Independently validate benchmark evidence; no target code is imported or run."""
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
report = json.loads((root / "benchmark.json").read_text(encoding="utf-8"))
assert report["schema_version"] == 1
assert report["behavior"] == "NOT_VERIFIED"
assert report["attempted"] == len(report["rows"]) > 0
assert report["structurally_complete"] == sum(row["status"] == "STRUCTURALLY_COMPLETE" for row in report["rows"])
assert (root / "benchmark.md").is_file()
hashes = {}
for row in report["rows"]:
    if row["case"] in hashes:
        assert hashes[row["case"]] == row["input_sha256"]
    hashes[row["case"]] = row["input_sha256"]
for engine in ("cfr", "vineflower", "procyon", "jadx"):
    assert (engine in report["variants"]) == (engine not in report["not_measured"])
for variant, summary in report["variants"].items():
    rows = [row for row in report["rows"] if row["variant"] == variant]
    assert len(rows) == summary["attempted"] == report["samples"]
    assert summary["recompiled"] == sum(row.get("verification", {}).get("status") == "COMPILED" for row in rows)
    assert summary["structurally_complete"] == sum(row["status"] == "STRUCTURALLY_COMPLETE" for row in rows)
for row in report["rows"]:
    assert row["total_elapsed_ms"] >= 0
    if "report" in row:
        child_path = (root / row["report"]).resolve()
        assert child_path.is_relative_to(root.resolve())
        child = json.loads(child_path.read_text(encoding="utf-8"))
        assert child["input_sha256"] == row["input_sha256"]
        assert child["behavior"] == "NOT_VERIFIED"
        assert child["verification"] == row["verification"]
progress = [json.loads(line) for line in (root / "progress.jsonl").read_text(encoding="utf-8").splitlines()]
assert progress == report["rows"]
print(f"Validated {report['attempted']} benchmark rows and linked evidence reports.")
