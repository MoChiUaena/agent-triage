#!/usr/bin/env python3
"""Replay both Linux native trim receipts from one owned HTTP workflow."""
import argparse
from pathlib import Path

from resource_report import MIB, verify_dataset


def render(directory, source_commit, seconds, workflow_run_id):
    if type(workflow_run_id) is not int or workflow_run_id <= 0:
        raise ValueError("Expected an exact positive workflow run ID")
    summaries = list(directory.rglob("summary.json"))
    if len(summaries) != 2:
        raise ValueError("Expected both Linux HTTP modes from one workflow")
    datasets = [verify_dataset(path, source_commit, seconds) for path in summaries]
    modes = {meta["component"] for meta, _ in datasets}
    if modes != {"http-enabled", "http-disabled"} or any(
            meta["operatingSystem"] != "linux" or "nativeHeapTrim" not in meta or
            meta["workflowRunId"] != workflow_run_id for meta, _ in datasets):
        raise ValueError("Expected both Linux HTTP native trim receipts from the specified run")
    lines = ["| Starter | RSS before → after MiB | Anonymous before → after MiB | PSS before → after MiB | NMT non-heap before → after MiB |",
        "|---|---:|---:|---:|---:|"]
    for meta, _ in sorted(datasets, key=lambda dataset: dataset[0]["component"]):
        before, after = meta["_nativeTrimRows"]
        pair = lambda key: f"{before[key] / MIB:.2f} → {after[key] / MIB:.2f}"
        lines.append(f"| {meta['component'].removeprefix('http-')} | {pair('rssBytes')} | {pair('anonymousBytes')} | "
            f"{pair('pssBytes')} | {pair('nativeNonHeapCommittedBytes')} |")
    lines.append("\nThe trim sample follows the original closed sample. It is outside the resource growth gate; "
        "a reduction alone cannot prove allocation source or absence of a leak.")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--seconds", type=int, required=True)
    parser.add_argument("--workflow-run-id", type=int, required=True)
    args = parser.parse_args()
    try:
        print(render(args.directory, args.source_commit, args.seconds, args.workflow_run_id))
    except (ValueError, KeyError, OSError, TypeError) as error:
        parser.exit(1, "Native trim replay rejected: " + str(error) + "\n")


if __name__ == "__main__":
    main()
