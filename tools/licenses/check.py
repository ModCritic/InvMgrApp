#!/usr/bin/env python3
"""Proves where every byte of a shipped InvMgr file came from, and what its license asks.

    python3 tools/licenses/check.py jar <jar> --classes <compiled classes> [release options]
    python3 tools/licenses/check.py apk <apk> --native-map <map> --linked <so> [release options]
    python3 tools/licenses/check.py linux <.tar.gz, .AppImage, .deb or .rpm> --classes <compiled classes>
    python3 tools/licenses/check.py windows <the setup .exe, with app/ and build.log beside it> --classes <...>

It breaks the file into leaves (inventory.py), proves each leaf's origin against the inputs of
every origin in registry.toml (inputs.py), and then checks each origin's obligations. It exits 1
when any leaf has no proven origin, when two origins claim one leaf, when an origin marked as
never allowed is found, or when an obligation is unmet with no recorded decision.

The old check matched package names and library file names against a list. Every license
problem it missed was something a name cannot show: a Windows DLL by another author inside a
jar it never opened, a static library linked into a native library it treated as one row. This
one matches bytes wherever bytes survive the build, and names only where the build rewrites
them (Android's dex and resources), with Android's own tools reading the names back.
"""
import argparse
import fnmatch
import hashlib
import os
import re
import sys
import tomllib
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from inputs import PLACES, Index, expand        # noqa: E402
from inventory import leaves, read_entry          # noqa: E402

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent


def matches(pattern, path):
    """A pattern with a slash is held against the whole path, one without against the name."""
    target = path if "/" in pattern else path.rsplit("/", 1)[-1].rsplit("!", 1)[-1]
    return fnmatch.fnmatchcase(target, pattern)


# What a file the build writes may contain. Each returns None when the content is what the
# tool writes, or a reason when it is not.

def manifest_ok(data):
    for line in data.decode("utf-8", "replace").splitlines():
        if line and not line.startswith(" ") and not re.match(r"^[A-Za-z0-9_-]+: ", line):
            return f"not a manifest header: {line[:60]!r}"
    return None


def pom_properties_ok(data):
    for line in data.decode("utf-8", "replace").splitlines():
        if line and not line.startswith("#") and not re.match(r"^(groupId|artifactId|version)=", line):
            return f"unexpected line: {line[:60]!r}"
    return None


def properties_ok(data):
    for line in data.decode("utf-8", "replace").splitlines():
        if line and not line.startswith("#") and not re.match(r"^[\w.-]+=[\w.\-:/ ]*$", line):
            return f"unexpected line: {line[:60]!r}"
    return None


def textproto_ok(data):
    for line in data.decode("utf-8", "replace").splitlines():
        if line and not re.match(r'^\s*([\w]+\s*[:{]|})', line):
            return f"unexpected line: {line[:60]!r}"
    return None


def pkcs7_ok(data):
    # A signature block is DER-encoded: it starts with a SEQUENCE tag and holds the signing
    # certificate, whose subject we can look for to be sure it is ours.
    return None if data[:1] == b"\x30" else "not a DER signature block"


def art_profile_ok(data):
    # ART's binary startup profile, which the build writes from the libraries' baseline-prof.txt
    # files: the magic "pro\0" for .prof, "prm\0" for its metadata.
    return None if data[:4] in (b"pro\x00", b"prm\x00") else "not an ART profile"


def bundle_config_ok(data):
    # bundletool's version, then the file patterns it keeps uncompressed (**.[pP][nN][gG] ...).
    for text in re.findall(rb"[\x20-\x7e]{3,}", data):
        if not re.fullmatch(rb"\d+\.\d+\.\d+|\*\*\.[\[\]A-Za-z0-9]+", text):
            return f"unexpected text {text[:40]!r}"
    return None


def native_pb_ok(data):
    # The bundle's list of native library folders, nothing else.
    for text in re.findall(rb"[\x20-\x7e]{4,}", data):
        if not re.fullmatch(rb"lib/[\w-]+", text):
            return f"unexpected text {text[:40]!r}"
    return None


def d8_json_ok(data):
    import json
    try:
        doc = json.loads(data)
    except ValueError:
        return "not JSON"
    return None if set(doc) <= {"options", "version"} else f"unexpected keys {sorted(doc)}"


