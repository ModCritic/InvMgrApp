"""Opening InvMgr's four Linux downloads so their files can be proven like a jar's.

All four carry the same app folder that jpackage makes: bin/InvMgr (jpackage's launcher),
lib/app (our jar), and lib/runtime (a Java runtime cut from Eclipse Temurin by jlink). They differ
in what is wrapped around it:

  .tar.gz    the folder and nothing else
  .AppImage  the AppImage runtime, a small program that mounts the rest, then a squashfs image of
             a folder holding the app folder, AppRun, a menu entry and the icon
  .deb       an ar archive of debian-binary, control.tar.* (the package's description and the
             scripts dpkg runs) and data.tar.* (the files it installs)
  .rpm       a header (the package's description, its file list and the scripts rpm runs) and a
             cpio archive of the files it installs

unpack() turns each into an ordinary folder of files, laid out as the download holds them, so the
check can walk it the way it walks a jar. The runtime folder cannot be matched against Temurin's
own files, because jlink writes lib/modules, the class library's one big file, fresh for the
modules it is given. So runtime() runs jlink again on Temurin's module files and the check matches
against what it writes. jlink gives the same bytes every time (checked 2026-11-05).
"""
import io
import os
import re
import struct
import subprocess
import tarfile
import zipfile
from pathlib import Path

# jpackage runs jlink with these options unless told otherwise. Read from `jpackage --verbose` on
# Temurin 17.0.21+9, 2026-11-05. A build that passes --jlink-options makes a runtime this one does
# not match, and the check then fails rather than passing something it did not prove.
JLINK_OPTIONS = ["--strip-native-commands", "--strip-debug", "--no-man-pages", "--no-header-files"]

# The scripts an rpm header can hold, by the spec file section that writes each.
RPM_SCRIPTS = {"PRETRANS": "%pretrans", "PREIN": "%pre", "POSTIN": "%post",
               "PREUN": "%preun", "POSTUN": "%postun", "POSTTRANS": "%posttrans"}


def format_of(path):
    with open(path, "rb") as f:
        head = f.read(16)
    if head[:2] == b"\x1f\x8b":
        return "tar.gz"
    if head[:4] == b"\x7fELF" and head[8:11] == b"AI\x02":
        return "AppImage"
    if head[:8] == b"!<arch>\n":
        return "deb"
    if head[:4] == b"\xed\xab\xee\xdb":
        return "rpm"
    raise SystemExit(f"{path}: not a .tar.gz, AppImage, .deb or .rpm")


def unpack(download, into):
    """Unpacks a download into the folder `into` and says which format it was."""
    kind = format_of(download)
    into = Path(into)
    into.mkdir(parents=True, exist_ok=True)
    if kind == "tar.gz":
        # The "tar" filter, unlike "data", writes a link that points out of the folder, so the
        # check can report it as a finding rather than stop on it.
        with tarfile.open(download) as tar:
            tar.extractall(into, filter="tar")
    elif kind == "AppImage":
        unpack_appimage(Path(download).read_bytes(), into)
    elif kind == "deb":
        for name, data in ar_members(Path(download).read_bytes()):
            if name.startswith(("control.tar", "data.tar")):
                with tarfile.open(fileobj=io.BytesIO(data)) as tar:
                    tar.extractall(into / name, filter="tar")
            else:
                (into / name).write_bytes(data)
    else:
        unpack_rpm(download, into)
    return kind


def ar_members(data):
    """(name, bytes) for each member of an ar archive, the container a .deb is."""
    i = 8
    while i < len(data):
        name = data[i:i + 16].decode().strip().rstrip("/")
        size = int(data[i + 48:i + 58].decode().strip())
        yield name, data[i + 60:i + 60 + size]
        i += 60 + size + (size & 1)


def elf_sections(data):
    """{name: (offset, size)} and the byte where the ELF file itself ends, for a 64-bit ELF."""
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    headers = [struct.unpack_from("<IIQQQQ", data, shoff + i * shentsize) for i in range(shnum)]
    strtab = headers[shstrndx]
    names = data[strtab[4]:strtab[4] + strtab[5]]
    found = {names[h[0]:names.index(b"\0", h[0])].decode(): (h[4], h[5]) for h in headers[1:]}
    return found, shoff + shnum * shentsize


