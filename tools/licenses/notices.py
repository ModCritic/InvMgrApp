#!/usr/bin/env python3
"""Writes the files behind the app's Licenses page, from notices.toml.

    python3 tools/licenses/notices.py            write them
    python3 tools/licenses/notices.py --check    exit 1 if the committed ones are out of date

The page lives in the Menu window, under About. For each platform it reads an index, a plain
text file with one line per thing on the page, and opens the license texts the index points at.
This script writes both from notices.toml: the index from the sentences there, and each text
copied byte for byte from the third-party file it names, so a license on the page is the
license as its authors wrote it.

The output is committed, so building the app needs no Python and none of the archives the texts
come from. The archives are needed here: {sources} is the folder that holds the third-party
source archives a release attaches (--sources changes it).

Index lines are tab-separated, the kind first:
    para    a paragraph               note    a paragraph in the dimmer gray
    credit  a required credit         source  the label of the link to the source
    text    title, resource, used-by sentence
    group   title, introduction       item    name, resource (belongs to the group above it)
"""
import argparse
import re
import sys
import tarfile
import tomllib
from pathlib import Path

from inputs import PLACES

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
RESOURCES = REPO / "src/main/resources"
PACKAGE = "com/modcritic/invmgr/ui/licenses"
SOURCES = Path.home() / "ClaudeWorkspace/InvMgr-release-1.0.1"
HEADER = "# Written by tools/licenses/notices.py from tools/licenses/notices.toml. Edit those and run it.\n"


def expand(spec, sources):
    spec = spec.replace("{sources}", str(sources))
    for key, value in PLACES.items():
        spec = spec.replace("{" + key + "}", value)
    return spec


class Reader:
    """Reads source files, opening each archive once however many members are asked of it."""

    def __init__(self, sources):
        self.sources = sources
        self.wanted = {}
        self.read = {}

    def want(self, spec):
        spec = expand(spec, self.sources)
        archive, _, member = spec.partition("!")
        self.wanted.setdefault(archive, set())
        if member:
            self.wanted[archive].add(member)
        return spec

    def load(self):
        for archive, members in self.wanted.items():
            path = Path(archive)
            if not path.exists():
                raise SystemExit(f"notices: {archive} is missing; it is where a license text comes from")
            if not members:
                self.read[archive] = path.read_bytes()
                continue
            with tarfile.open(path) as tar:
                for info in tar:
                    if info.name in members:
                        self.read[f"{archive}!{info.name}"] = tar.extractfile(info).read()
            for member in members - {k.partition("!")[2] for k in self.read if k.startswith(archive + "!")}:
                raise SystemExit(f"notices: {member} is not in {archive}")

    def __getitem__(self, spec):
        return self.read[spec]


def cut(text, lic):
    """The part of a source file a license entry asks for, or the whole file."""
    if "from_line" in lic:
        lines = text.splitlines(keepends=True)
        start = next((i for i, line in enumerate(lines) if line.strip() == lic["from_line"]), None)
        end = next((i for i, line in enumerate(lines) if start is not None and i > start
                    and line.strip() == lic["through_line"]), None)
        if start is None or end is None:
            raise SystemExit(f"notices: {lic['id']}: {lic['from_line']!r} to {lic['through_line']!r} not found")
        return "".join(lines[start:end + 1])
    if "blocks" in lic:
        found = [b.strip("\n") for b in re.split(r"(?m)^-{20,}[ \t]*$", text)]
        picked = []
        for start in lic["blocks"]:
            block = next((b for b in found if b.lstrip("\n").startswith(start) and lic["block_contains"] in b), None)
            if block is None:
                raise SystemExit(f"notices: {lic['id']}: no block starting {start.splitlines()[0]!r}")
            picked.append(block.strip("\n") + "\n")
        return "\n\n".join(picked)
    return text


def item_source(lic, item):
    return lic["source"].replace("{module}", item["module"]).replace("{legal_file}", item["legal_file"])