def ini_ok(data):
    # A desktop menu entry, or the .cfg file jpackage writes beside the jar: [Section] headers
    # and key=value lines.
    for line in data.decode("utf-8", "replace").splitlines():
        if line and not line.startswith("#") and not re.match(r"^\[[\w .-]+\]$|^[A-Za-z][\w.-]*(\[[\w@.-]+\])?=", line):
            return f"not a [section] or key=value line: {line[:60]!r}"
    return None


def jpackage_state_ok(data):
    # lib/app/.jpackage.xml, jpackage's record of how it made the app folder.
    from xml.etree import ElementTree
    try:
        root = ElementTree.fromstring(data)
    except ElementTree.ParseError:
        return "not XML"
    extra = {c.tag for c in root} - {"app-version", "main-launcher", "add-launcher", "signed", "app-store"}
    if root.tag != "jpackage-state" or extra:
        return f"unexpected elements: {root.tag} {sorted(extra)}"
    return None


def deb_version_ok(data):
    # debian-binary, the first member of every .deb: the package format's version.
    return None if data == b"2.0\n" else f"not dpkg's format version: {data[:20]!r}"


def md5_ok(data):
    return None if len(data) == 16 else f"{len(data)} bytes, not an MD5 digest's 16"


def jlink_release_ok(data):
    # The release file jlink writes into a runtime: KEY="value" lines.
    for line in data.decode("utf-8", "replace").splitlines():
        if line and not re.fullmatch(r'[A-Z_]+="[^"]*"', line):
            return f"not a KEY=\"value\" line: {line[:60]!r}"
    return None


def legal_pointer_ok(data):
    # On Windows jlink writes one line pointing at java.base's copy where Linux has a link.
    text = data.decode("utf-8", "replace").strip()
    names = ("LICENSE", "ASSEMBLY_EXCEPTION", "ADDITIONAL_LICENSE_INFO")
    return None if text in {f"Please see ..\\java.base\\{n}" for n in names} else f"not a pointer to java.base: {text[:60]!r}"


CHECKS = {
    "manifest": manifest_ok, "pom-properties": pom_properties_ok, "properties": properties_ok,
    "textproto": textproto_ok, "pkcs7": pkcs7_ok, "art-profile": art_profile_ok,
    "bundle-config": bundle_config_ok, "native-pb": native_pb_ok, "d8-json": d8_json_ok,
    "ini": ini_ok, "jpackage-state": jpackage_state_ok, "deb-version": deb_version_ok, "md5": md5_ok,
    "jlink-release": jlink_release_ok, "legal-pointer": legal_pointer_ok,
}


def rule_mismatch(data, rule, places):
    """None when a file a tool wrote is what its rule says, else a reason. A rule names a content
    check, one of jpackage's templates, a script whose lines the file must all be, or a file in a
    tar archive whose opening the file must be (a notice cut from the top of a source file)."""
    if "template" in rule:
        import linux
        return linux.template_mismatch(data, rule, places)
    if "pe_like" in rule or "jimage_names_in" in rule or "built_twice" in rule:
        import windows
        if "pe_like" in rule:
            return windows.launcher_mismatch(data, rule, places)
        if "jimage_names_in" in rule:
            return windows.jimage_mismatch(data, rule, places)
        return windows.built_twice_mismatch(data, places)
    if "prefix_of" in rule:
        import tarfile
        archive, _, member = expand(rule["prefix_of"], places).partition("!")
        with tarfile.open(archive) as tar:
            whole = tar.extractfile(member).read()
        return None if data and whole.startswith(data) else f"it is not the start of {member}"
    if "lines_from" in rule:
        script = Path(expand(rule["lines_from"], places)).read_text().splitlines()
        for line in data.decode("utf-8", "replace").splitlines():
            if line not in script:
                return f"the line {line[:60]!r} is not in {rule['lines_from']}"
        return None
    return CHECKS[rule["check"]](data)


class Report:
    def __init__(self):
        self.unproven = []     # (where, why)
        self.ambiguous = []    # (where, origin ids)
        self.claimed = {}      # origin id -> [leaf]
        self.counted = {}      # origin id -> {"classes": n, "resources": n, ...} inside composite leaves
        self.class_origin = {}  # dex class (dotted) -> origin id
        self.problems = []     # registry or obligation problems that fail the check
        self.gaps = []         # (holder, obligation) accepted by a recorded decision
        self.notes = []        # things to know that do not fail the check
        self.image_modules = {}   # class library module -> classes of it native-image compiled in
        self.native_inputs = set()  # linker inputs that put bytes in a linked library
        self.linked_bytes = None    # the linked library, for components found by their data
        self.embedded = {}          # resources native-image copied into the linked library, name to bytes

    def claim(self, origin_id, leaf):
        self.claimed.setdefault(origin_id, []).append(leaf)

    def count(self, origin_id, what, amount=1):
        bucket = self.counted.setdefault(origin_id, {})
        bucket[what] = bucket.get(what, 0) + amount