def unpack_appimage(data, into):
    """The runtime is the ELF program at the front; the squashfs image starts where it ends, the
    same place the runtime itself looks. appimagetool writes an MD5 of the image into the
    runtime's .digest_md5 section, so the runtime is written out with that section blanked, which
    is the file as AppImage released it, and the 16 bytes go in a file of their own."""
    sections, end = elf_sections(data)
    offset, size = sections[".digest_md5"]
    (into / "runtime").write_bytes(data[:offset] + bytes(size) + data[offset + size:end])
    (into / "runtime.digest_md5").write_bytes(data[offset:offset + size])
    image = into / "image.squashfs"
    image.write_bytes(data[end:])
    # 7-Zip reads squashfs. -snl keeps symbolic links as links; -snld lets it write links that
    # climb a folder, which the runtime's legal folder is full of (../java.base/LICENSE).
    subprocess.run(["7z", "x", "-snl", "-snld", f"-o{into / 'squashfs'}", str(image)],
                   check=True, capture_output=True)
    image.unlink()


def unpack_rpm(download, into):
    """The installed files come out of the payload with rpm2cpio and bsdtar. The header's scripts,
    the only code in it, are read with rpm's own query and written one file per script."""
    payload = into / "payload"
    payload.mkdir()
    cpio = subprocess.run(["rpm2cpio", str(download)], check=True, capture_output=True).stdout
    subprocess.run(["bsdtar", "-x", "-f", "-", "-C", str(payload)], input=cpio, check=True)
    scripts = into / "scripts"
    for tag, section in RPM_SCRIPTS.items():
        text = subprocess.run(["rpm", "-qp", "--qf", f"%{{{tag}}}", str(download)],
                              capture_output=True, text=True, check=True).stdout
        if text != "(none)":
            scripts.mkdir(exist_ok=True)
            (scripts / section).write_text(text)


def shipped_runtime(folder):
    """The runtime folder inside an unpacked download, found by the release file jlink writes."""
    found = sorted(p.parent for p in Path(folder).rglob("lib/runtime/release"))
    if len(found) != 1:
        raise SystemExit(f"expected one Java runtime in the download, found {len(found)}")
    return found[0]


def runtime_modules(runtime):
    """The modules a runtime holds, from the MODULES line of its release file."""
    text = (runtime / "release").read_text()
    match = re.search(r'^MODULES="([^"]*)"', text, re.M)
    if not match:
        raise SystemExit(f"{runtime}/release names no modules")
    return match.group(1).split()


def runtime(shipped, temurin, into):
    """Runs jlink on Temurin's own module files for the modules the shipped runtime names, with
    jpackage's options, and returns the folder it wrote."""
    out = Path(into) / "runtime"
    subprocess.run([f"{temurin}/bin/jlink", "--output", str(out), "--module-path", f"{temurin}/jmods",
                    "--add-modules", ",".join(runtime_modules(shipped)), *JLINK_OPTIONS],
                   check=True, capture_output=True)
    return out


def class_counts(runtime, temurin):
    """{module: entries} inside a runtime's lib/modules, as Temurin's jimage lists them."""
    listing = subprocess.run([f"{temurin}/bin/jimage", "list", str(runtime / "lib" / "modules")],
                             check=True, capture_output=True, text=True).stdout
    counts, module = {}, None
    for line in listing.splitlines():
        if line.startswith("Module: "):
            module = line.split()[1]
        elif module and line.startswith("    "):
            counts[module] = counts.get(module, 0) + 1
    return counts


def template_mismatch(data, rule, places):
    """None when a file is a template with each capitalized placeholder filled in with one line,
    else a reason. The template is a file, or a member of a zip such as jpackage's module
    ("archive!member"). For a script inside an rpm header, `section` names the part of
    template.spec it comes from."""
    from inputs import expand
    archive, _, member = expand(rule["template"], places).partition("!")
    template = (zipfile.ZipFile(archive).read(member) if member else Path(archive).read_bytes()).decode()
    member = member or archive
    if rule.get("section"):
        lines = template.splitlines()
        start = lines.index(rule["section"]) + 1
        end = next((i for i in range(start, len(lines)) if lines[i].startswith("%")), len(lines))
        template = "\n".join(lines[start:end])
    # Blank lines at either end are allowed to differ: a placeholder alone on the last line that
    # fills in empty leaves one, and rpm keeps a script's leading newline.
    pattern = "\n*"
    for piece in re.split(r"((?<![A-Za-z0-9])[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+(?![A-Za-z0-9]))", template.strip("\n")):
        if re.fullmatch(r"[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+", piece):
            pattern += "[^\n]*"
        else:
            pattern += re.escape(piece)
    text = data.decode("utf-8", "replace")
    return None if re.fullmatch(pattern + "\n*", text) else f"it is not {member.rsplit('/', 1)[-1]} with its blanks filled in"
