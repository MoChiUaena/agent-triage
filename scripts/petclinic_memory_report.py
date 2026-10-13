#!/usr/bin/env python3
"""Replay numeric Petclinic memory profiles against the corresponding cost receipts."""
import argparse
import hashlib
import json
from pathlib import Path

from petclinic_memory_profile import names, verify_bundle
from petclinic_multi_cost_report import verify as verify_cost
from prepare_petclinic_rest import REVISION


def profile_sha(folder, starter_enabled):
    matches = list(folder.rglob(names(starter_enabled)[1]))
    if len(matches) != 1:
        raise ValueError("Expected exactly one numeric memory profile summary per phase")
    return hashlib.sha256(matches[0].read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--seconds", type=int, choices=(60, 300, 600), required=True)
    parser.add_argument("--trust-current-run", action="store_true",
                        help="Use only for files produced by this workflow run")
    parser.add_argument("--enabled-cost-sha256")
    parser.add_argument("--control-cost-sha256")
    parser.add_argument("--enabled-profile-sha256")
    parser.add_argument("--control-profile-sha256")
    args = parser.parse_args()
    provided = (args.enabled_cost_sha256, args.control_cost_sha256,
                args.enabled_profile_sha256, args.control_profile_sha256)
    if args.trust_current_run == any(value is not None for value in provided) \
            or not args.trust_current_run and any(value is None for value in provided):
        parser.error("Use current-run trust or all four independently recorded SHA-256 digests")

    enabled_cost = verify_cost(args.directory, args.source_commit, args.seconds,
        expected_summary_sha=args.enabled_cost_sha256, trust_current_run=args.trust_current_run)
    control_cost = verify_cost(args.directory, args.source_commit, args.seconds,
        expected_summary_sha=args.control_cost_sha256, trust_current_run=args.trust_current_run, control=True)
    enabled_profile = verify_bundle(args.directory, args.source_commit, REVISION, args.seconds,
        enabled_cost["summarySha256"], args.enabled_profile_sha256 if not args.trust_current_run
        else profile_sha(args.directory, True), True)
    control_profile = verify_bundle(args.directory, args.source_commit, REVISION, args.seconds,
        control_cost["summarySha256"], args.control_profile_sha256 if not args.trust_current_run
        else profile_sha(args.directory, False), False)
    print(json.dumps({"starterEnabled": enabled_profile, "starterDisabled": control_profile,
        "costSummarySha256": {"enabled": enabled_cost["summarySha256"],
                              "disabled": control_cost["summarySha256"]},
        "order": "enabled-then-disabled"}, sort_keys=True))


if __name__ == "__main__":
    main()
