#!/usr/bin/env python3
"""Replace British spellings with American ones, in identifiers and prose alike.

    python3 tools/m6.6b-spelling.py            # report, writes nothing
    python3 tools/m6.6b-spelling.py --show     # report and print every line that would change
    python3 tools/m6.6b-spelling.py --apply    # rewrite the files

M6.6a left these alone on purpose, because the identifiers are British too:
`centreX`, `resizeAroundCentre`, `ITEM_COLOUR`, `gesture.cancelled()`. Prose and
code have to move together or the comment stops naming the thing beside it.

WHY THIS IS NOT A SED SCRIPT

A blind substring replacement of "centre" also hits
`skippingTheAscentRestoresEverything`, where "As-centRe-stores" spans a camel
boundary. `axes` contains "axe", `onActivityResult` contains "tyre", and
`programmer` contains "programme". So this splits every letter run at camelCase
and underscore boundaries first and matches whole words only. `AscentRestores`
becomes `Ascent` + `Restores` and neither is in the map.

That has a cost worth knowing: a compound the map does not list is missed rather
than half-converted. `centimetres` is one lowercase word, not `centi` + `metres`,
so it needs its own entry. Every compound in this repo is listed below; a new one
needs adding. The `--report` count going to zero is what proves the list complete
for the corpus as it stands, which is why the reporter uses the same wide nets
that built the map rather than the map itself.

WHAT IS PROTECTED

Third-party text, because repunctuating somebody's license misquotes it: the SIL
OFL body, and anything under docs/original/. Plus tools/m6.6b-spelling.allow,
for the lines in CLAUDE.md and MANUAL.md that record this pass and quote its
own examples. Without that file a second run would Americanise the examples and
the documentation would claim a change it no longer shows.

And this file, because MAP below is a list of British words. A sweep that ran
over its own map would rewrite every key into the value beside it, after which
the map says color -> color and the tool is a no-op that reports success. M6.6a
shipped exactly that bug one level down: the en dash rule held a literal en dash,
the sweep turned it into a hyphen, and the rule then matched every hyphen in the
repo. A pattern must not be written in the thing it hunts. Where the thing is a
single character an escape solves it; where it is forty English words, the file
excludes itself and says so here.

For the same reason tools/m6.6-scan.py no longer keeps its own copy of the list
and imports MAP from here instead. One list, two readers, and only one file that
the sweep has to look away from.

WHAT IS NOT PROTECTED, AND WAS CHECKED BY HAND FIRST

No JNI symbol contains a British spelling, so no rename can break the linker's
name-based resolution. No file name does either, so nothing needs a git mv. One
string literal does, `"no such colour block: "` in FaceStrip, and it is an
exception message rather than anything the user reads, so no displayed text
changes and parity is untouched. tools/golden/original-*.js hold blocks copied
verbatim out of the original app; the only British word inside one is a comment
of ours, and the copied expressions have none.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SKIP_PREFIX = ("docs/original/", "target/", "assets/")
SKIP_EXT = {".png", ".jpg", ".jpeg", ".ttf", ".jar", ".keystore", ".apk"}

# See "WHAT IS PROTECTED" above: this file is a list of the words it removes, so
# it must not be swept. The allow file is exempt for the same reason.
SKIP_EXACT = {"tools/m6.6b-spelling.py", "tools/m6.6b-spelling.allow"}

# Line ranges of third-party text, 1-based and inclusive of the start. Lines 1-11
# of OFL.txt are our own note about why two notices share one file; the SIL
# license itself starts at 12 and is not ours to edit.
FROZEN_FROM = {"src/main/resources/com/modcritic/invmgr/ui/fonts/OFL.txt": 12}

MAP = {
    # -our
    "colour": "color", "colours": "colors", "coloured": "colored",
    "recolours": "recolors",
    "behaviour": "behavior", "behaviours": "behaviors",
    "favour": "favor", "favours": "favors",
    "flavours": "flavors",
    "honour": "honor", "honours": "honors", "honoured": "honored",
    "honouring": "honoring",
    "neighbour": "neighbor", "neighbours": "neighbors",
    "neighbouring": "neighboring",
    "endeavour": "endeavor",
    # -re
    "centre": "center", "centres": "centers", "centred": "centered",
    "centring": "centering", "recentre": "recenter",
    "metre": "meter", "metres": "meters",
    "centimetre": "centimeter", "centimetres": "centimeters",
    "millimetre": "millimeter", "millimetres": "millimeters",
    # -ce
    "defence": "defense",
    "licence": "license", "licences": "licenses",
    "practise": "practice",
    # -ise
    "initialise": "initialize", "initialised": "initialized",
    "initialises": "initializes", "initialising": "initializing",
    "initialiser": "initializer",
    "optimise": "optimize", "optimised": "optimized",
    "optimises": "optimizes", "optimising": "optimizing",
    "optimisation": "optimization", "optimisations": "optimizations",
    "normalise": "normalize", "normalised": "normalized",
    "normalises": "normalizes", "normalising": "normalizing",
    "normalisation": "normalization",
    "rasterise": "rasterize", "rasterised": "rasterized",
    "rasteriser": "rasterizer",
    "recognise": "recognize", "recognised": "recognized",
    "recognises": "recognizes", "recogniser": "recognizer",
    "serialise": "serialize", "serialised": "serialized",
    "maximise": "maximize", "maximised": "maximized",
    "maximises": "maximizes", "maximising": "maximizing",
    "minimise": "minimize", "minimised": "minimized",
    "minimising": "minimizing",
    "localised": "localized",
    "organised": "organized",
    "summarise": "summarize",
    "synchronised": "synchronized",
    "synthesised": "synthesized", "synthesising": "synthesizing",
    "randomise": "randomize", "randomises": "randomizes",
    "randomising": "randomizing",
    "sanitises": "sanitizes",
    "quantises": "quantizes",
    "generalising": "generalizing",
    "americanise": "americanize",
    # Our own coinages, formed from the British suffix.
    "ellipsise": "ellipsize", "ellipsises": "ellipsizes",
    "ellipsising": "ellipsizing",
    "iconise": "iconize", "iconised": "iconized",
    # -yse
    "analyse": "analyze", "analysed": "analyzed",
    # doubled consonant
    "cancelled": "canceled", "cancelling": "canceling",
    "labelled": "labeled", "labelling": "labeling",
    # "unlabelled" is not reached by the "labelled" entry, which is whole-word. Two perf probes
    # spelled their default picture label this way while two others spelled it "unlabeled", and
    # the string becomes a directory name under target/pictures/. Found by M6.7c, 2026-09-19.
    "unlabelled": "unlabeled",
    "relabelling": "relabeling",
    "modelled": "modeled",
    "travelled": "traveled", "travelling": "traveling",
    "levelled": "leveled",
    "totalling": "totaling",
    "marshalling": "marshaling",
    # the rest
    "grey": "gray", "greys": "grays", "greyed": "grayed",
    "artefact": "artifact", "artefacts": "artifacts",
    "catalogue": "catalog",
    "judgement": "judgment",
    "misspelt": "misspelled",
    "anticlockwise": "counterclockwise",
    "programme": "program", "programmes": "programs",
    # Not a suffix swap like everything above it. British treats the short form of
    # "mathematics" as a mass noun and American treats it as a singular, so the "s"
    # is the whole difference. There is no rule to generalize; it is one word.
    "maths": "math",
}

# The nets that built MAP, kept so the reporter can find a British word the map
# has never heard of. A word matching a net but absent from MAP is reported as
# unmapped rather than silently passed over. The exempt set is everything a net
# catches that is already American.
NETS = [
    re.compile(r"our(s|ed|ing|ful|less|able)?$"),
    re.compile(r"is(e|ed|es|ing|er|ers|ation|ations)$"),
    re.compile(r"ys(e|ed|es|ing|er)$"),
    re.compile(r"(tre|tres|tred|tring)$"),
    re.compile(r"(bre|bres|cre|cres)$"),
    re.compile(r"ll(ed|ing|er|ers|ous)$"),
    re.compile(r"ogue(s)?$"),
    re.compile(r"grey"),
    re.compile(r"^(whilst|amongst|orientated|fulfil|enrol|skilful|wilful|"
               r"storey|storeys|kerb|mould|moulds|smoulder|plough|draught|"
               r"aluminium|sulphur|ageing|artefact|artefacts|judgement|"
               r"judgements|acknowledgement|acknowledgements|speciality|"
               r"instalment|misspelt|spelt|learnt|dreamt|maths|programme|programmes|"
               r"practise|practised|practising|pyjamas|gaol|cheque|cheques|"
               r"manoeuvre|counsellor|marvellous|jeweller|anticlockwise|"
               r"catalogue|dialogue|analogue|monologue|epilogue)$"),
]

# Words a net catches that are American already, or are not English at all.
# Anything not here and not in MAP shows up as "unmapped" and needs a decision.
#
# Two of these are MAP's OWN OUTPUT: "misspelt" converts to "misspelled" and
# "anticlockwise" to "counterclockwise", and both results end in a net's suffix, so
# the reporter flagged the words this tool had just written. A new MAP entry whose
# American side trips a net needs a line here in the same change, or the pass nags
# forever about its own work.
EXEMPT = {
    "our", "ours", "four", "your", "yours", "hour", "hours", "detour",
    "favourite",  # never appears; kept so a future hit lands in MAP not here
    "advertise", "arises", "arising", "clockwise", "coerced", "compelling",
    "counterclockwise", "disguise", "enterprise", "exercise", "exercised",
    "exercises",
    "heightwise", "likewise", "noise", "otherwise", "precise", "premise",
    "promise", "promised", "promises", "promising", "raise", "raised",
    "raises", "raising", "revised", "rise", "rises", "rising", "surprise",
    "surprised", "surprises", "surprising", "wise", "wiser",
    # "stalling" is "stall" plus a suffix and trips the -lling net that catches
    # "labelling" and "modelling". Added at M6.7b, whose LatencyClockTest stalls the
    # app on purpose and named the field after what it does. "refilling" is the same
    # shape, from the virtualized item list's refill().
    "stalling", "refilling",
    "absence", "coincidence", "confidence", "consequence", "consequences",
    "dependence", "difference", "differences", "divergence", "divergences",
    "evidence", "existence", "fence", "hence", "independence", "inference",
    "negligence", "occurrence", "occurrences", "persistence", "precedence",
    "preference", "preferences", "presence", "reference", "references",
    "sentence", "sentences", "sequence", "silence",
    "called", "caller", "callers", "calling", "controlled", "culled", "culling",
    # Added at M6.7c: "hand-rolled" and "uncalled" both trip the -lled net and are
    # American already. They came in with that milestone's own write-up.
    "rolled", "uncalled",
    "dwelling", "falling", "filled", "filling", "installed", "installer",
    "installing", "killed", "killing", "polled", "polling", "profileinstaller",
    "pulled", "pulling", "refilled", "scrolled", "scroller", "scrolling",
    "smaller", "spilling", "stalled", "taller", "telling", "unfilled",
    "uninstalled", "willing",
    "aesthetic", "daemon", "docstring", "echoes", "does", "doesn", "goes",
    "jstring", "misspelled", "shoes", "shoehorned", "spelled", "spelling", "string",
    "substring", "undoes", "whatsoever", "whoever", "zeroes",
    "eyeballed", "eyeballing", "marshaling", "greyscale",
    # American English keeps "dialogue" for a conversation and spells the window
    # "dialog", which is what this repo already calls Dialogs and ModalDialog. So
    # there is nothing to convert and a blanket rule would break the other sense.
    "dialogue", "dialogues",
}

# A letter run, split at camelCase and SHOUT_CASE boundaries. The first branch
# keeps an all-caps run whole unless a lowercase letter follows it, so
# ITEM_COLOUR splits on the underscore while HTMLParser gives HTML + Parser.
WORD = re.compile(r"[A-Z]+(?![a-z])|[A-Z][a-z]+|[a-z]+")


def allowances():
    """Exact text this pass must leave British, one entry per line.

    Same shape as tools/m6.6-quotes.allow: <path>::<the exact text>. Used for the
    rows in CLAUDE.md and MANUAL.md that document this pass by quoting the words
    it removed.
    """
    out = {}
    f = ROOT / "tools" / "m6.6b-spelling.allow"
    if not f.exists():
        return out
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "::" not in line:
            continue
        rel, _, text = line.partition("::")
        out.setdefault(rel.strip(), []).append(text)
    return out


ALLOW = allowances()


def recase(british, american):
    """Carry the source word's case onto the replacement."""
    if british.islower():
        return american
    if british.isupper():
        return american.upper()
    if british[0].isupper() and british[1:].islower():
        return american[0].upper() + american[1:]
    return None  # mIxEd; caller reports it rather than guessing


