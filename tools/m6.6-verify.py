#!/usr/bin/env python3
"""Gate one round of the M6.6 unslopping sweep.

Usage: python3 tools/m6.6-verify.py <spec> [<spec> ...]

⚠ THE M6.6 SWEEP IS FINISHED and there is no round left to gate. Kept for the same reason
as m6.6-next-round.py, which see. Note this one has never had an automated caller: the
other script prints its command line for a human to run. (Checked by M6.7c, 2026-09-19.)

A spec is a set number, optionally followed by the lanes that ran it: "4" is set 4 in
all three lanes, "4AB" is set 4 in lanes A and B only. Several specs are one round, so
a round where lanes A and B ran set 4 while lane C ran set 5 is "4AB 5C". Passing them
separately would report each half's files as unexpected edits in the other.

Reads the round's three sets out of the tracker, then checks the five things that can
go wrong: a row left unticked or ticked by the wrong lane, a file edited that was not
in the round, a violation still standing, prose that grew, and a block comment whose
delimiters no longer balance.

Before and after are both measured with the scanner's own prose extractor, one
against `git show HEAD:<file>` and one against the working tree, so the two counts
are comparable. That means the round must be verified BEFORE it is committed.
"""

import importlib.util
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRACKER = ROOT / "docs" / "M6.6-UNSLOP-TRACKER.md"
MARKS = {"A": "✅", "B": "✔", "C": "☑"}

spec = importlib.util.spec_from_file_location("scan", ROOT / "tools" / "m6.6-scan.py")
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)

SET_HEAD = re.compile(r"^## Lane ([ABC]), set (\d+)\b")
ROW = re.compile(r"^\|\s*(\[ \]|\S+)\s*\|\s*`([^`]+)`\s*\|")


def still_queued():
    """Files with an unticked row SOMEWHERE, so more of them is still to come.

    A file can appear twice. device_suite.py's "#" comments were swept in set 6 and
    its docstrings queued for a later set, because the scanner only learned to read
    docstrings afterwards. Without this, widening the scanner retroactively fails
    every round that had already passed under the narrower rule.
    """
    out = set()
    for line in TRACKER.read_text(encoding="utf-8").splitlines():
        m = ROW.match(line)
        if m and m.group(1) == "[ ]":
            out.add(m.group(2))
    return out


def sets_for(round_no):
    """Return {lane: [(mark_cell, path), ...]} for one round."""
    out, cur = {}, None
    for line in TRACKER.read_text(encoding="utf-8").splitlines():
        m = SET_HEAD.match(line)
        if m:
            cur = (m.group(1), int(m.group(2)))
            continue
        r = ROW.match(line)
        if r and cur and cur[1] == round_no:
            out.setdefault(cur[0], []).append((r.group(1), r.group(2)))
    return out


def git_show(rel):
    p = subprocess.run(["git", "-C", str(ROOT), "show", f"HEAD:{rel}"],
                       capture_output=True, text=True)
    return p.stdout if p.returncode == 0 else None


# Not a sentence end: an escaped dot in a comment that is talking about a regex, and
# a decimal point. Both showed up in a real round and moved the mean on their own.
SENT = re.compile(r"(?<!\\)(?<!\d)[.!?](?:\s|$)")


def sentences(prose):
    parts = [s.strip() for s in SENT.split(prose) if s.strip()]
    return parts


CODE_EXT = {".java", ".c", ".h", ".js", ".css"}
BLOCK_RX = re.compile(r"/\*.*?\*/", re.S)
LINE_RX = re.compile(r"(?<![:\w])//[^\n]*")


def code_only(text):
    """The file with every comment blanked, so two versions compare on code alone."""
    return re.sub(r"\s+", " ", LINE_RX.sub(" ", BLOCK_RX.sub(" ", text))).strip()