def load_registry(path=HERE / "registry.toml"):
    with open(path, "rb") as f:
        registry = tomllib.load(f)
    origins = {o["id"]: o for o in registry["origin"]}
    for o in origins.values():
        for field in ("name", "license", "evidence", "targets"):
            if not o.get(field):
                raise SystemExit(f"registry: origin {o['id']} has no {field}")
        if "inside" in o and o["inside"] not in origins:
            raise SystemExit(f"registry: origin {o['id']} is inside unknown origin {o['inside']}")
    return origins


def narrow(candidates, name, origins, field):
    """Drops origins whose `files` (or `classes`) patterns do not name this leaf, and those whose
    `not_files` do."""
    return {i for i in candidates
            if (not origins[i].get(field) or any(matches(p, name) for p in origins[i][field]))
            and not any(matches(p, name) for p in origins[i].get("not_" + field, []))}


def owner(origin_ids, name, origins, field="files"):
    """Which origin a leaf belongs to, when it is found in the inputs of several.

    An origin that names its files and says which origin it arrives inside is more specific
    than that origin: Microsoft's DLLs arrive inside OpenJFX's Windows jar, and the DLLs are
    Microsoft's. Anything else claimed by two origins is ambiguous and fails.
    """
    specific = {i for i in origin_ids if origins[i].get(field)}
    # Two that both name their files: the one inside the other is the more specific. Temurin's
    # Visual C++ files arrive in Temurin's Windows runtime, whose own patterns cover them too.
    nested = {i for i in specific if origins[i].get("inside") in origin_ids}
    if len(nested) == 1:
        chosen = next(iter(nested))
        if origin_ids - {chosen} == {origins[chosen]["inside"]}:
            return chosen, None
    general = origin_ids - specific
    if len(specific) == 1:
        chosen = next(iter(specific))
        if all(origins[chosen].get("inside") == g for g in general):
            return chosen, None
    if not specific and len(general) == 1:
        return next(iter(general)), None
    return None, sorted(origin_ids)


def generated_rule(origins, key, value):
    # A bundle holds the APK's root files under base/root/, so its paths are tried both ways.
    values = [value, value[len("base/root/"):]] if key == "path" and value.startswith("base/root/") else [value]
    for o in origins.values():
        for g in o.get("generated", []):
            pattern = g.get(key)
            if pattern and any(fnmatch.fnmatchcase(v, pattern) for v in values):
                return o["id"], g
    return None, None


class Context:
    """What a check needs besides the artifact: the index, the registry, and for an APK the
    names Android's tools read back out of it."""

    def __init__(self, target, artifact, index, origins, places, args):
        self.target, self.artifact, self.index = target, artifact, index
        self.origins, self.places, self.args = origins, places, args
        self.apk_entries, self.apk_files = [], {}
        if target == "apk":
            import android
            self.apk_entries, self.apk_files = android.resources(artifact)
        elif target == "aab":
            import android
            self.apk_entries = android.bundle_resources(artifact)
            # A bundle keeps resources at their real paths: base/res/<type>[-qualifiers]/<name>.<ext>
            for name in zipfile.ZipFile(artifact).namelist():
                m = re.match(r"base/res/([a-z]+)(?:-[^/]+)?/([^/.]+)", name)
                if m:
                    self.apk_files[name] = m.groups()


