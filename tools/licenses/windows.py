"""Proving InvMgr's Windows installer, which no tool on Linux can open.

The installer is one setup .exe made by Inno Setup from the app folder jpackage makes on Windows.
Current Inno Setup files are unreadable to innoextract, so the check never looks inside the setup.
It rests on two facts instead. The setup is built twice, separately, from the same pinned tools
and inputs, and kept only when both are byte-identical, with the line "both builds: <SHA-256>" in
build.log beside it; so the setup is exactly what Inno Setup makes of the app folder beside it.
And the app folder itself is proven here, file by file, the way a Linux download is.

Three files in the app folder are written on Windows and so are not byte-identical to anything on
this machine: the launcher InvMgr.exe, which is jpackage's launcher with our icon and version
added to its resources; the runtime's lib/modules, the class library file jlink writes; and its
release file. The launcher is compared with jpackage's own section by section, all but the
resources; lib/modules has to hold only entries Temurin's full class library has.
"""
import hashlib
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path


def prepare(setup, into, places):
    """Copies the setup and the app folder beside it into `into`, and reads build.log for the
    hash both builds produced. The setup's version, from its name, fills {version} if not given."""
    setup = Path(setup).resolve()
    app = setup.parent / "app" / "InvMgr"
    log = setup.parent / "build.log"
    if not app.is_dir() or not log.exists():
        raise SystemExit(f"{setup.parent} needs, beside the setup, the app folder app/InvMgr it was made from and "
                         "a build.log whose line 'both builds: <SHA-256>' records the hash two separate builds both made")
    match = re.search(r"^both builds: ([0-9a-fA-F]{64})$", log.read_text(), re.M)
    if not match:
        raise SystemExit(f"{log} records no matching pair of builds")
    places["setup_built_twice"] = match.group(1).lower()
    version = re.fullmatch(r"InvMgr-(.+)-windows-x64-setup\.exe", setup.name)
    if version and "version" not in places:
        places["version"] = version.group(1)
    into = Path(into)
    into.mkdir(parents=True)
    shutil.copy2(setup, into / setup.name)
    shutil.copytree(app, into / "app" / "InvMgr")
    return into


def pe_sections(data):
    """{section name: its bytes in the file} for a Windows program or library."""
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    count, optional = struct.unpack_from("<H", data, pe + 6)[0], struct.unpack_from("<H", data, pe + 20)[0]
    table = pe + 24 + optional
    found = {}
    for i in range(count):
        entry = table + 40 * i
        name = data[entry:entry + 8].rstrip(b"\0").decode("ascii", "replace")
        size, offset = struct.unpack_from("<II", data, entry + 16)
        found[name] = data[offset:offset + size]
    return found


def launcher_mismatch(data, rule, places):
    """None when every section but .rsrc, the resources, is the same as in the launcher the rule
    names (a member of jpackage's module), else a reason."""
    from inputs import expand
    archive, _, member = expand(rule["pe_like"], places).partition("!")
    original = pe_sections(zipfile.ZipFile(archive).read(member))
    shipped = pe_sections(data)
    if set(original) != set(shipped):
        return f"its sections {sorted(shipped)} are not {member.rsplit('/', 1)[-1]}'s {sorted(original)}"
    differ = [name for name in original if name != ".rsrc" and original[name] != shipped[name]]
    return f"section {differ[0]} differs from {member.rsplit('/', 1)[-1]}'s" if differ else None


def jimage_entries(path, temurin):
    listing = subprocess.run([f"{temurin}/bin/jimage", "list", str(path)],
                             check=True, capture_output=True, text=True).stdout
    entries, module = set(), None
    for line in listing.splitlines():
        if line.startswith("Module: "):
            module = line.split()[1]
        elif module and line.startswith("    "):
            entries.add(f"{module}/{line.strip()}")
    return entries


def jimage_mismatch(data, rule, places):
    """None when every entry of a class library file is one the full class library the rule names
    has, else a reason. Read with this machine's Temurin jimage, which reads Windows' files too."""
    from inputs import expand
    with tempfile.NamedTemporaryFile(suffix=".modules") as f:
        f.write(data)
        f.flush()
        shipped = jimage_entries(f.name, places["temurin"])
    full = jimage_entries(expand(rule["jimage_names_in"], places), places["temurin"])
    extra = sorted(shipped - full)
    if not shipped:
        return "it lists no entries"
    return f"{len(extra)} entries the full class library lacks, the first {extra[0]}" if extra else None


def built_twice_mismatch(data, places):
    digest = hashlib.sha256(data).hexdigest()
    want = places.get("setup_built_twice")
    return None if digest == want else f"its SHA-256 {digest} is not the one both builds made ({want})"
