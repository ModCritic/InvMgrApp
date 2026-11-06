#!/usr/bin/env python3
"""Fetches the third-party source InvMgr's installers owe, and zips it for a release.

    python3 tools/installers/installer-sources.py fetch            # into LINUX_SOURCES, checking each file
    python3 tools/installers/installer-sources.py bundle <folder>  # writes InvMgr-<version>-installers-third-party-source.zip

The Linux downloads and the Windows installer carry Eclipse Temurin's Java runtime and launcher
(GPLv2), built from the same commit for both; on Linux it is compiled with GCC. The AppImage carries
the AppImage runtime with libfuse (LGPL-2.1) and five other libraries built in. Those licenses ask
for the source to be offered from the same place as the download, so each release puts this zip
beside the downloads. Inno Setup's license, for the Windows setup program, asks for no source.

Every file is pinned by SHA-256. fetch downloads what is missing and stops on any file whose
bytes are not the pinned ones, the ones already there included. Alpine's build recipes for the
libraries Alpine supplied to the AppImage runtime are not published as one file, so fetch builds
that archive from files fetched at pinned commits, in a fixed order with fixed dates, so it comes
out byte for byte the same each time. tools/licenses/registry.toml pins the same files again, and
the license check finds them inside the zip by those values.

LINUX_SOURCES is where the files are kept, the same folder build-linux.sh copies the AppImage's
license files from.
"""
import gzip
import hashlib
import io
import os
import re
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SOURCES = Path(os.environ.get("LINUX_SOURCES", Path.home() / "ClaudeWorkspace/InvMgr-build/linux-sources"))

# name, where it comes from, SHA-256
FILES = [
    ("jdk17u-jdk-17.0.21+9_adopt-source.tar.gz",
     "https://codeload.github.com/adoptium/jdk17u/tar.gz/refs/tags/jdk-17.0.21%2B9_adopt",
     "4e295d4a3213bcbe52c6d90cbedf28498f58e149fca399926bd425a49bff4c92"),
    ("temurin-build-1cf43d7a-source.tar.gz",
     "https://codeload.github.com/adoptium/temurin-build/tar.gz/1cf43d7a3e77886bfea7d550a902e151a87d724d",
     "f524e42fdb6480934dae49e59b07bd979763dfb413046f73b8cbceefe5b08591"),
    ("gcc-10.3.0.tar.xz",
     "https://ftp.gnu.org/gnu/gcc/gcc-10.3.0/gcc-10.3.0.tar.xz",
     "64f404c1a650f27fc33da242e1f2df54952e3963a49e06e73f6940f3223ac344"),
    ("type2-runtime-dd6cebed-source.tar.gz",
     "https://codeload.github.com/AppImage/type2-runtime/tar.gz/dd6cebedcbddde9c82f89b011e8e1d40b6e43868",
     "f5fec23be76e50e2445ed2d018bac49b367490fc483c62c4637e99ec705d27ba"),
    ("fuse-3.15.0.tar.xz",
     "https://github.com/libfuse/libfuse/releases/download/fuse-3.15.0/fuse-3.15.0.tar.xz",
     "70589cfd5e1cff7ccd6ac91c86c01be340b227285c5e200baa284e401eea2ca0"),
    ("squashfuse-0.5.2.tar.gz",
     "https://github.com/vasi/squashfuse/archive/0.5.2.tar.gz",
     "db0238c5981dabbd80ee09ae15387f390091668ca060a7bc38047912491443d3"),
    ("zstd-1.5.6.tar.gz",
     "https://github.com/facebook/zstd/releases/download/v1.5.6/zstd-1.5.6.tar.gz",
     "8c29e06cf42aacc1eafc4077ae2ec6c6fcb96a626157e0593d5e82a34fd403c1"),
    ("zlib-1.3.1.tar.gz",
     "https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz",
     "9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23"),
    ("musl-1.2.5.tar.gz",
     "https://musl.libc.org/releases/musl-1.2.5.tar.gz",
     "a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4"),
    ("mimalloc-2.1.7-source.tar.gz",
     "https://codeload.github.com/microsoft/mimalloc/tar.gz/refs/tags/v2.1.7",
     "0eed39319f139afde8515010ff59baf24de9e47ea316a315398e8027d198202d"),
]