def attribute(source, ctx, report, prefix=""):
    for leaf in leaves(source if isinstance(source, bytes) else str(source), prefix):
        inner = leaf.path[len(prefix):]
        if leaf.kind == "empty":
            report.claim("(empty files)", leaf)
            continue
        if leaf.kind == "link":
            root = Path(source).resolve()
            target = Path(source, leaf.path).resolve()
            if target.is_relative_to(root) and target.exists():
                report.claim("(links inside the download)", leaf)
            else:
                report.unproven.append((leaf.path, "a link to something outside the download"))
            continue
        candidates = narrow({oid for oid, _, _ in ctx.index.find(leaf.sha256)}, leaf.path, ctx.origins, "files")
        if candidates:
            chosen, tied = owner(candidates, leaf.path, ctx.origins)
            company = pe_company(read_entry(source, inner)) if chosen and leaf.kind == "pe" else None
            if company and company not in ctx.origins[chosen].get("pe_company", []):
                # A Windows library names its maker inside itself. When that maker is not one the
                # claiming origin records, the file arrived inside someone else's package: the
                # Microsoft runtime DLLs in OpenJFX's Windows jar were exactly that.
                report.unproven.append((leaf.path, f"made by {company!r}, which {chosen} does not record"))
            elif chosen:
                report.claim(chosen, leaf)
            else:
                report.ambiguous.append((leaf.path, tied))
            continue
        if not prefix and ctx.target in ("apk", "aab") and attribute_android(leaf, source, ctx, report):
            continue
        # A rule names a path inside the archive the leaf is in: the jar's META-INF/MANIFEST.MF
        # is the same file whether the jar ships alone or inside a Linux download.
        rule_origin, rule = generated_rule(ctx.origins, "path", inner)
        if rule:
            why = rule_mismatch(read_entry(source, inner), rule, ctx.places)
            if why:
                report.unproven.append((leaf.path, f"written by {rule['tool']}, but {why}"))
            else:
                report.claim(rule_origin, leaf)
            continue
        if leaf.kind == "archive":
            attribute(read_entry(source, inner), ctx, report, prefix=leaf.path + "!")
            continue
        if leaf.kind == "elf" and ctx.target in ("apk", "aab"):
            if attribute_native(leaf, source, ctx, report):
                continue
        report.unproven.append((f"{leaf.path} [{leaf.kind}, {leaf.size} bytes]",
                                "its bytes match no input of any origin"))


def pe_company(data):
    """The CompanyName a Windows library records about itself, or None. Its version information
    holds UTF-16 "CompanyName" followed by the value; "N/A", which OpenJFX writes, counts as none."""
    key = "CompanyName".encode("utf-16-le") + b"\0\0"
    i = data.find(key)
    if i < 0:
        return None
    j = i + len(key)
    while data[j:j + 2] == b"\0\0":
        j += 2
    end = data.find(b"\0\0\0", j)
    value = data[j:end + 1].decode("utf-16-le", "replace").strip()
    return None if value in ("", "N/A") else value


def attribute_android(leaf, source, ctx, report):
    """The three APK parts whose bytes the build rewrites. Returns True when it handled the leaf."""
    if leaf.kind == "dex":
        import android
        classes = android.dex_classes(read_entry(source, leaf.path))
        for cls in classes:
            dotted = cls.replace("/", ".")
            rule_origin, _ = generated_rule(ctx.origins, "class", cls)
            if rule_origin:
                report.count(rule_origin, "classes")
                report.class_origin[dotted] = rule_origin
                continue
            found = narrow({oid for oid, _, _ in ctx.index.find_class(cls)}, cls, ctx.origins, "classes")
            chosen, tied = owner(found, cls, ctx.origins, "classes") if found else (None, None)
            if chosen:
                report.count(chosen, "classes")
                report.class_origin[dotted] = chosen
            elif tied:
                report.ambiguous.append((f"{leaf.path} class {cls}", tied))
            else:
                report.unproven.append((f"{leaf.path} class {cls}", "no input defines this class"))
        report.claim("(dex, by class)", leaf)
        return True
    if leaf.path in ctx.apk_files:
        kind, name = ctx.apk_files[leaf.path]
        found = {oid for oid, _, _ in ctx.index.find_resource(kind, name)}
        chosen, tied = owner(found, name, ctx.origins) if found else (None, None)
        if chosen:
            report.claim(chosen, leaf)
        elif tied:
            report.ambiguous.append((f"{leaf.path} ({kind}/{name})", tied))
        else:
            report.unproven.append((f"{leaf.path} ({kind}/{name})", "no input declares this resource"))
        return True
    if leaf.path == "BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb":
        # The build's own record of every library in the bundle: each must be one the registry
        # names, so a library added to the build cannot arrive unrecorded.
        import android
        named = {spec.split(":", 1)[1] for o in ctx.origins.values() for spec in o.get("inputs", [])
                 if spec.startswith("gradle:")}
        named |= {lib for o in ctx.origins.values() for lib in o.get("metadata_only", [])}
        for lib in sorted(android.bundle_dependencies(read_entry(source, leaf.path))):
            if lib not in named:
                report.unproven.append((f"{leaf.path} lists {lib}", "no origin in the registry names this library"))
        report.claim("build-generated", leaf)
        return True
    if leaf.path in ("resources.arsc", "base/resources.pb"):
        for kind, name in ctx.apk_entries:
            found = {oid for oid, _, _ in ctx.index.find_resource(kind, name)}
            if found:
                # A name several libraries declare (an id, typically) is one entry in the
                # table either way; what matters is that a known input declared it.
                for oid in sorted(found)[:1]:
                    report.count(oid, "resource entries")
            else:
                report.unproven.append((f"{leaf.path} entry {kind}/{name}", "no input declares it"))
        report.claim("build-generated", leaf)
        return True
    if leaf.path in ("AndroidManifest.xml", "base/manifest/AndroidManifest.xml"):
        import android
        if leaf.path == "AndroidManifest.xml":
            classes, permissions = android.manifest(ctx.artifact)
        else:
            classes, permissions = android.bundle_manifest(ctx.artifact)
        texts = manifest_texts(ctx)
        for cls in sorted(classes):
            if cls not in report.class_origin and not cls.startswith("android."):
                report.unproven.append((f"AndroidManifest.xml names {cls}", "no class of that name in the dex"))
        for permission in sorted(permissions):
            short = permission.rsplit(".", 1)[-1]
            if not any(permission in t or ("${applicationId}." + short) in t for t in texts):
                report.unproven.append((f"AndroidManifest.xml asks for {permission}",
                                        "no input manifest asks for it"))
        report.claim("build-generated", leaf)
        return True
    return False


