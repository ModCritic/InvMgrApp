"""The files a build starts from, indexed so a shipped leaf can be traced back to one.

An input is written as a short spec in the registry:

    maven:<group>:<artifact>:<version>[:<classifier>]   a jar in the local Maven repository
    gradle:<group>:<artifact>:<version>                  an aar or jar in Gradle's download cache
    zip:<path>                                           every file inside a zip, jar or aar
    tar:<path>                                           every file inside a tar archive, compressed or not
    dir:<path>                                           every file under a directory
    file:<path>                                          one file
    src:<path>                                           Java sources: the classes they define,
                                                         for code that is recompiled on the way in

Paths may use the placeholders in PLACES, filled in from the command line, so the registry
names no one's home directory.

Three lookups come out of it. By SHA-256, for files that ship unchanged. By class name, for
Android's dex format, which recompiles every class so no bytes survive to match. By resource
type and name, for Android resources, which the resource compiler rewrites and renames.
"""
import hashlib
import io
import os
import re
import tarfile
import zipfile
from pathlib import Path
from xml.etree import ElementTree

PLACES = {
    "m2": str(Path.home() / ".m2" / "repository"),
    "gradle": str(Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"),
    "gluon": str(Path.home() / ".gluon" / "substrate"),
    "graalvm": str(Path.home() / "graalvm-svm-java17-linux-gluon-22.1.0.1-Final"),
    # The Linux downloads' Java and AppImage runtime, where tools/installers/build-linux.sh finds
    # them, and moved by the same two variables.
    "temurin": os.environ.get("TEMURIN_HOME", str(Path.home() / "ClaudeWorkspace" / "InvMgr-build" / "temurin" / "jdk-17.0.21+9")),
    "appimage_runtime": os.environ.get("APPIMAGE_RUNTIME", str(Path.home() / "ClaudeWorkspace" / "InvMgr-build" / "appimage" / "runtime-20251108" / "runtime-x86_64")),
    "linux_sources": os.environ.get("LINUX_SOURCES", str(Path.home() / "ClaudeWorkspace" / "InvMgr-build" / "linux-sources")),
}

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def expand(spec, places):
    for key, value in places.items():
        spec = spec.replace("{" + key + "}", value)
    return spec


def maven_path(coords, places):
    group, artifact, version, *rest = coords.split(":")
    name = f"{artifact}-{version}" + (f"-{rest[0]}" if rest else "") + ".jar"
    return Path(places["m2"], *group.split("."), artifact, version, name)


def gradle_path(coords, places):
    """Gradle keeps each download under a folder named for its hash, so the file is searched for."""
    group, artifact, version = coords.split(":")
    base = Path(places["gradle"], group, artifact, version)
    for suffix in (".aar", ".jar"):
        found = sorted(base.glob(f"*/{artifact}-{version}{suffix}"))
        if found:
            return found[0]
    return base / f"{artifact}-{version}.aar"


def resolve(spec, places):
    """The path an input spec points at, and how to read it."""
    how, _, rest = spec.partition(":")
    if how == "maven":
        return "zip", maven_path(rest, places)
    if how == "gradle":
        return "zip", gradle_path(rest, places)
    if how in ("zip", "tar", "dir", "file", "src"):
        # "src:<archive>!<folder>" names a folder of Java sources inside an archive.
        return how, Path(expand(rest.split("!", 1)[0], places))
    raise ValueError(f"unknown input kind in {spec!r}")


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_members(source, prefix=""):
    """(path, bytes) for every file in a zip, and inside every zip nested in it."""
    handle = io.BytesIO(source) if isinstance(source, bytes) else source
    with zipfile.ZipFile(handle) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            data = z.read(info)
            yield prefix + info.filename, data
            if info.filename.endswith((".jar", ".zip", ".aar")) and data[:2] == b"PK":
                yield from zip_members(data, prefix + info.filename + "!")


def members(spec, places):
    """(path inside the input, bytes) for every file the input holds."""
    how, path = resolve(spec, places)
    if how == "src" and "!" in spec:
        root = spec.split("!", 1)[1].rstrip("/") + "/"
        for inner, data in zip_members(str(path)):
            if inner.startswith(root):
                yield inner[len(root):], data
    elif how == "zip":
        yield from zip_members(str(path))
    elif how == "tar":
        with tarfile.open(path) as tar:
            for info in tar:
                if info.isfile():
                    yield info.name, tar.extractfile(info).read()
    elif how in ("dir", "src"):
        for root, _, files in os.walk(path):
            for name in sorted(files):
                full = Path(root, name)
                yield str(full.relative_to(path)), full.read_bytes()
    else:
        yield path.name, path.read_bytes()


RESOURCE_DIR = re.compile(r"(?:^|!)res/([a-z]+)(?:-[^/]+)?/([^/]+)$")
VALUES_FILE = re.compile(r"(?:^|!)res/values(?:-[^/]+)?/[^/]+\.xml$")
VALUE_TAGS = {"color", "dimen", "string", "integer", "bool", "style", "attr", "plurals",
              "array", "string-array", "integer-array", "fraction", "drawable"}


def resource_names(path, data):
    """(type, name) for every Android resource a file in a library declares.

    An aar's R.txt lists every one, ids made for enum values included, so it is read first;
    the res/ files are read too, which is what covers our own resources."""
    if path.rsplit("!", 1)[-1] == "R.txt":
        for line in data.decode("utf-8", "replace").splitlines():
            m = re.match(r"^int(\[\])? (\w+) (\w+)", line)
            if m and (m.group(2) != "styleable" or m.group(1)):
                # "int[] styleable Name" is a whole attribute group; "int styleable Name_attr" is
                # an index into one, not a resource of its own.
                yield m.group(2), m.group(3)
        return
    if VALUES_FILE.search(path):
        try:
            root = ElementTree.fromstring(data)
        except ElementTree.ParseError:
            return
        for element in root:
            tag, name = element.tag, element.get("name")
            if not name:
                continue
            if tag == "item" and element.get("type"):
                yield element.get("type"), name
            elif tag in VALUE_TAGS:
                yield {"string-array": "array", "integer-array": "array"}.get(tag, tag), name
            elif tag == "declare-styleable":
                yield "styleable", name
                for attr in element.iter("attr"):
                    if attr.get("name") and ":" not in attr.get("name"):
                        yield "attr", attr.get("name")
        return
    match = RESOURCE_DIR.search(path)
    if match:
        kind, file = match.groups()
        yield kind, file.split(".")[0]
        # A layout or drawable written in XML can declare new ids with "@+id/name".
        if file.endswith(".xml"):
            for found in re.findall(rb"@\+id/([A-Za-z0-9_]+)", data):
                yield "id", found.decode()


class Index:
    """The three lookups, each answering with every (origin id, input spec, path) found."""

    def __init__(self):
        self.by_hash = {}
        self.by_class = {}
        self.by_resource = {}
        self.missing = []    # (origin id, spec, path) for inputs not on this machine

    def add(self, origin_id, spec, places):
        how, path = resolve(spec, places)
        if re.search(r"\{\w+\}", str(path)):
            return    # a placeholder this kind of check does not fill: not an input here
        if not path.exists():
            self.missing.append((origin_id, spec, str(path)))
            return
        # A folder named res is a resource folder itself: its files are found as res/<type>/...
        resource_root = "res/" if how == "dir" and path.name == "res" else ""
        for inner, data in members(spec, places):
            where = (origin_id, spec, inner)
            self.by_hash.setdefault(hashlib.sha256(data).hexdigest(), []).append(where)
            name = inner.rsplit("!", 1)[-1]
            if name == "AndroidManifest.xml" and "!" not in inner:
                # The build writes each library an R class in the package its manifest names,
                # holding the ids of that library's resources.
                package = re.search(rb'package="([\w.]+)"', data)
                if package:
                    self.by_class.setdefault(package.group(1).decode().replace(".", "/") + "/R", []).append(where)
            if how == "src" and name.endswith(".java"):
                self.by_class.setdefault(name[:-len(".java")], []).append(where)
            elif name.endswith(".class") and not name.startswith("META-INF/"):
                self.by_class.setdefault(name[:-len(".class")], []).append(where)
            for resource in resource_names(resource_root + inner, data):
                self.by_resource.setdefault(resource, []).append(where)

    def find(self, digest):
        return self.by_hash.get(digest, [])

    def find_class(self, name):
        """A class by its internal name (a/b/C). Nested and compiler-written classes are found
        through the top-level class they belong to, which is how a source file is found too."""
        if name in self.by_class:
            return self.by_class[name]
        outer = name.split("$", 1)[0]
        return self.by_class.get(outer, [])

    def find_resource(self, kind, name):
        return self.by_resource.get((kind, name), [])