def balanced(rel, text):
    """A .java/.c/.js block comment that lost its close swallows the file."""
    if Path(rel).suffix not in {".java", ".c", ".h", ".js", ".css"}:
        return True
    return text.count("/*") == text.count("*/")


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return 2
    lanes, labels = {}, []
    for spec in sys.argv[1:]:
        m = re.fullmatch(r"(\d+)([ABCabc]*)", spec)
        if not m:
            print(f"FAIL  cannot read spec {spec!r}")
            return 2
        n, want = int(m.group(1)), set(m.group(2).upper())
        part = sets_for(n)
        if want:
            part = {k: v for k, v in part.items() if k in want}
        if not part:
            print(f"FAIL  no sets found for {spec}")
            return 1
        for k, v in part.items():
            lanes.setdefault(k, []).extend(v)
        labels.append(f"set {n} lanes {''.join(sorted(part))}")
    expected = {p for rows in lanes.values() for _, p in rows}
    queued = still_queued()
    fails, notes = [], []

    # 1. ticks
    for lane, rows in sorted(lanes.items()):
        for cell, path in rows:
            if cell == "[ ]":
                fails.append(f"unticked   {lane}  {path}")
            elif MARKS[lane] not in cell:
                fails.append(f"wrong mark {lane} expected {MARKS[lane]}: {path} has {cell}")

    # 2. the diff names nothing extra
    changed = subprocess.run(["git", "-C", str(ROOT), "diff", "--name-only"],
                             capture_output=True, text=True).stdout.split()
    stray = [c for c in changed
             if c not in expected and not c.endswith("M6.6-UNSLOP-TRACKER.md")]
    for s in stray:
        fails.append(f"unexpected edit  {s}")
    for e in sorted(expected):
        if e not in changed:
            notes.append(f"no diff at all   {e}  (already clean, or not touched)")

    # 3, 4, 5. violations, growth, delimiters
    for rel in sorted(expected):
        now = (ROOT / rel).read_text(encoding="utf-8")
        before = git_show(rel)
        if not balanced(rel, now):
            fails.append(f"unbalanced /* */ {rel}")
        prose_now = scan.prose_of(rel, now)
        hard = {k: len(rx.findall(prose_now)) for k, rx in scan.HARD.items()}
        # This used to drop "British spelling", which M6.6a's agents were told to leave
        # alone. The rule is no longer in scan.HARD at all: M6.6b owns spellings, it
        # covers identifiers as well as prose, and its list lives in m6.6b-spelling.py.
        # There is nothing left to filter out here.
        hard = {k: v for k, v in hard.items() if v}
        if hard and rel in queued:
            notes.append(f"more to come     {rel}  {hard}  (queued in a later set)")
            hard = {}
        if hard:
            fails.append(f"still violating  {rel}  {hard}")
        if before is None:
            notes.append(f"new file         {rel}")
            continue
        if Path(rel).suffix in CODE_EXT and code_only(before) != code_only(now):
            fails.append(f"code changed     {rel}  (this sweep rewrites comments only)")
        before_len, now_len = len(scan.prose_of(rel, before)), len(prose_now)
        s_before = sentences(scan.prose_of(rel, before))
        s_now = sentences(prose_now)
        # A real split adds a sentence WITHOUT removing text. Segmenting technical
        # comments is genuinely hard (1.5, D-13., §5.5, e.g., an escaped dot), so a
        # count that rises while the prose gets shorter is the counter changing its
        # mind, not the prose changing. Three rounds running, that was the false
        # positive; length is the check that has never lied.
        if len(s_now) > len(s_before) and now_len >= before_len:
            fails.append(f"sentences rose   {rel}  {len(s_before)} -> {len(s_now)}"
                         f"  and the prose did not shrink")
        # Total prose length, not the mean. A rewrite that merges two fragments into
        # one sentence shortens the file and raises the mean at the same time, so the
        # mean reports a growth that did not happen. SPEC-3D-VIEW.md did exactly that:
        # 22 characters shorter, one sentence fewer, mean up 1.2.
        if now_len > before_len:
            fails.append(f"prose grew       {rel}  {before_len} -> {now_len} chars")

    print(f"{'; '.join(labels)}: {len(expected)} files")
    for n in notes:
        print("  note  " + n)
    for f in fails:
        print("  FAIL  " + f)
    print("PASS" if not fails else f"{len(fails)} FAILURES")
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