def manifest_texts(ctx):
    """The text of every AndroidManifest.xml among the inputs: ours and every library's."""
    texts = []
    for o in ctx.origins.values():
        if "apk" not in o["targets"]:
            continue
        for spec in o.get("inputs", []):
            if re.search(r"\{\w+\}", expand(spec, ctx.places)):
                continue
            from inputs import members, resolve
            if not resolve(spec, ctx.places)[1].exists():
                continue
            for inner, data in members(spec, ctx.places):
                if inner.endswith("AndroidManifest.xml"):
                    texts.append(data.decode("utf-8", "replace"))
    return texts


def attribute_native(leaf, source, ctx, report):
    """A native library linked by this build. Handled in native.py; see that file."""
    import native
    return native.attribute(leaf, source, ctx, report)


MARKERS = re.compile(r"copyright|SPDX-License|licensed under|general public license|apache license"
                     r"|permission is hereby granted|all rights reserved|\(c\) \d{4}", re.I)


def check_repo(ref, origins, report, places):
    """The published repository: every file in it must belong to exactly one origin by its
    `repo_paths`, and a file we claim as our own may carry a license or copyright line only where
    the registry records why (`repo_markers`). Code pasted in with its header still on is caught
    here; code pasted in without one is not, which is why copying anything in is a moment to add
    it to the registry."""
    import subprocess
    paths = subprocess.run(["git", "-C", places["repo"], "ls-tree", "-r", "--name-only", ref],
                           capture_output=True, text=True, check=True).stdout.split()
    patterns = {o["id"]: o.get("repo_paths", []) for o in origins.values() if "repo" in o["targets"]}
    allowed = [m for o in origins.values() for m in o.get("repo_markers", [])]
    for path in paths:
        claimed = {i for i, ps in patterns.items() if any(fnmatch.fnmatchcase(path, p) for p in ps)}
        chosen, tied = owner(claimed, path, {i: dict(origins[i], files=origins[i].get("inside") and patterns[i]) for i in claimed}) if claimed else (None, None)
        from inventory import Leaf
        data = subprocess.run(["git", "-C", places["repo"], "show", f"{ref}:{path}"], capture_output=True, check=True).stdout
        leaf = Leaf(path, hashlib.sha256(data).hexdigest(), len(data), "text")
        if not chosen:
            if tied:
                report.ambiguous.append((path, tied))
            else:
                report.unproven.append((path, "no origin's repo_paths name it"))
            continue
        report.claim(chosen, leaf)
        if origins[chosen]["license"] != "0BSD" or b"\0" in data[:8000]:
            continue
        for number, line in enumerate(data.decode("utf-8", "replace").splitlines(), 1):
            if MARKERS.search(line) and not any(fnmatch.fnmatchcase(path, m["path"]) and m.get("text", "") in line for m in allowed):
                report.unproven.append((f"{path}:{number}", f"a license or copyright line in a file we claim as our own: {line.strip()[:90]!r}"))


def build_index(origins, target, places):
    index = Index()
    for o in origins.values():
        if target in o["targets"]:
            for spec in o.get("inputs", []):
                index.add(o["id"], spec, places)
    return index


