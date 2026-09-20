#!/usr/bin/env python3
"""
The one place M6.7b works out what a run of numbers means.

WHY IT IS ALONE
===============

Three lanes measure the app: TestFX on Xvfb, TestFX on the Plasma desktop, and the phone over
adb. All three write the same raw samples and NONE of them works out a percentile, because two
implementations of a percentile is how a before-and-after comparison quietly ends up comparing two
different questions.

THE OUTLIER RULE, WHICH IS THE WHOLE POINT
==========================================

The user's instruction, 2026-09-17, in their own words: "An outlier in timing taking considerably
longer than the majority should not just average down and be forgotten, as it could be a lagspike
that may occur again if not rectified."

So nothing here leads with a mean. A sample is called an OUTLIER when it is BOTH:

  * at least twice the run's own median, and
  * at least one 60 Hz frame (16.7 ms) above that median.

Both halves are needed, and each one alone is wrong. Twice the median alone flags a 0.4 ms sample
against a 0.2 ms median, which no hand can feel. A fixed threshold alone is the mistake M6.7a
already made and corrected in FlightClock: a machine sitting exactly on its vsync lands half its
frames a hair above any fixed line, so "51% over budget" described a laptop that was running
perfectly. A threshold has to be built out of what THIS machine did, not out of a number the app
picked.

The plain worst sample and the 99th percentile are printed whatever the rule says, so a spike the
rule misses is still on the page.

RUNNING IT
==========

    python3 tools/perf/perfstat.py report baseline
    python3 tools/perf/perfstat.py compare baseline after-caching
    python3 tools/perf/perfstat.py compare baseline after-caching --platform phone
"""

import argparse
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
# ⚠ NOT under target/. A baseline is compared against a later build, and target/ is the directory
# `mvn clean` exists to delete; putting them there cost this milestone every run it had taken.
PERF = os.path.join(REPO, "perf-runs")

# One frame at 60 Hz. Used ONLY as "how much slower does a spike have to be before a hand notices",
# never as a pass mark; see the module docstring.
FRAME_MS = 1000.0 / 60.0

# Screens outermost, then the felt arm before the work arm, because that is the reading order:
# what a hand feels first, then what the app was actually doing underneath it.
PLATFORM_ORDER = ["xvfb-felt", "xvfb-work", "plasma-felt", "plasma-work",
                  "phone-felt", "phone-work"]


# --------------------------------------------------------------------------- reading runs

def load(label, platform=None):
    """Every scenario file written under one label, as {platform: {scenario: [samples]}}."""
    root = os.path.join(PERF, label)
    if not os.path.isdir(root):
        sys.exit("no run called %r under %s" % (label, PERF))
    found = {}
    for plat in sorted(os.listdir(root)):
        if platform and plat != platform:
            continue
        here = os.path.join(root, plat)
        if not os.path.isdir(here):
            continue
        for name in sorted(os.listdir(here)):
            if not name.endswith(".json"):
                continue
            with open(os.path.join(here, name)) as f:
                blob = json.load(f)
            found.setdefault(plat, {})[blob["scenario"]] = blob
    if not found:
        sys.exit("nothing to read under %s" % root)
    return found


def totals(blob):
    """
    The `t` column of every sample worth counting.

    ⚠ ON THE PHONE A FINGER ARRIVES TWICE, as a touch event and as a synthesized mouse event
    (CLAUDE.md §5.8 item 5). Both are genuinely delivered and both are recorded, but counting both
    would make every phone median a blend of a finger and its shadow, and would double the sample
    count for no extra information. So when a file has any touch samples at all, only those count.
    A desktop file has none and is unaffected.
    """
    samples = blob["samples"]
    touches = [s for s in samples if s["type"].startswith("touch-")]
    return [s["t"] for s in (touches or samples)]


# --------------------------------------------------------------------------- the statistics

def percentile(sorted_values, fraction):
    """Nearest-rank, which needs no interpolation and cannot invent a value nothing measured."""
    if not sorted_values:
        return float("nan")
    rank = max(1, math.ceil(fraction * len(sorted_values)))
    return sorted_values[min(rank, len(sorted_values)) - 1]


def summarize(values):
    """Everything said about one scenario on one platform. No mean in the headline."""
    if not values:
        return None
    ordered = sorted(values)
    n = len(ordered)
    median = percentile(ordered, 0.5)
    threshold = max(2.0 * median, median + FRAME_MS)
    outliers = [v for v in ordered if v >= threshold]
    return {
        "n": n,
        "min": ordered[0],
        "median": median,
        "mean": sum(ordered) / n,
        "p90": percentile(ordered, 0.90),
        "p99": percentile(ordered, 0.99),
        "max": ordered[-1],
        "threshold": threshold,
        "outliers": outliers,
        "outlier_n": len(outliers),
        "outlier_pct": 100.0 * len(outliers) / n,
        "worst_outlier": outliers[-1] if outliers else 0.0,
    }


def where_are_the_outliers(blob):
    """Which repetition each outlier landed in. A spike at rep 0 is warm-up; one at rep 7 is not."""
    values = totals(blob)
    if not values:
        return []
    ordered = sorted(values)
    threshold = max(2.0 * percentile(ordered, 0.5), percentile(ordered, 0.5) + FRAME_MS)
    counted = {id(s) for s in blob["samples"]
               if s["type"].startswith("touch-")} or None
    return [(s["rep"], s["seq"], s["type"], s["t"]) for s in blob["samples"]
            if s["t"] >= threshold and (counted is None or id(s) in counted)]


