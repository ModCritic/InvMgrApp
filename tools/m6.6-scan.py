#!/usr/bin/env python3
"""Find files whose comment and doc prose breaks the unslop-me style.

Two passes over two different slices of each file.

The PROSE pass reads comment and doc prose only. Code, identifiers and string
literals are skipped, because a rule about how a sentence is written has nothing
to say about an identifier.

The CHARACTER pass reads the WHOLE file, string literals, fenced blocks, backtick
spans and all. Added 2026-09-11, because the prose pass reported zero across 204
files while ten em dashes sat in CLAUDE.md and docs/claude/BUILD-AND-VERIFY.md,
every one of them inside a fenced example, and fourteen more sat in the app's own
log prefix. Both were invisible by construction, and the user found them by eye.
A comment inside a fenced example is still text somebody reads and copies, and a
string literal is text the app shows to somebody. The rule is that this project
writes no em dash anywhere; the only survivors are somebody else's words in a file
belonging to something we use, which go in tools/m6.6-quotes.allow.

Signals are split in two. A HARD signal is unambiguous under the style (an em
dash, a killed phrase, a participial closer). A SOFT signal is a word the style
warns about that this project may legitimately use: "substrate" is a real Gluon
dependency here, "vector" and "surface" are 3D terms, "primitive" is a Java one.
A file is noncompliant on one hard hit, or on three soft hits.
"""

import ast
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path("/home/asteroid/ClaudeWorkspace/InvMgrApp")

# The original HTML app is third-party reference material; the golden PNGs and
# target/ are build output.
EXCLUDE_PREFIX = ("docs/original/", "target/", "assets/")

# The sweep's own bookkeeping quotes the patterns it hunts for, by name and in full,
# so scanning it reports its own examples as violations.
EXCLUDE_EXACT = {
    "docs/M6.6-UNSLOP-TRACKER.md",
    "docs/M6.6-SWEEP-PLAN.md",
    "tools/m6.6-scan.py",
    "tools/m6.6-verify.py",
}
PROSE_EXT = {".java", ".md", ".sh", ".c", ".h", ".js", ".xml", ".py", ".css",
             ".txt", ".html"}

# The character pass reads every tracked text file whatever its suffix, so it needs
# its own exemption list rather than PROSE_EXT's.
#
# Only one file is on it, and it cannot be anywhere else: m6.6-quotes.allow lists the
# exact text each exemption covers, so it has to hold the character to match it. The
# sweep's other bookkeeping files are NOT exempt here even though EXCLUDE_EXACT spares
# them the prose rules: they quote the PHRASES they hunt, which is a prose problem, and
# they spell the dash as an escape, which is what everything else should do too. Keeping
# them in this pass is how a literal one typed back into this scanner gets caught.
CHAR_EXCLUDE_EXACT = {"tools/m6.6-quotes.allow"}

# Prose this project writes that git ls-files cannot see, because it lives outside the
# repository. The no-dash rule is "anywhere", and these were invisible to it: 89 em dashes
# sat in the community names and 19 in the label script's comments, with 14 more in the
# guide, until 2026-09-11.
#
# Absolute on purpose. ROOT / <absolute path> returns the absolute path unchanged, so the
# reader at the bottom of this file needs no special case.
#
# InvMgr-graph-report.md is deliberately NOT here. It is generated from labels.json on
# every commit, so fixing the source fixes it, and listing it would report violations that
# cannot be fixed where they appear.
EXTRA_FILES = (
    "/home/asteroid/ClaudeWorkspace/InvMgr-graph-guide.md",
    "/home/asteroid/.graphify/invmgr/apply-labels.py",
    "/home/asteroid/.graphify/invmgr/labels.json",
)


def tracked_files():
    """Files the prose rules read: a known text suffix, minus the sweep's own notes."""
    for rel in _extended():
        if rel in EXCLUDE_EXACT:
            continue
        if Path(rel).suffix.lower() in PROSE_EXT:
            yield rel


def tracked_char_files():
    """Files the character rules read: everything tracked that decodes as text."""
    for rel in _extended():
        if rel not in CHAR_EXCLUDE_EXACT:
            yield rel


def _extended():
    """Everything tracked, plus the out-of-repo files EXTRA_FILES names."""
    yield from _tracked()
    for path in EXTRA_FILES:
        if Path(path).exists():
            yield path


def _tracked():
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files"],
                         capture_output=True, text=True, check=True).stdout
    for rel in out.splitlines():
        if rel.startswith(EXCLUDE_PREFIX):
            continue
        yield rel


BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE_SLASH = re.compile(r"(?<![:\w])//[^\n]*")
HASH = re.compile(r"(?<!\$)#[^\n]*")
SGML = re.compile(r"<!--.*?-->", re.S)
FENCE = re.compile(r"```.*?```", re.S)
INLINE_CODE = re.compile(r"`[^`\n]*`")


def quote_exceptions():
    """Verbatim quotations whose punctuation is the source's, not ours."""
    out = {}
    f = ROOT / "tools" / "m6.6-quotes.allow"
    if not f.exists():
        return out
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "::" not in line:
            continue
        rel, _, quoted = line.partition("::")
        out.setdefault(rel.strip(), []).append(quoted)
    return out