def satisfied(spec, shipped, places):
    """Whether one met_by entry holds. Returns (True or False or None, what was looked at);
    None means it could not be checked in this run (a release check needs --release).

    `shipped` maps each path in the artifact to a function returning its bytes. On a phone build
    that includes the resources native-image copied into the library, which are no file in the
    APK but are where the app reads its license texts from.

    An artifact: path is found where it is, or at the end of a longer one, so that a path inside
    the jar is found inside a Linux download's jar, and lib/runtime/... under whatever folder
    each download puts the app in."""
    spec = expand(spec, places)
    if spec.startswith("artifact:"):
        if places.get("target") == "repo":
            return "skip", spec    # a path inside a shipped file means nothing for the repository
        path, _, text = spec[len("artifact:"):].partition("~")
        found = path if path in shipped else next(
            (p for p in sorted(shipped) if p.endswith(("/" + path, "!" + path))), None)
        if found is None or not text:
            return found is not None, found or path
        # Windows programs keep their text in UTF-16, so a notice there is found that way too.
        data = shipped[found]()
        return (text in data.decode("utf-8", "replace") or text.encode("utf-16-le") in data,
                f"{found} contains {text!r}")
    if spec.startswith("release:"):
        if "release" not in places:
            return None, "needs --release"
        file, _, rest = spec[len("release:"):].partition("!")
        member, _, digest = rest.partition("=")
        bundle = Path(places["release"], file)
        if not bundle.exists():
            return False, f"{bundle} missing"
        with zipfile.ZipFile(bundle) as z:
            if member not in z.namelist():
                return False, f"{member} not in {file}"
            h = hashlib.sha256()
            with z.open(member) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
        return h.hexdigest() == digest, f"{file}!{member}"
    if spec.startswith("repo:"):
        path, _, text = spec[len("repo:"):].partition("~")
        file = Path(places["repo"], path)
        return file.exists() and text in file.read_text(encoding="utf-8"), f"{path} contains {text!r}"
    if spec.startswith("release-text~"):
        if "release_text" not in places:
            return None, "needs --release-text"
        text = spec[len("release-text~"):]
        return text in Path(places["release_text"]).read_text(encoding="utf-8"), f"Release text contains {text!r}"
    raise SystemExit(f"registry: unknown met_by form {spec!r}")


def check_completeness(report, origins, index, places):
    """Two ways the registry itself could fall behind, each checked on every run.

    legal_files: an origin whose own source lists its third-party code in legal files (OpenJFX
    and OpenJDK both do) names that source; every legal file there must have a component. A new
    version that bundles something new then fails until it is recorded.

    NOTICE files: Apache-2.0 section 4(d) passes a NOTICE file's attribution on to whoever
    redistributes the work. Any file named NOTICE inside an Apache-2.0 origin's inputs fails
    unless that origin records a notice obligation."""
    import tarfile
    for origin_id in present(report):
        o = origins.get(origin_id)
        if not o:
            continue
        legal = o.get("legal_files")
        if legal and legal.startswith("artifact:"):
            # The origin ships its own legal folder, so what ships is what has to be covered.
            pattern = "*" + legal[len("artifact:"):]
            found = {leaf.path.rsplit("/", 1)[-1] for held in report.claimed.values() for leaf in held
                     if fnmatch.fnmatchcase(leaf.path, pattern)}
            named = {c.get("legal_file") for c in o.get("component", [])}
            for missing in sorted(found - named):
                report.problems.append(f"{o['name']}: it ships third-party notices in {missing}, and no component records it")
        elif legal:
            archive, _, pattern = expand(legal, places).partition("!")
            if "{" in archive or "release" not in places and "{release}" in legal:
                report.notes.append(f"{o['name']}: legal-file coverage needs --release, not checked")
            elif not Path(archive).exists():
                report.problems.append(f"{o['name']}: {archive} missing, so its legal files cannot be counted")
            else:
                with tarfile.open(archive) as tar:
                    found = {m.name.rsplit("/", 1)[-1] for m in tar if fnmatch.fnmatchcase(m.name, pattern)}
                named = {c.get("legal_file") for c in o.get("component", [])}
                for missing in sorted(found - named):
                    report.problems.append(f"{o['name']}: its source lists third-party code in {missing}, and no component records it")
        if o["license"].startswith("Apache-2.0") and not any(ob["kind"] == "notice" for ob in o.get("obligation", [])):
            for digest, holders in index.by_hash.items():
                for holder_id, spec, inner in holders:
                    if holder_id == origin_id and re.search(r"(^|[/!])NOTICE(\.txt|\.md)?$", inner, re.I):
                        report.problems.append(f"{o['name']}: {spec} carries {inner}, an Apache-2.0 NOTICE file, and no notice obligation is recorded")