# --------------------------------------------------------------------------- printing

def markdown_row(cells):
    return "| " + " | ".join(cells) + " |"


def report(label, platform=None, show_outliers=True):
    runs = load(label, platform)
    for plat in sorted(runs, key=lambda p: PLATFORM_ORDER.index(p)
                       if p in PLATFORM_ORDER else 99):
        print()
        print("### %s, on %s" % (label, plat))
        print()
        print(markdown_row(["scenario", "n", "median", "p90", "p99", "worst",
                            "outliers", "worst outlier"]))
        print(markdown_row(["---", "---:", "---:", "---:", "---:", "---:", "---:", "---:"]))
        for scenario in sorted(runs[plat]):
            blob = runs[plat][scenario]
            stat = summarize(totals(blob))
            if stat is None:
                print(markdown_row([scenario, "0", "no samples", "", "", "", "", ""]))
                continue
            print(markdown_row([
                scenario,
                str(stat["n"]),
                "%.1f" % stat["median"],
                "%.1f" % stat["p90"],
                "%.1f" % stat["p99"],
                "%.1f" % stat["max"],
                "%d (%.0f%%)" % (stat["outlier_n"], stat["outlier_pct"]),
                "%.1f" % stat["worst_outlier"] if stat["outlier_n"] else "-",
            ]))
        print()
        print("*Milliseconds from the input event to the frame showing its result. "
              "An outlier is a sample at least twice the median AND at least 16.7 ms above it.*")
        if show_outliers:
            spikes = []
            for scenario in sorted(runs[plat]):
                for rep, seq, kind, value in where_are_the_outliers(runs[plat][scenario]):
                    spikes.append((value, scenario, rep, seq, kind))
            if spikes:
                spikes.sort(reverse=True)
                print()
                print("**The spikes themselves, worst first:**")
                print()
                print(markdown_row(["ms", "scenario", "rep", "seq", "event"]))
                print(markdown_row(["---:", "---", "---:", "---:", "---"]))
                for value, scenario, rep, seq, kind in spikes[:20]:
                    print(markdown_row(["%.1f" % value, scenario, str(rep), str(seq), kind]))
                if len(spikes) > 20:
                    print()
                    print("*%d spikes in all; the 20 worst are listed.*" % len(spikes))


def improvement(before, after):
    """Percent faster. Positive is better, and a zero baseline reports nothing rather than inf."""
    if before == 0:
        return None
    return 100.0 * (before - after) / before


def pct(value):
    if value is None:
        return "-"
    return "%+.1f%%" % value


def compare(before_label, after_label, platform=None):
    before = load(before_label, platform)
    after = load(after_label, platform)
    platforms = sorted(set(before) & set(after),
                       key=lambda p: PLATFORM_ORDER.index(p) if p in PLATFORM_ORDER else 99)
    missing = (set(before) | set(after)) - set(platforms)
    for plat in platforms:
        print()
        print("### %s -> %s, on %s" % (before_label, after_label, plat))
        print()
        print(markdown_row(["scenario", "n", "median before", "median after", "median",
                            "p90", "worst before", "worst after", "spikes before",
                            "spikes after"]))
        print(markdown_row(["---", "---:", "---:", "---:", "---:", "---:", "---:", "---:",
                            "---:", "---:"]))
        for scenario in sorted(set(before[plat]) & set(after[plat])):
            was = summarize(totals(before[plat][scenario]))
            now = summarize(totals(after[plat][scenario]))
            if was is None or now is None:
                continue
            print(markdown_row([
                scenario,
                "%d" % min(was["n"], now["n"]),
                "%.1f" % was["median"],
                "%.1f" % now["median"],
                pct(improvement(was["median"], now["median"])),
                pct(improvement(was["p90"], now["p90"])),
                "%.1f" % was["max"],
                "%.1f" % now["max"],
                "%d" % was["outlier_n"],
                "%d" % now["outlier_n"],
            ]))
        print()
        print("*Percentages are how much FASTER the second run was; a minus sign is a "
              "regression. Milliseconds elsewhere.*")
        print()
        print("*⚠ The two worst columns are raw milliseconds and NOT a percentage, deliberately. "
              "A maximum is one sample, so a percentage of it says more about which single frame "
              "was unlucky than about the change: an earlier version of this table reported a "
              "283% regression on a scenario whose worst went from 1.3 ms to 5.0. Read the worst "
              "columns where n is large or where the spike count moved.*")
        only_before = sorted(set(before[plat]) - set(after[plat]))
        only_after = sorted(set(after[plat]) - set(before[plat]))
        if only_before:
            print()
            print("⚠ Only in %s: %s" % (before_label, ", ".join(only_before)))
        if only_after:
            print()
            print("⚠ Only in %s: %s" % (after_label, ", ".join(only_after)))
    if missing:
        print()
        print("⚠ Not compared, because only one run has it: %s" % ", ".join(sorted(missing)))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="what", required=True)

    one = sub.add_parser("report", help="one run, as a table per platform")
    one.add_argument("label")
    one.add_argument("--platform")
    one.add_argument("--no-spikes", action="store_true")

    two = sub.add_parser("compare", help="two runs, with the percentage between them")
    two.add_argument("before")
    two.add_argument("after")
    two.add_argument("--platform")

    args = parser.parse_args()
    if args.what == "report":
        report(args.label, args.platform, not args.no_spikes)
    else:
        compare(args.before, args.after, args.platform)


if __name__ == "__main__":
    main()
