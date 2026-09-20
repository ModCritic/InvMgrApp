#!/usr/bin/env python3
"""Work out which lanes run which set next, and print the round's arguments.

Usage: python3 tools/m6.6-next-round.py

⚠ THE M6.6 SWEEP IS FINISHED. docs/M6.6-UNSLOP-TRACKER.md has no unticked rows left, so
every run now prints "Nothing left unticked" and stops. Kept, not deleted, because this
and m6.6-verify.py are how a sweep round is run here, and the next one will want them
rather than a fresh invention. It becomes useful again the moment somebody adds unticked
rows to the tracker. (Checked by M6.7c, 2026-09-19.)

The rule that matters is the alignment one. Taking each lane's lowest unticked set
independently is what a round looks like when the lanes are level, but when they are
not it keeps them apart forever: a lane that got ahead stays ahead, every round is
mixed, and every gate call needs two specs. So a lane only runs when it is AT the
lowest set anybody is on. A lane that is ahead sits out until the others catch it up,
which takes exactly one round, and then all three are on the same set again.

Lane C got ahead on 2026-09-06, when the weekly usage ran out and only lane C could be
sent. Four rounds after that I was still saying the lanes would come back in step and
still selecting them the way that guaranteed they would not.

Prints the gate spec and the Workflow args. It starts nothing.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRACKER = ROOT / "docs" / "M6.6-UNSLOP-TRACKER.md"
MARK = {"A": "✅", "B": "✔", "C": "☑"}

SET_HEAD = re.compile(r"^## Lane ([ABC]), set (\d+)")
UNTICKED = re.compile(r"^\|\s*\[ \]\s*\|\s*`([^`]+)`")


def unticked_sets():
    """{(lane, set): [files still to do]}, skipping sets that are fully ticked."""
    out, cur = {}, None
    for line in TRACKER.read_text(encoding="utf-8").splitlines():
        head = SET_HEAD.match(line)
        if head:
            cur = (head.group(1), int(head.group(2)))
            continue
        row = UNTICKED.match(line)
        if row and cur:
            out.setdefault(cur, []).append(row.group(1))
    return out


def main():
    sets = unticked_sets()
    if not sets:
        print("Nothing left unticked. The sweep is done.")
        return 0

    lowest = {}
    for (lane, n) in sets:
        if lane not in lowest or n < lowest[lane]:
            lowest[lane] = n

    target = min(lowest.values())
    running = sorted(l for l, n in lowest.items() if n == target)
    waiting = sorted(l for l, n in lowest.items() if n != target)

    lanes = [{"lane": l, "mark": MARK[l], "files": sets[(l, target)]} for l in running]
    args = {"round": f"{target}{''.join(running)}", "lanes": lanes}

    print(f"set {target}, lanes {', '.join(running)}")
    for entry in lanes:
        print(f"\nlane {entry['lane']}  {entry['mark']}  ({len(entry['files'])} files)")
        for f in entry["files"]:
            print("   ", f)
    if waiting:
        for l in waiting:
            print(f"\nlane {l} sits this one out; it is already on set {lowest[l]}."
                  f" After this round every lane is on set {target + 1}.")
    print(f"\ngate: python3 tools/m6.6-verify.py {target}{''.join(running)}")
    print("\nargs:")
    print(json.dumps(args, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