def present(report):
    """Every origin found anywhere: as a whole leaf, or inside a dex or a linked library."""
    return set(report.claimed) | set(report.counted)


def check_obligations(report, origins, places, artifact=None):
    """Every obligation of every origin found, and of every component found inside one."""
    shipped = {leaf.path: (lambda p=leaf.path: read_entry(artifact, p))
               for found in report.claimed.values() for leaf in found}
    shipped.update({name: (lambda data=data: data) for name, data in report.embedded.items()})
    lines = []
    for origin_id in sorted(present(report)):
        o = origins.get(origin_id)
        if not o:
            continue
        if o.get("license_status") == "unverified":
            report.problems.append(f"{o['name']} is in the artifact, and its license has not been read from a file yet: {'; '.join(o['evidence'])}")
        if o.get("allowed") is False:
            report.problems.append(f"{o['name']} is in the artifact, and the registry says it must never ship: {o.get('why_not', '')}")
        holders = [(o["name"], o.get("obligation", []))]
        mine = report.claimed.get(origin_id, [])
        for c in o.get("component", []):
            if c.get("marker") and report.linked_bytes is not None:
                # Data, not code: present only when a string its data contains is in the library.
                found = c["marker"].encode() in report.linked_bytes
            else:
                found = None
            if found is False:
                lines.append(f"  -       {c['name']}: not in this artifact ({c['marker']!r} is not in the library)")
                continue
            if (found or c.get("always")
                    or any(matches(p, leaf.path) for leaf in mine for p in c.get("in", []))
                    or (c.get("module") and c["module"] in report.image_modules)
                    or any(fnmatch.fnmatchcase(i, expand(p, places)) for i in report.native_inputs
                           for p in c.get("native", []))):
                holders.append((f"{c['name']} (inside {origin_id})", c.get("obligation", [])))
            else:
                lines.append(f"  -       {c['name']}: not in this artifact")
        for holder, obligations in holders:
            for ob in obligations:
                results = [r for r in (satisfied(s, shipped, places) for s in ob.get("met_by", []))
                           if r[0] != "skip"]
                if any(r is None for r, _ in results):
                    state = "not checked"
                elif results and all(r for r, _ in results):
                    state = "met"
                elif ob.get("gap"):
                    state = "GAP"
                else:
                    state = "UNMET"
                lines.append(f"  {state:7s} {holder}: {ob['kind']}")
                for r, what in results:
                    lines.append(f"            {'ok ' if r else 'no ' if r is False else '?  '} {what}")
                if ob.get("gap") and state in ("GAP", "met"):
                    lines.append(f"            decision: {ob['gap']}")
                    report.gaps.append((holder, ob))
                if state == "UNMET":
                    report.problems.append(f"{holder}: {ob['kind']} obligation not met ({ob['why']})")
    print("\nObligations:")
    print("\n".join(lines))