QUOTED = quote_exceptions()


def whole_of(rel, text):
    """The whole file with quoted exemptions blanked, for the character rules.

    Nothing else is stripped. A fenced block, a backtick span, a string literal and an
    identifier are all in scope, which is the difference between this and prose_of().
    """
    for q in QUOTED.get(rel, ()):
        text = text.replace(q, " ")
    return text


def prose_of(rel, text):
    """Return only the human-written prose in a file."""
    for q in QUOTED.get(rel, ()):
        text = text.replace(q, " ")
    # A dash alone in a markdown table cell is a "none here" placeholder in a column
    # of values, the way "n/a" is. It joins no clause and is not the tell.
    if Path(rel).suffix.lower() == ".md":
        text = re.sub("\\|\\s*[\u2014\u2013]\\s*(?=\\||\\w)", "| ", text)
    ext = Path(rel).suffix.lower()
    if ext in {".md", ".txt"}:
        text = FENCE.sub(" ", text)
        return INLINE_CODE.sub(" ", text)
    if ext in {".java", ".c", ".h", ".js", ".css"}:
        parts = BLOCK.findall(text) + LINE_SLASH.findall(text)
        return "\n".join(parts)
    if ext == ".py":
        # A docstring is prose a person reads, so it counts. Reading only "#" lines
        # missed twelve em dashes in device_suite.py's 23 docstrings, which is how
        # this was found: lane B reported them as out of scope and was right about
        # the rule, which meant the rule was wrong.
        lines = [ln for ln in text.splitlines() if not ln.startswith("#!")]
        out = HASH.findall("\n".join(lines))
        try:
            tree = ast.parse(text)
        except SyntaxError:
            return "\n".join(out)
        for node in ast.walk(tree):
            if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef,
                                 ast.ClassDef)):
                doc = ast.get_docstring(node)
                if doc:
                    out.append(doc)
        return "\n".join(out)
    if ext == ".sh":
        lines = [ln for ln in text.splitlines() if not ln.startswith("#!")]
        return "\n".join(HASH.findall("\n".join(lines)))
    if ext in {".xml", ".html"}:
        return "\n".join(SGML.findall(text))
    return ""


def words(*ws):
    return re.compile(r"\b(" + "|".join(ws) + r")\b", re.I)


# Read against the WHOLE file, never against prose_of(). See the module docstring.
CHARACTER = {
    "em dash": re.compile("\u2014"),
    # Flat again. Every en dash in this repo became a hyphen on 2026-09-09, by the
    # user's decision: nobody types one, no keyboard has a key for it, and a range
    # written with a hyphen reads the same. So any en dash now is a reintroduction.
    #
    # Spelled as an escape, not as the character. This pattern held a literal en dash
    # until the sweep that removed them ran over this file too and turned it into a
    # hyphen, at which point the rule matched every hyphen in the repo and reported
    # 20,175 violations. A pattern that hunts a character must not contain it.
    "en dash": re.compile("\u2013"),
}

HARD = {
    "killed phrase": re.compile(
        r"(it is important to note|at its core|a testament to|"
        r"when it comes to|it'?s worth noting|in today'?s world)", re.I),
    "swap word": words("utilize", "utilizes", "utilizing", "facilitate",
                       "facilitates", "numerous", "in order to",
                       "due to the fact that"),
    "negative parallelism": re.compile(
        r"(not just \w+,? but|isn'?t just|it'?s not (about|that) .{2,40}"
        r",? it'?s)", re.I),
    "participial closer": re.compile(
        r",\s+(ensuring|highlighting|allowing|enabling|providing|making it|"
        r"underscoring|showcasing|demonstrating|leveraging)\b", re.I),
    "analogy opener": re.compile(
        r"(think of it (like|as)|it'?s basically|imagine a\b)", re.I),
    "fancy is": words("serves as", "stands as", "boasts"),
    # British spellings used to be a rule here and are now M6.6b's, in
    # tools/m6.6b-spelling.py. Two reasons, and the second is the real one.
    #
    # This scanner reads comment prose only, so it could see "the box centre" and
    # never see `centreX` on the line below it. Half the British spellings in this
    # repo were identifiers, and a rule that structurally cannot look at them
    # reports a file clean while the code beside the comment still reads British.
    #
    # And a list of British words kept HERE is a list the sweep would rewrite into
    # American ones, leaving a rule that matches nothing. m6.6b-spelling.py holds
    # the list, excludes itself from its own pass, and matches whole words after
    # splitting camelCase, so it catches both halves of the problem.
    "reflex transition": words("moreover", "furthermore", "additionally"),
}

