"""Proving where every byte of a linked native library came from, using the linker's own map.

libsubstrate.so, the Android app's one big native library, is linked by this build from our own
C, the Java that native-image compiled ahead of time, and static libraries from three SDKs,
plus files the compiler driver adds on its own. No file in the APK says which. The linker
does: given -Wl,-Map=<file> it writes a map naming the input file, and the member inside an
archive, of every section it placed. Rerunning a link with that flag gives byte-identical
output (checked 2026-10-29), so a map made for a build describes that build exactly.

The shipped copy is the linked one with symbols stripped by the Android build, so the two are
compared on their loaded sections, the bytes the phone actually maps into memory.
"""
import fnmatch
import os
import re
import struct
from pathlib import Path

from inputs import expand
from inventory import read_entry

SHF_ALLOC = 0x2
SHT_NOBITS = 8


def sections(data):
    """{name: (flags, type, bytes or None)} for a 64-bit little-endian ELF file."""
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    headers = []
    for i in range(shnum):
        name, kind, flags, _, offset, size = struct.unpack_from("<IIQQQQ", data, shoff + i * shentsize)
        headers.append((name, kind, flags, offset, size))
    strtab = headers[shstrndx]
    names = data[strtab[3]:strtab[3] + strtab[4]]
    found = {}
    for name, kind, flags, offset, size in headers[1:]:
        label = names[name:names.index(b"\0", name)].decode()
        found[label] = (flags, kind, None if kind == SHT_NOBITS else data[offset:offset + size], size)
    return found


def loaded_mismatch(shipped, linked):
    """None when every loaded section of the two libraries is identical, else a reason."""
    a, b = sections(shipped), sections(linked)
    loaded_a = {n for n, (f, *_) in a.items() if f & SHF_ALLOC}
    loaded_b = {n for n, (f, *_) in b.items() if f & SHF_ALLOC}
    if loaded_a != loaded_b:
        return f"loaded sections differ: {sorted(loaded_a ^ loaded_b)}"
    for name in sorted(loaded_a):
        if a[name][2:] != b[name][2:]:
            return f"section {name} differs"
    return None


MAP_LINE = re.compile(r"^\s*([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)\s+(\d+) (\s*)(.*)$")


def map_inputs(path):
    """(output section, input file, archive member or None, size) for every input section the
    map lists. lld indents output sections by 0, input sections by 8 and symbols by 16."""
    out_section = None
    for line in open(path, encoding="utf-8", errors="replace"):
        m = MAP_LINE.match(line)
        if not m:
            continue
        indent, text, size = len(m.group(5)), m.group(6), int(m.group(3), 16)
        if indent == 0:
            out_section = text
        elif indent == 8:
            source = text.rsplit(":(", 1)[0]
            member = None
            inner = re.match(r"^(.*)\(([^()]*)\)$", source)
            if inner:
                source, member = inner.groups()
            yield out_section, os.path.normpath(source), member, size


def origin_of(path, origins, places):
    """The origin whose `native_inputs` patterns name this linker input."""
    hits = []
    for o in origins.values():
        patterns = o.get("native_inputs", []) + [g["native_input"] for g in o.get("generated", []) if "native_input" in g]
        for pattern in patterns:
            if pattern == path or fnmatch.fnmatchcase(path, os.path.normpath(expand(pattern, places))):
                hits.append(o["id"])
    return hits