def convert(rel, text):
    """Return (new_text, {word: count}, [odd-case words])."""
    counts, odd = {}, []
    # Blank the allowed text out with a placeholder of the same length, so
    # offsets stay put and the words inside it are invisible to the pass.
    holes = []
    for i, quoted in enumerate(ALLOW.get(rel, ())):
        token = "\x00ALLOW%dZ\x00" % i
        if quoted in text:
            text = text.replace(quoted, token)
            holes.append((token, quoted))

    first = FROZEN_FROM.get(rel)
    if first:
        lines = text.split("\n")
        head, tail = "\n".join(lines[:first - 1]), "\n".join(lines[first - 1:])
    else:
        head, tail = text, None

    def sub(m):
        w = m.group(0)
        american = MAP.get(w.lower())
        if not american:
            return w
        fixed = recase(w, american)
        if fixed is None:
            odd.append(w)
            return w
        counts[w] = counts.get(w, 0) + 1
        return fixed

    head = WORD.sub(sub, head)
    out = head if tail is None else head + "\n" + tail
    for token, quoted in holes:
        out = out.replace(token, quoted)
    return out, counts, odd


def unmapped(text):
    """British-looking words the map has no entry for."""
    out = {}
    for w in WORD.findall(text):
        lw = w.lower()
        if lw in MAP or lw in EXEMPT:
            continue
        if any(n.search(lw) for n in NETS):
            out[lw] = out.get(lw, 0) + 1
    return out


