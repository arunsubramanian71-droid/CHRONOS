#!/usr/bin/env python3
"""Analyses the filled-in result sheets of docs/HUMAN_STUDIES.md and prints the numbers the report template needs.

    python3 tools/study_analyse.py nfr15     docs/study-results/nfr15.csv
    python3 tools/study_analyse.py blind     docs/study-results/blind.csv
    python3 tools/study_analyse.py unplanned docs/study-results/unplanned.csv
    (add --allow-selftest only for the tooling self-test: rows whose participant column starts with SELFTEST are otherwise refused)

It computes nothing the sheets do not contain, never fills a missing cell, and states the sample size next to every figure. With zero rows it
says that no study has been run.
"""
import csv
import statistics
import sys


def load(path, allow_selftest):
    with open(path, newline="", encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if any((v or "").strip() for v in r.values())]
    fake = [r for r in rows if any((v or "").upper().startswith("SELFTEST") for v in r.values())]
    if fake and not allow_selftest:
        sys.exit("refused: %d row(s) are marked SELFTEST; they are not study data" % len(fake))
    return rows, bool(fake)


def banner(selftest):
    if selftest:
        print("SELF-TEST ROWS: this output is not a study result.\n")


def nfr15(rows):
    print("Study A (NFR-15, 'a new team member can run the flagship demo from the README: under 10 minutes, three commands')")
    print("participants: %d" % len(rows))
    if not rows:
        print("No session has been recorded: NFR-15 is NOT VERIFIED.")
        return
    ok = 0
    for r in rows:
        secs, cmds = float(r["elapsed_seconds"]), int(r["commands_typed"])
        passed = secs < 600 and cmds <= 3 and r["demo_output_read_out"].strip().lower() in ("yes", "y")
        ok += passed
        print("  %-12s %5.0f s  %d commands  demo read out: %-3s  -> %s" % (r["participant"], secs, cmds, r["demo_output_read_out"], "PASS" if passed else "FAIL"))
    print("passed %d of %d. One participant can show the requirement is reachable, not a rate; say n next to every claim." % (ok, len(rows)))


def blind(rows):
    print("Study B (blind debugging, Section 25)")
    print("sessions: %d, debuggers: %d" % (len(rows), len({r["debugger"] for r in rows})))
    if not rows:
        print("No session has been recorded: the blind protocol is NOT RUN.")
        return
    correct = [r for r in rows if r["answer_correct"].strip().lower() == "yes"]
    mins = [float(r["minutes_to_answer"]) for r in rows if r["minutes_to_answer"].strip()]
    print("correct diagnoses: %d of %d" % (len(correct), len(rows)))
    if mins:
        print("minutes to answer: median %.1f, min %.1f, max %.1f (n=%d)" % (statistics.median(mins), min(mins), max(mins), len(mins)))
    by = {}
    for r in rows:
        by.setdefault(r["case_kind"], []).append(r["answer_correct"].strip().lower() == "yes")
    for k in sorted(by):
        print("  case kind %s: %d of %d correct" % (k, sum(by[k]), len(by[k])))
    conf = [r["what_was_confusing"].strip() for r in rows if r["what_was_confusing"].strip()]
    print("things reported as confusing (%d):" % len(conf))
    for c in conf:
        print("  - " + c)


def unplanned(rows):
    print("Study B part 2 (failures nobody planned, diagnosed by hand and with the tool, answers compared)")
    print("diagnoses: %d" % len(rows))
    if not rows:
        print("No session has been recorded: this part is NOT RUN.")
        return
    for method in ("tool", "hand"):
        m = [r for r in rows if r["method"].strip().lower() == method]
        agree = sum(r["matches_reference"].strip().lower() == "yes" for r in m)
        mins = [float(r["minutes"]) for r in m if r["minutes"].strip()]
        print("  %-4s: %d diagnoses, %d match the reference%s" % (method, len(m), agree, (", median %.1f min" % statistics.median(mins)) if mins else ""))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--allow-selftest"]
    if len(args) != 2 or args[0] not in ("nfr15", "blind", "unplanned"):
        sys.exit(__doc__)
    data, selftest = load(args[1], "--allow-selftest" in sys.argv)
    banner(selftest)
    {"nfr15": nfr15, "blind": blind, "unplanned": unplanned}[args[0]](data)