def attribute(leaf, source, ctx, report):
    """Claims a linked library leaf by its map. Returns False when the leaf is not one this
    build links, so it is reported unproven like any other unknown file."""
    linked_names = {n for o in ctx.origins.values() for n in o.get("linked_libraries", [])}
    if leaf.path.rsplit("/", 1)[-1] not in linked_names:
        return False
    args = ctx.args
    if not (args.native_map and args.linked):
        report.unproven.append((leaf.path, "a library this build links needs --native-map and --linked to be checked"))
        return True
    why = loaded_mismatch(read_entry(source, leaf.path), args.linked.read_bytes())
    if why and not args.approximate:
        report.unproven.append((leaf.path, f"--linked is not this library ({why}), so the map does not describe it"))
        return True
    if why:
        report.notes.append(f"{leaf.path}: the map is from another build of this library ({why}); "
                            "which archive members it pulls in can differ")
    report.linked_bytes = args.linked.read_bytes()
    loaded = {n for n, (f, *_) in sections(report.linked_bytes).items() if f & SHF_ALLOC}
    reported = set()
    for out_section, path, member, size in map_inputs(args.native_map):
        if out_section not in loaded or size == 0:
            continue
        hits = origin_of(path, ctx.origins, ctx.places)
        if len(hits) == 1:
            report.count(hits[0], "native bytes", size)
            report.native_inputs.add(f"{path}({member})" if member else path)
        elif hits:
            report.ambiguous.append((f"{leaf.path} input {path}", sorted(hits)))
        elif path not in reported:
            reported.add(path)
            label = f"{path}({member})" if member else path
            report.unproven.append((f"{leaf.path} input {label}", "no origin's native_inputs name this file"))
    report.claim("(native, by link map)", leaf)
    if args.compile_log:
        image_classpath(ctx, report)
    return True


def image_classpath(ctx, report):
    """The Java that native-image compiled into the library: every entry on its classpath must
    be a known origin's input. The classpath is one jar whose manifest lists the real entries."""
    log = ctx.args.compile_log.read_text(encoding="utf-8", errors="replace")
    command = log.split("Command Line", 1)[1].split("\n\n", 1)[0]
    words = command.split()
    entries = words[words.index("-cp") + 1].split(":") if "-cp" in words else []
    expanded = []
    for entry in entries:
        path = Path(entry)
        if path.name == "classpathJar.jar" and path.exists():
            import zipfile
            text = zipfile.ZipFile(path).read("META-INF/MANIFEST.MF").decode()
            text = re.sub(r"\r?\n ", "", text)
            listed = re.search(r"^Class-Path: (.*)$", text, re.M)
            expanded += [str((path.parent / p).resolve()) for p in (listed.group(1).split() if listed else [])]
        else:
            expanded.append(entry)
    # The GraalVM runtime and the Java class library are compiled into every image without
    # being on its classpath; the registry marks them with these two names.
    for o in ctx.origins.values():
        if {"(graalvm runtime)", "(java class library)"} & set(o.get("image_classpath", [])):
            report.count(o["id"], "in the native image")
    modules_in_image(ctx, report)
    embedded_resources(expanded, ctx, report)
    for entry in expanded:
        hits = origin_of(os.path.normpath(entry), {i: {"id": i, "native_inputs": o.get("image_classpath", [])}
                                                  for i, o in ctx.origins.items()}, ctx.places)
        if len(hits) == 1 and ctx.origins[hits[0]].get("not_compiled_in"):
            compiled_in = classes_compiled_in(Path(entry), ctx)
            if compiled_in:
                report.count(hits[0], "classes compiled into the image")
                report.unproven.append((f"native-image classpath entry {entry}",
                                        f"its classes are compiled into the library: {', '.join(sorted(compiled_in)[:5])}"))
            else:
                report.notes.append(f"{entry} is on native-image's classpath, and none of the classes only it "
                                    "defines has a symbol in the library")
        elif len(hits) == 1:
            report.count(hits[0], "image classpath entries")
        else:
            report.unproven.append((f"native-image classpath entry {entry}",
                                    "no origin's image_classpath names it" if not hits else f"claimed by {hits}"))


def symbol_prefixes(linked):
    """The class simple names native-image compiled methods of: the part of each symbol before
    its first underscore (Node_draw_<hash> gives Node)."""
    import subprocess
    symbols = subprocess.run(["nm", "--defined-only", str(linked)], capture_output=True, text=True).stdout
    return {line.split()[-1].split("_", 1)[0] for line in symbols.splitlines() if line.strip()}


def simple_names(jar):
    import zipfile
    return {n.rsplit("/", 1)[-1][:-len(".class")] for n in zipfile.ZipFile(jar).namelist()
            if n.endswith(".class") and not n.startswith("META-INF/")}


def jdk_modules(ctx):
    """{module: class simple names} for the Java class library native-image compiled from."""
    import subprocess
    graalvm = Path(expand("{graalvm}", ctx.places))
    listing = subprocess.run([str(graalvm / "bin" / "jimage"), "list", str(graalvm / "lib" / "modules")],
                             capture_output=True, text=True, check=True).stdout
    modules, current = {}, None
    for line in listing.splitlines():
        if line.startswith("Module: "):
            current = modules.setdefault(line.split()[1], set())
        elif line.strip().endswith(".class") and current is not None:
            current.add(line.strip().rsplit("/", 1)[-1][:-len(".class")])
    return modules