SOFT = {
    "vocabulary": words(
        "delve", "underscore", "showcase", "intricate", "pivotal",
        "meticulous", "realm", "tapestry", "testament", "foster", "garner",
        "bolster", "boast", "leverage", "robust", "seamless", "seamlessly",
        "comprehensive", "crucial", "nuanced", "multifaceted", "holistic",
        "embark", "unlock", "elevate", "resonate", "compelling", "enhance",
        "enhances", "interplay", "landscape", "cutting-edge", "game-changer"),
    "jargon noun": words(
        "substrate", "wedge", "locus", "vantage", "nexus", "bedrock",
        "scaffolding", "modality", "paradigm", "ratchet", "evacuate",
        "endgame", "north star", "flywheel"),
    "zombie noun": re.compile(r"\bthe (implementation|utilization|"
                              r"realization|creation|application) of\b", re.I),
}


def scan():
    rows = []
    prose_set = set(tracked_files())
    char_set = set(tracked_char_files())
    for rel in sorted(prose_set | char_set):
        try:
            text = (ROOT / rel).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        hard, soft, samples = {}, {}, []
        wc = 0
        if rel in char_set:
            whole = whole_of(rel, text)
            for name, rx in CHARACTER.items():
                hits = rx.findall(whole)
                if hits:
                    hard[name] = len(hits)
                    samples.append(f"{name}: {len(hits)} in the file as a whole")
        if rel not in prose_set:
            rows.append({
                "file": rel, "prose_words": 0, "hard": hard, "soft": soft,
                "hard_n": sum(hard.values()), "soft_n": 0,
                "noncompliant": bool(hard), "samples": samples[:4],
            })
            continue
        prose = prose_of(rel, text)
        wc = len(prose.split())
        for name, rx in HARD.items():
            hits = rx.findall(prose)
            if hits:
                hard[name] = len(hits)
                samples.append(f"{name}: {hits[0] if isinstance(hits[0], str) else hits[0][0]!r}")
        for name, rx in SOFT.items():
            hits = rx.findall(prose)
            if hits:
                soft[name] = len(hits)
        hard_n = sum(hard.values())
        soft_n = sum(soft.values())
        rows.append({
            "file": rel, "prose_words": wc,
            "hard": hard, "soft": soft,
            "hard_n": hard_n, "soft_n": soft_n,
            "noncompliant": bool(hard_n) or soft_n >= 3,
            "samples": samples[:4],
        })
    return rows


def parse_args(argv):
    """Read the command line, or explain what is wrong with it and exit.

    ⚠ THE ONLY ARGUMENT IS WHERE THE REPORT GOES, AND IT USED TO BE POSITIONAL.
    This script picks its own files (scan() walks the repo), so it takes no inputs,
    but `m6.6-scan.py somefile` reads exactly like every other tool on this machine
    and used to mean "write the report over somefile". On 2026-09-09 it was called
    as `m6.6-scan.py tools/golden/verify-goldens.sh tools/golden/original-loadstate.js`,
    meaning to scan those two. It flattened the first, ignored the second, printed its
    usual success line, and the wreckage was committed a minute later by `git add -A`.

    So the output path now needs --out, a bare path is refused, and an unknown flag is
    refused. Refusing the extra argument is the half that matters: nothing was ever
    read past argv[1], so naming two files could not produce an error, and the moment
    an error would have caught this was the moment it was silently dropped.

    Parsed before scan() runs, so a bad command line costs no wait.
    """
    out, rest = Path("scan.json"), []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--out":
            if i + 1 >= len(argv):
                rest.append("--out needs a path after it")
                break
            out = Path(argv[i + 1])
            i += 2
            continue
        if a.startswith("--out="):
            out = Path(a[len("--out="):])
        elif a in ("-h", "--help"):
            print(__doc__.strip())
            print("\n    python3 tools/m6.6-scan.py [--out PATH]\n")
            print("Scans the whole repo and works out its own file list.")
            print("It takes no input files. --out only says where the JSON report goes.")
            sys.exit(0)
        elif a.startswith("-"):
            rest.append(f"unknown option {a!r}")
        else:
            rest.append(f"unexpected argument {a!r}")
        i += 1

    if rest:
        print("m6.6-scan.py: " + "; ".join(rest), file=sys.stderr)
        print("", file=sys.stderr)
        print("This script scans the whole repo and chooses its own files.", file=sys.stderr)
        print("It takes NO input files. To report on one file, run it and read that", file=sys.stderr)
        print("file's row out of the JSON.", file=sys.stderr)
        print("", file=sys.stderr)
        print("    python3 tools/m6.6-scan.py [--out PATH]", file=sys.stderr)
        sys.exit(2)
    return out


if __name__ == "__main__":
    out = parse_args(sys.argv[1:])
    rows = scan()
    out.write_text(json.dumps(rows, indent=1))
    bad = [r for r in rows if r["noncompliant"]]
    print(f"scanned {len(rows)} files, {len(bad)} noncompliant")
    print(f"total prose words: {sum(r['prose_words'] for r in rows):,}")
    cats = {}
    for r in bad:
        for k, v in list(r["hard"].items()) + list(r["soft"].items()):
            cats[k] = cats.get(k, 0) + v
    for k, v in sorted(cats.items(), key=lambda kv: -kv[1]):
        print(f"  {v:5d}  {k}")
