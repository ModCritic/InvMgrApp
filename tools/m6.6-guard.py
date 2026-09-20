#!/usr/bin/env python3
"""Report writing-style breaks in the commit just made.

Usage: python3 tools/m6.6-guard.py [<commit>]

M6.6 took about 2,800 em dashes out of 166 files' comments. The corpus is what
teaches the habit to whoever writes the next section, and that includes Claude,
which had the writing rules loaded and matched the surrounding prose anyway. So
without something that notices, this comes back: M6.5 alone added 526 in six days.

It reports and never blocks. A post-commit hook cannot fail a commit that has
already landed, and blocking is the wrong shape anyway: a dash inside a quotation
or a sample name is legitimate, and the answer is a line in
tools/m6.6-quotes.allow rather than a fight with a hook.

Three checks, over three different slices of each changed file. The prose rules read
comments and doc prose. The CHARACTER rules (em dash, en dash) read the whole file,
because the ten dashes the user found on 2026-09-10 were all inside fenced blocks and
the fourteen after them were inside string literals, and a prose-only rule cannot see
either. The spelling check reads the whole file too.

British spellings are checked too, since M6.6b. They come from
m6.6b-spelling.py rather than from the prose scanner, because that tool splits
camelCase and so sees `centreX` as well as "the box centre" in the comment above
it. The prose scanner reads comments only and would miss every identifier.
"""

import importlib.util
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


scan = _load("scan", "m6.6-scan.py")
spelling = _load("spelling", "m6.6b-spelling.py")


def changed(rev):
    out = subprocess.run(
        ["git", "-C", str(ROOT), "diff-tree", "--no-commit-id", "--name-only",
         "-r", rev],
        capture_output=True, text=True)
    return [f for f in out.stdout.split() if (ROOT / f).exists()]


def main():
    # EMPTY, and it should stay that way. CLAUDE.md and the nine files it was split
    # into were the last exemption; M6.6c cleared them on 2026-09-10 and the scanner
    # now reports zero hard hits across every tracked file. A name added back here is
    # a file that has stopped being guarded, so add one only with a milestone behind
    # it and a date to remove it by.
    quiet_prose = set()
    rev = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    findings = []
    for rel in changed(rev):
        if rel.startswith(scan.EXCLUDE_PREFIX) or rel in scan.EXCLUDE_EXACT:
            continue
        # The spelling tool is a list of the words it removes; see its docstring.
        if rel in spelling.SKIP_EXACT:
            continue
        try:
            text = (ROOT / rel).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        hits = {}
        # Whole file: a fenced example and a string literal both count.
        if rel not in scan.CHAR_EXCLUDE_EXACT:
            whole = scan.whole_of(rel, text)
            for name, rx in scan.CHARACTER.items():
                n = len(rx.findall(whole))
                if n:
                    hits[name] = n
        if Path(rel).suffix.lower() in scan.PROSE_EXT and rel not in quiet_prose:
            prose = scan.prose_of(rel, text)
            for name, rx in scan.HARD.items():
                n = len(rx.findall(prose))
                if n:
                    hits[name] = n
        # Whole file here as well: an identifier counts.
        try:
            _, brit, _ = spelling.convert(rel, text)
        except (UnicodeDecodeError, OSError):
            brit = {}
        if brit:
            hits["British spelling"] = sum(brit.values())
        if hits:
            findings.append((rel, hits))

    if not findings:
        return 0
    print("\nM6.6 guard: this commit breaks the writing style.")
    for rel, hits in findings:
        print("  " + rel + "  " + ", ".join(f"{k} x{n}" for k, n in hits.items()))
    print("Fix it, or exempt the text if it is not the comment's own prose: a quotation\n"
          "or sample data goes in tools/m6.6-quotes.allow, and a British spelling being\n"
          "shown as an example goes in tools/m6.6b-spelling.allow.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