def field(value):
    """A sentence for the index, which must stay on its one line."""
    if "\t" in value or "\n" in value:
        raise SystemExit(f"notices: a sentence holds a line break or a tab: {value!r}")
    return value


def build(sources):
    """Every file this script owns, as {path relative to the resources folder: bytes}."""
    spec = tomllib.loads((HERE / "notices.toml").read_text(encoding="utf-8"))
    licenses = {lic["id"]: lic for lic in spec["license"]}
    reader = Reader(sources)
    for lic in licenses.values():
        if "items" in lic:
            for item in lic["items"]:
                reader.want(item_source(lic, item))
        elif "source" in lic:
            reader.want(lic["source"])
    reader.load()

    out = {}
    texts = {}
    for lic in licenses.values():
        if "items" in lic:
            for item in lic["items"]:
                src = expand(item_source(lic, item), sources)
                name = f"{PACKAGE}/texts/jdk/{Path(item['legal_file']).stem}.txt"
                data = reader[src]
                if out.get(name, data) != data:
                    raise SystemExit(f"notices: two different files would both be {name}")
                out[name] = data
                item["resource"] = "/" + name
        elif "resource" in lic:
            if not (RESOURCES / lic["resource"].lstrip("/")).is_file():
                raise SystemExit(f"notices: {lic['id']} points at {lic['resource']}, which is not in the app's resources")
        else:
            name = f"{PACKAGE}/texts/{lic['file']}"
            out[name] = cut(reader[expand(lic["source"], sources)].decode("utf-8"), lic).encode("utf-8")
            lic["resource"] = "/" + name
        texts[lic["id"]] = lic

    for platform, page in spec["platform"].items():
        lines = [HEADER, f"para\t{field(page['intro'])}\n", f"para\t{field(page['gpl'])}\n",
                 f"source\t{field(page['source'])}\n", f"note\t{field(page['offer'])}\n"]
        lines += [f"credit\t{field(c)}\n" for c in page["credits"]]
        for lic_id in page["licenses"]:
            lic = texts[lic_id]
            if "items" in lic:
                lines.append(f"group\t{field(lic['title'])}\t{field(lic['group'])}\n")
                lines += [f"item\t{field(i['name'])}\t{i['resource']}\n" for i in lic["items"]]
            else:
                used_by = lic.get("used_by", {}).get(platform)
                if not used_by:
                    raise SystemExit(f"notices: {lic_id} is listed for {platform} and says nothing about what uses it there")
                lines.append(f"text\t{field(lic['title'])}\t{lic['resource']}\t{field(used_by)}\n")
        out[f"{PACKAGE}/{platform}.txt"] = "".join(lines).encode("utf-8")
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="compare with the committed files and write nothing")
    parser.add_argument("--sources", type=Path, default=SOURCES, help="the folder of third-party source archives")
    args = parser.parse_args()

    wanted = build(args.sources)
    folder = RESOURCES / PACKAGE
    present = {str(p.relative_to(RESOURCES)) for p in folder.rglob("*") if p.is_file()} if folder.exists() else set()
    if args.check:
        stale = [n for n, data in wanted.items() if not (RESOURCES / n).is_file() or (RESOURCES / n).read_bytes() != data]
        extra = sorted(present - set(wanted))
        for n in stale:
            print(f"out of date: {n}")
        for n in extra:
            print(f"not written by notices.py: {n}")
        if stale or extra:
            print("Run python3 tools/licenses/notices.py and commit what it writes.")
            return 1
        print(f"The Licenses page's {len(wanted)} files are current.")
        return 0
    for n in sorted(present - set(wanted)):
        (RESOURCES / n).unlink()
    for n, data in wanted.items():
        (RESOURCES / n).parent.mkdir(parents=True, exist_ok=True)
        (RESOURCES / n).write_bytes(data)
    print(f"Wrote {len(wanted)} files under src/main/resources/{PACKAGE}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