def print_report(report, origins):
    print("Origins found:")
    for origin_id in sorted(present(report), key=lambda i: -len(report.claimed.get(i, []))):
        o = origins.get(origin_id, {"license": "-"})
        found = report.claimed.get(origin_id, [])
        size = sum(leaf.size for leaf in found)
        inside = ", ".join(f"{n} {what}" for what, n in report.counted.get(origin_id, {}).items())
        print(f"  {len(found):6d} files {size:>12,d} bytes  {origin_id:30s} {o['license']}"
              + (f"  (+ {inside})" if inside else ""))
    if report.ambiguous:
        print(f"\nCLAIMED BY MORE THAN ONE ORIGIN ({len(report.ambiguous)}):")
        for where, ids in report.ambiguous[:50]:
            print(f"  {where}  {', '.join(ids)}")
    if report.unproven:
        print(f"\nNO PROVEN ORIGIN ({len(report.unproven)}):")
        for where, why in report.unproven[:200]:
            print(f"  {where}: {why}")
        if len(report.unproven) > 200:
            print(f"  ... and {len(report.unproven) - 200} more")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("target", choices=["jar", "apk", "aab", "linux", "windows", "repo"])
    parser.add_argument("artifact", help="the file to check; for repo, the git branch or commit that is published")
    parser.add_argument("--classes", type=Path, help="jar, linux: the compiled classes of the build that made the jar")
    parser.add_argument("--repo", type=Path, default=REPO, help="the source at the commit the artifact was built from")
    parser.add_argument("--build", type=Path, help="apk: the build folder the logs and objects are in (default: target/)")
    parser.add_argument("--native-map", type=Path, help="apk: the linker map of libsubstrate.so")
    parser.add_argument("--linked", type=Path, help="apk: the library as the linker wrote it, before stripping")
    parser.add_argument("--compile-log", type=Path, help="apk: native-image's command line, from the build's log")
    parser.add_argument("--approximate", action="store_true",
                        help="apk: accept a link map from another build of the library, for a release "
                             "made before maps were kept; says so in the output")
    parser.add_argument("--release", type=Path, help="the release folder, to check source obligations")
    parser.add_argument("--release-text", type=Path, help="the Release's text, to check credits")
    parser.add_argument("--version", help="the release's version, for {version} in the registry")
    parser.add_argument("--registry", type=Path, default=HERE / "registry.toml",
                        help="another registry, for the self-test's deliberately broken copies")
    args = parser.parse_args()
    if args.target in ("jar", "linux", "windows") and not args.classes:
        parser.error(f"a {args.target} check needs --classes")

    places = dict(PLACES, repo=str(args.repo.resolve()), build=str((args.build or REPO / "target").resolve()),
                  target=args.target)
    for key in ("classes", "release", "release_text", "version"):
        if getattr(args, key):
            places[key] = str(getattr(args, key))
    origins = load_registry(args.registry)
    if args.target == "linux":
        # The download is unpacked into a folder, and its Java runtime made again by jlink to be
        # matched against, both in a scratch folder that goes when the check ends.
        import linux
        import tempfile
        scratch = tempfile.TemporaryDirectory(prefix="invmgr-license-")
        unpacked = Path(scratch.name, "download")
        kind = linux.unpack(args.artifact, unpacked)
        shipped_runtime = linux.shipped_runtime(unpacked)
        places["runtime"] = str(linux.runtime(shipped_runtime, places["temurin"], scratch.name))
        print(f"{args.artifact}: a {kind}; its Java runtime was made again with jlink to compare\n")
        args.artifact = unpacked
    if args.target == "windows":
        # The setup can't be opened here; see windows.py for how it is proven. Temurin for Windows,
        # unzipped on this machine, is what the app folder's runtime is matched against.
        import tempfile
        import windows
        scratch = tempfile.TemporaryDirectory(prefix="invmgr-license-")
        places["temurin_windows"] = os.environ.get(
            "TEMURIN_WINDOWS", str(Path.home() / "ClaudeWorkspace/InvMgr-build/windows/jdk-17.0.21+9"))
        args.artifact = windows.prepare(args.artifact, Path(scratch.name, "download"), places)
        shipped_runtime = args.artifact / "app" / "InvMgr" / "runtime"
    # A bundle holds the same Android app as the APK, so the same origins apply.
    index = build_index(origins, "apk" if args.target == "aab" else args.target, places) if args.target != "repo" else Index()
    report = Report()
    if args.target in ("linux", "windows"):
        import linux
        report.image_modules = linux.class_counts(shipped_runtime, places["temurin"])
    for origin_id, spec, path in index.missing:
        report.problems.append(f"input of {origin_id} not on this machine: {spec} ({path})")
    if args.target == "repo":
        check_repo(args.artifact, origins, report, places)
    else:
        args.artifact = Path(args.artifact)
        ctx = Context(args.target, args.artifact, index, origins, places, args)
        attribute(args.artifact, ctx, report)
    print_report(report, origins)
    if report.image_modules and args.target in ("linux", "windows"):
        print("\nJava modules in the bundled runtime (entries each has in lib/modules):")
        print("  " + ", ".join(f"{m} {n}" for m, n in sorted(report.image_modules.items(), key=lambda kv: -kv[1])))
    elif report.image_modules:
        print("\nClass library modules with code in the native image (classes only that module defines):")
        print("  " + ", ".join(f"{m} {n}" for m, n in sorted(report.image_modules.items(), key=lambda kv: -kv[1])))
    check_obligations(report, origins, places, None if args.target == "repo" else args.artifact)
    check_completeness(report, origins, index, places)
    for line in report.notes:
        print(f"NOTE: {line}")
    for line in report.problems:
        print(f"PROBLEM: {line}")
    failed = bool(report.unproven or report.ambiguous or report.problems)
    print("\nFAIL" if failed else "\nOK: every leaf has exactly one proven origin, and every obligation is met or decided.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