# Alpine's recipe folders for the four libraries, at the last commit before the runtime was built.
APORTS_NAME = "alpine-3.21-aports-runtime-libs.tar.gz"
APORTS_SHA256 = "a282b99405bddb866016f0bd4711dd312e90a4cdd7c4ce14094892efeea16890"
APORTS = [
    ("main/musl", "efd4d5df7be0ea48d98512671f676fc5ceb14899", [
        "APKBUILD", "CVE-2025-26519.patch", "__stack_chk_fail_local.c", "getconf.c", "getent.c",
        "handle-aux-at_base.patch", "iconv.c", "isatty-glibc-align.patch", "ldconfig",
        "ldso-fix-non-functional-fix-to-early-dynamic-PAGE_SI.patch",
        "loongarch64-Add-lsx-and-lasx-vector-structure-defini.patch",
        "loongarch64-add-TLSDESC-support.patch", "loongarch64-add-bits-hwcap.h.patch",
        "loongarch64-add-fpu-and-simd-context-to-signal.h.patch"]),
    ("community/mimalloc2", "5a1a5dc3216a69e3cd66a57ee07cb5ef01b8be39", [
        "APKBUILD", "cmake-add-insecure-suffix.patch"]),
    ("main/zstd", "5c2ddf18f193dafdd25ae7391d96c7e30766eecd", ["APKBUILD"]),
    ("main/zlib", "4c2722e7ecc1c5e3ba882429044a101497121223", ["APKBUILD"]),
]
# Every archive this script writes is dated the day these sources were pinned.
PINNED = 1793836800   # 2026-11-05 00:00 UTC


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def aports_archive():
    """Alpine's recipe files as one .tar.gz, built the same way every time: files in a fixed
    order, every date the pinning date, no owner names, and no date in the gzip header."""
    buffer = io.BytesIO()
    top = APORTS_NAME[:-len(".tar.gz")].replace("-runtime-libs", "")
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        def add(name, data):
            info = tarfile.TarInfo(f"{top}/{name}")
            info.size, info.mtime, info.mode = len(data), PINNED, 0o644
            tar.addfile(info, io.BytesIO(data))
        add("COMMITS", "".join(f"{folder} {commit}\n" for folder, commit, _ in APORTS).encode())
        for folder, commit, names in APORTS:
            for name in sorted(names):
                add(f"{folder}/{name}", download(
                    f"https://raw.githubusercontent.com/alpinelinux/aports/{commit}/{folder}/{name}"))
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as gz:
        gz.write(buffer.getvalue())
    return out.getvalue()


def fetch():
    SOURCES.mkdir(parents=True, exist_ok=True)
    bad = []
    for name, url, want in FILES + [(APORTS_NAME, None, APORTS_SHA256)]:
        path = SOURCES / name
        if not path.exists():
            print(f"fetching {name}")
            path.write_bytes(aports_archive() if url is None else download(url))
        got = sha256(path)
        print(f"{'ok ' if got == want else 'BAD'} {name}")
        if got != want:
            bad.append(f"{name}: {got}, pinned {want}")
    if bad:
        sys.exit("Files whose bytes are not the pinned ones:\n  " + "\n  ".join(bad))


def version():
    # The project's own version: the first <version> after <artifactId>invmgr</artifactId>.
    pom = (REPO / "pom.xml").read_text()
    match = re.search(r"<artifactId>invmgr</artifactId>.*?<version>([^<]+)</version>", pom, re.S)
    if not match:
        sys.exit("Cannot read the version from pom.xml")
    return match.group(1)


def bundle(folder):
    v = version()
    top = f"InvMgr-{v}-installers-third-party-source"
    names = [name for name, _, _ in FILES] + [APORTS_NAME]
    for name in names:
        if not (SOURCES / name).exists():
            sys.exit(f"{SOURCES / name} is missing: run fetch first")
    readme = (HERE / "installer-source-README.txt").read_text().replace("{version}", v)
    sums = "".join(f"{sha256(SOURCES / name)}  {name}\n" for name in names)
    out = Path(folder) / f"{top}.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    when = (2026, 11, 5, 0, 0, 0)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        def add(name, data):
            info = zipfile.ZipInfo(f"{top}/{name}", when)
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
        add("README.txt", readme.encode())
        for name in names:
            add(name, (SOURCES / name).read_bytes())
        add("SHA256SUMS", sums.encode())
    print(out)


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "fetch":
        fetch()
    elif len(sys.argv) == 3 and sys.argv[1] == "bundle":
        bundle(sys.argv[2])
    else:
        sys.exit(__doc__.split("\n\n")[1])


if __name__ == "__main__":
    main()
