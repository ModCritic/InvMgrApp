"""Reading the parts of an APK whose bytes do not survive the build, using Android's own tools.

Three parts of an APK are rewritten on the way in, so their origin cannot be proven by matching
bytes against the libraries they came from:

- the dex files, where Android's compiler D8 recompiles every class into one format;
- the resources, which the resource compiler aapt2 compiles and, in a release build, renames to
  short codes such as res/N3.png;
- AndroidManifest.xml, which the build merges from ours and the libraries' and then compiles.

So these are read back with the same SDK's dexdump and aapt2, and their origin is proven by
name: a class name, a resource's type and name, a component's class.
"""
import re
import subprocess
import tempfile
from pathlib import Path

SDK_TOOLS = Path.home() / ".gluon" / "substrate" / "Android" / "build-tools"


def tool(name):
    """The newest build-tools copy of an Android SDK tool, or None when the SDK is missing."""
    found = sorted(SDK_TOOLS.glob(f"*/{name}"), key=lambda p: [int(x) for x in re.findall(r"\d+", p.parent.name)])
    return found[-1] if found else None


def dex_classes(data):
    """Every class defined in one dex file, as internal names (a/b/C)."""
    dexdump = tool("dexdump")
    if not dexdump:
        raise SystemExit("dexdump not found: install the Android SDK to check an APK")
    with tempfile.NamedTemporaryFile(suffix=".dex") as f:
        f.write(data)
        f.flush()
        out = subprocess.run([str(dexdump), f.name], capture_output=True, check=True).stdout.decode("utf-8", "replace")
    return sorted({m.group(1) for m in re.finditer(r"Class descriptor\s*:\s*'L([^;]+);'", out)})


def aapt2(*args):
    path = tool("aapt2")
    if not path:
        raise SystemExit("aapt2 not found: install the Android SDK to check an APK")
    return subprocess.run([str(path), *args], capture_output=True, text=True, check=True).stdout


def resources(apk):
    """The APK's resource table: every (type, name), and for file resources the path each
    configuration's file has inside the APK."""
    entries, files = [], {}
    current = None
    for line in aapt2("dump", "resources", str(apk)).splitlines():
        m = re.match(r"\s+resource 0x[0-9a-f]+ ([\w.-]+)/(\S+)", line)
        if m:
            current = (m.group(1), m.group(2))
            entries.append(current)
            continue
        m = re.search(r"\(file\) (\S+)", line)
        if m and current:
            files[m.group(1)] = current
    return entries, files


def manifest(apk):
    """From the compiled AndroidManifest.xml: the class named by every application component
    (activities, services, receivers, providers, the application itself) and every permission
    the app asks for."""
    out = aapt2("dump", "xmltree", "--file", "AndroidManifest.xml", str(apk))
    classes, permissions = set(), set()
    element = None
    for line in out.splitlines():
        m = re.match(r"\s*E: ([\w-]+)", line)
        if m:
            element = m.group(1)
            continue
        m = re.match(r'\s*A: http://schemas.android.com/apk/res/android:name\(0x[0-9a-f]+\)="([^"]+)"', line)
        if m and element in ("application", "activity", "activity-alias", "service", "receiver", "provider"):
            classes.add(m.group(1))
        elif m and element in ("uses-permission", "permission"):
            permissions.add(m.group(1))
    return classes, permissions


# An Android App Bundle (.aab) is what Google Play receives; Play builds the APKs phones install
# from it. Its files sit under base/ and its resources keep their real paths, but its resource
# table and manifest are in a protocol buffer format (protobuf, Google's binary encoding) that
# aapt2 does not read. bundletool, Google's own tool for bundles, does.

BUNDLETOOL = Path.home() / ".local" / "share" / "bundletool"


def bundletool(*args):
    jars = sorted(BUNDLETOOL.glob("bundletool-all-*.jar"))
    if not jars:
        raise SystemExit(f"bundletool not found in {BUNDLETOOL}: it is needed to check an .aab")
    return subprocess.run(["java", "-jar", str(jars[-1]), *args], capture_output=True, text=True, check=True).stdout


def bundle_resources(aab):
    """Every (type, name) in the bundle's resource table."""
    out = bundletool("dump", "resources", f"--bundle={aab}")
    return [tuple(m.groups()) for m in re.finditer(r"^0x[0-9a-f]+ - ([\w.-]+)/(\S+)$", out, re.M)]


def bundle_manifest(aab):
    """The classes and permissions the bundle's manifest names, as manifest() does for an APK."""
    out = bundletool("dump", "manifest", f"--bundle={aab}")
    classes, permissions = set(), set()
    for m in re.finditer(r"<(application|activity|activity-alias|service|receiver|provider|uses-permission|permission)\b[^>]*?android:name=\"([^\"]+)\"", out):
        (permissions if "permission" in m.group(1) else classes).add(m.group(2))
    return classes, permissions


def protobuf_fields(data):
    """(field number, wire type, value) for each field of one protobuf message: enough of the
    format to read the bundle's dependency list without a protobuf library."""
    i = 0
    while i < len(data):
        key, i = varint(data, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(data, i)
        elif wire == 2:
            length, i = varint(data, i)
            value, i = data[i:i + length], i + length
        elif wire == 5:
            value, i = data[i:i + 4], i + 4
        elif wire == 1:
            value, i = data[i:i + 8], i + 8
        else:
            raise ValueError(f"protobuf wire type {wire} is not supported")
        yield number, wire, value


def varint(data, i):
    shift = result = 0
    while True:
        byte = data[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def bundle_dependencies(data):
    """group:artifact:version for every library the build records in
    BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb. Each one is a nested
    message whose fields 1, 2 and 5 are the group, artifact and version."""
    found = set()

    def walk(message, depth):
        try:
            fields = list(protobuf_fields(message))
        except (ValueError, IndexError):
            return
        strings = {n: v for n, w, v in fields if w == 2}
        try:
            group, artifact, version = (strings[k].decode() for k in (1, 2, 5))
            if re.fullmatch(r"[\w.-]+", group) and re.fullmatch(r"[\w.-]+", artifact) and re.fullmatch(r"[\w.+-]+", version):
                found.add(f"{group}:{artifact}:{version}")
                return
        except (KeyError, UnicodeDecodeError):
            pass
        if depth < 6:
            for n, w, v in fields:
                if w == 2 and len(v) > 2:
                    walk(v, depth + 1)

    walk(data, 0)
    return found