def modules_in_image(ctx, report):
    """Which of the class library's modules have compiled code in the library: a module counts
    when a symbol starts with a class simple name that only it defines, among all modules, the
    image's other classpath jars and our own classes. Its third-party components follow."""
    modules = jdk_modules(ctx)
    owners = {}
    for module, names in modules.items():
        for name in names:
            owners.setdefault(name, set()).add(module)
    others = set()
    for jar in Path(ctx.places["build"], "gluonfx/aarch64-android/gvm/tmp/deps").glob("*.jar"):
        others |= simple_names(jar)
    for root, _, files in os.walk(Path(ctx.places["build"], "classes")):
        others |= {f[:-len(".class")] for f in files if f.endswith(".class")}
    for prefix in symbol_prefixes(ctx.args.linked):
        if prefix in owners and len(owners[prefix]) == 1 and prefix not in others:
            report.image_modules[next(iter(owners[prefix]))] = report.image_modules.get(next(iter(owners[prefix])), 0) + 1


def classes_compiled_in(jar, ctx):
    """Classes from a jar on native-image's classpath whose compiled code is in the library.

    native-image names each compiled method after its class's simple name (Node_draw_<hash>),
    with no package. So a class counts as compiled in when a symbol starts with a simple name
    that this jar defines and nothing else on the classpath or in the Java class library does.
    A name shared with another jar proves nothing either way, and is left out."""
    elsewhere = set()
    for other in Path(ctx.places["build"], "gluonfx/aarch64-android/gvm/tmp/deps").glob("*.jar"):
        elsewhere |= simple_names(other)
    for root, _, files in os.walk(Path(ctx.places["build"], "classes")):
        elsewhere |= {f[:-len(".class")] for f in files if f.endswith(".class")}
    for names in jdk_modules(ctx).values():
        elsewhere |= names
    for svm_jar in Path(expand("{graalvm}", ctx.places), "lib", "svm").rglob("*.jar"):
        elsewhere |= simple_names(svm_jar)
    return (simple_names(jar) - elsewhere) & symbol_prefixes(ctx.args.linked)


def embedded_resources(entries, ctx, report):
    """Files other than classes that native-image copied into the library, such as fonts.

    native-image stores the resources an app loads (fonts, stylesheets, license texts) inside the
    library as raw bytes. They are no file in the APK, so they pass no other test: a resource
    counts as shipped when its whole content appears in the linked library. It then belongs to
    the origin whose `files` patterns name it (a font inside our classes folder is the font's),
    or else to the origin of the classpath entry it came from."""
    import zipfile
    data = report.linked_bytes
    for entry in entries:
        path = Path(entry)
        hits = origin_of(os.path.normpath(entry), {i: {"id": i, "native_inputs": o.get("image_classpath", [])}
                                                  for i, o in ctx.origins.items()}, ctx.places)
        if len(hits) != 1:
            continue
        if path.is_dir():
            files = [(str(f.relative_to(path)), f.read_bytes()) for f in path.rglob("*")
                     if f.is_file() and f.suffix != ".class"]
        elif path.suffix == ".jar" and path.exists():
            z = zipfile.ZipFile(path)
            files = [(n, z.read(n)) for n in z.namelist()
                     if not n.endswith("/") and not n.endswith(".class") and not n.startswith("META-INF/")]
        else:
            continue
        for name, content in files:
            if len(content) < 64 or content not in data:
                continue
            owner = hits[0]
            for o in ctx.origins.values():
                if o.get("inside") == hits[0] and any(fnmatch.fnmatchcase(name, p) or fnmatch.fnmatchcase("/" + name, p)
                                                      for p in o.get("files", [])):
                    owner = o["id"]
            report.count(owner, "resources embedded in the native image")
            report.embedded[name] = content
            if ctx.origins[owner].get("not_compiled_in"):
                report.unproven.append((f"native-image resource {name} from {entry}",
                                        f"embedded in the library, and {owner} must put nothing in the app"))