def files():
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files"],
                         capture_output=True, text=True, check=True).stdout
    for rel in out.splitlines():
        if rel.startswith(SKIP_PREFIX) or Path(rel).suffix.lower() in SKIP_EXT:
            continue
        if rel in SKIP_EXACT:
            continue
        yield rel


def check_args(argv):
    """Refuse anything that is not a known flag, and refuse paths outright.

    ⚠ THIS SCRIPT CHOOSES ITS OWN FILES, and naming some looks like it would narrow
    that. files() walks the repo; a path on the command line was read by nothing at
    all. On 2026-09-09 it was run as `--check <two paths>`, meaning to check those two:
    --check is not a flag, both paths were dropped, and the whole repo was reported on
    instead. Nothing said so, because an unrecognized argument simply was not looked at.

    With --apply that stops being a reporting mistake. Someone naming three files to
    keep the blast radius small would rewrite all 195 instead, and the tool would print
    its usual summary. So an unknown argument is fatal here rather than ignored, and
    the message says the file list is not negotiable rather than just listing flags.
    """
    known = {"--apply", "--show", "-h", "--help"}
    bad = [a for a in argv if a not in known]
    if bad:
        joined = ", ".join(repr(a) for a in bad)
        print(f"m6.6b-spelling.py: unrecognized argument(s): {joined}", file=sys.stderr)
        print("", file=sys.stderr)
        print("This script walks the whole repo and chooses its own files.", file=sys.stderr)
        print("Naming paths does NOT narrow it; they were silently dropped before", file=sys.stderr)
        print("2026-09-10, which with --apply meant rewriting everything.", file=sys.stderr)
        print("", file=sys.stderr)
        print("    python3 tools/m6.6b-spelling.py            # report, writes nothing",
              file=sys.stderr)
        print("    python3 tools/m6.6b-spelling.py --show     # report plus every changed line",
              file=sys.stderr)
        print("    python3 tools/m6.6b-spelling.py --apply    # rewrite the files",
              file=sys.stderr)
        sys.exit(2)
    if {"-h", "--help"} & set(argv):
        print(__doc__.strip())
        sys.exit(0)


def main():
    check_args(sys.argv[1:])
    apply = "--apply" in sys.argv
    show = "--show" in sys.argv
    per_file, per_word, odd_all, unknown = {}, {}, {}, {}
    changed_lines = []

    for rel in files():
        p = ROOT / rel
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        new, counts, odd = convert(rel, text)
        for w, n in unmapped(text).items():
            unknown.setdefault(w, set()).add(rel)
        if odd:
            odd_all[rel] = odd
        if not counts:
            continue
        per_file[rel] = sum(counts.values())
        for w, n in counts.items():
            per_word[w] = per_word.get(w, 0) + n
        if show:
            for i, (a, b) in enumerate(zip(text.split("\n"), new.split("\n")), 1):
                if a != b:
                    changed_lines.append(f"{rel}:{i}\n  -{a.strip()[:150]}\n  +{b.strip()[:150]}")
        if apply:
            p.write_text(new, encoding="utf-8")

    verb = "changed" if apply else "would change"
    print(f"{sum(per_file.values())} words {verb} across {len(per_file)} files\n")
    print("--- by word ---")
    for w, n in sorted(per_word.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"{n:5d}  {w} -> {recase(w, MAP[w.lower()])}")
    print("\n--- by file ---")
    for rel, n in sorted(per_file.items(), key=lambda kv: -kv[1]):
        print(f"{n:5d}  {rel}")
    if odd_all:
        print("\n--- mIxEd case, left alone, decide by hand ---")
        for rel, ws in odd_all.items():
            print(f"  {rel}: {', '.join(ws)}")
    if unknown:
        print("\n--- British-looking and unmapped: add to MAP or to EXEMPT ---")
        for w, rels in sorted(unknown.items()):
            print(f"  {w}  ({len(rels)} files, e.g. {sorted(rels)[0]})")
    if show and changed_lines:
        print("\n--- every changed line ---")
        for line in changed_lines:
            print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
