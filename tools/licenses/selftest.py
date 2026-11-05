#!/usr/bin/env python3
"""Breaks the license check on purpose, once for every kind of failure, and confirms it fails.

    python3 tools/licenses/selftest.py

A check that has only ever been seen passing has not been tested. Each case below makes one
thing wrong (a file nobody recorded, a library missing from the registry, a Windows DLL whose
maker its origin does not list, an obligation nobody meets) in a copy, never in the real files,
and requires check.py to exit 1 with the message that names that fault. It also requires the
real registry to pass where it should, so a case cannot succeed by the check failing for some
unrelated reason.

It needs a release's files on this machine; the paths below are 1.0.1's, and can be changed
with the environment variables named beside them.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
HOME = Path.home()
RELEASE = Path(os.environ.get("INVMGR_RELEASE", HOME / "ClaudeWorkspace/InvMgr-release-1.0.1"))
CLONE = Path(os.environ.get("INVMGR_CLONE", HOME / "ClaudeWorkspace/InvMgr-public-clone-1.0.1"))
JAR = RELEASE / "invmgr-1.0.1.jar"
APK = REPO / "target/release/InvMgr-1.0.1-2.apk"
AAB = REPO / "target/release/InvMgr-1.0.1-2.aab"
# 1.0.1 was built before maps were kept; these copies sit beside its release (see their README.txt).
BOM = RELEASE / "InvMgr-1.0.1-2-bom-approximate"
MAP = Path(os.environ.get("INVMGR_LINK_MAP", BOM / "libsubstrate.map"))
LINKED = Path(os.environ.get("INVMGR_LINKED", BOM / "libInvMgr.so"))
COMPILE_LOG = Path(os.environ.get("INVMGR_COMPILE_LOG", BOM / "compile.log"))
REGISTRY = (HERE / "registry.toml").read_text()


def run(*args):
    result = subprocess.run([sys.executable, str(HERE / "check.py"), *map(str, args)],
                            capture_output=True, text=True)
    return result.returncode, result.stdout + result.stderr


def without_origin(text, origin_id):
    start = text.index(f'[[origin]]\nid = "{origin_id}"')
    end = text.find("\n[[origin]]", start + 10)
    return text[:start] + (text[end + 1:] if end >= 0 else "")


def without_component(text, legal_file):
    i = text.index(f'legal_file = "{legal_file}"')
    start = text.rindex("  [[origin.component]]", 0, i)
    end = text.index("  [[origin.component]]", i)
    return text[:start] + text[end:]


def main():
    work = Path(tempfile.mkdtemp(prefix="license-selftest-"))
    results = []

    def case(name, expect, args, registry=None):
        if registry is not None:
            path = work / f"{name}.toml"
            path.write_text(registry)
            args = [*args, "--registry", path]
        code, out = run(*args)
        ok = code == 1 and re.search(expect, out) is not None
        results.append((ok, name, expect if ok else f"expected /{expect}/, exit {code}"))

    def passes(name, unwanted, args):
        code, out = run(*args)
        bad = [u for u in unwanted if re.search(u, out)]
        results.append((not bad, name, "clean" if not bad else f"found {bad}"))

    jar_args = ["jar", JAR, "--classes", CLONE / "target/classes", "--repo", CLONE]

    # The real registry proves every leaf; only obligations may fail it.
    passes("jar: every file has one origin", [r"NO PROVEN ORIGIN", r"CLAIMED BY MORE"], jar_args)

    # A file nobody recorded.
    evil = work / "evil.jar"
    shutil.copy(JAR, evil)
    with zipfile.ZipFile(evil, "a") as z:
        z.writestr("com/evil/Payload.class", b"\xca\xfe\xba\xbe" + os.urandom(64))
    case("jar: unknown file", r"com/evil/Payload\.class.*match no input", ["jar", evil, *jar_args[2:]])

    # A Windows DLL by another maker, once its own origin is gone.
    case("jar: DLL maker not recorded", r"made by 'Microsoft Corporation'", jar_args,
         without_origin(REGISTRY, "microsoft-vc-runtime"))

    # An obligation nothing meets.
    case("jar: unmet obligation", r"license-text obligation not met", jar_args,
         REGISTRY.replace('met_by = ["artifact:com/modcritic/invmgr/ui/fonts/OFL.txt", "repo:',
                          'met_by = ["artifact:com/modcritic/invmgr/ui/fonts/NOT-THERE.txt", "repo:', 1))

    # A legal file in an origin's source with no component.
    case("jar: legal file not recorded", r"jpeg_fx\.md, and no component records it",
         [*jar_args, "--release", RELEASE, "--version", "1.0.1"],
         REGISTRY.replace('id = "openjfx-desktop"', 'id = "openjfx-desktop"\nlegal_files = "{release}/openjfx-21-b31-source.tar.gz!jfx-21-31/modules/javafx.graphics/src/main/legal/*.md"', 1)
         .replace('  legal_file = "jpeg_fx.md"\n  in = ["*javafx_iio.dll"', '  in = ["*javafx_iio.dll"', 1))

    if APK.exists() and MAP and MAP.exists():
        apk_args = ["apk", APK, "--repo", CLONE, "--build", REPO / "target", "--native-map", MAP,
                    "--linked", LINKED, "--compile-log", COMPILE_LOG, "--approximate"]
        # Every file has one origin, apart from the one finding the 1.0.1 build really has:
        # Gluon Substrate's own icons and config files, which native-image embedded because
        # Substrate's jar is on its classpath. The next case requires the check to report them.
        passes("apk: every file has one origin, apart from the known Substrate resources",
               [r"(?m)^  (?!native-image resource ).*: (its bytes match|no input|no origin)", r"CLAIMED BY MORE"], apk_args)
        case("apk: Substrate's resources embedded in the library", r"must put nothing in the app", apk_args)
        case("apk: library missing from the registry", r"class androidx/\S+: no input defines this class", apk_args,
             without_origin(REGISTRY, "androidx"))
        case("apk: origin that must never ship", r"must never ship", apk_args,
             REGISTRY.replace('id = "kotlin"\n', 'id = "kotlin"\nallowed = false\n', 1))
        case("apk: license never read", r"has not been read from a file", apk_args,
             REGISTRY.replace('id = "invmgr-icon"\n', 'id = "invmgr-icon"\nlicense_status = "unverified"\n', 1))
        bad_map = work / "extra-input.map"
        lines = MAP.read_text().splitlines()
        at = next(i for i, line in enumerate(lines) if re.search(r"^\s+[0-9a-f]+\s+[0-9a-f]+\s+[0-9a-f]+\s+\d+ \.text$", line))
        lines.insert(at + 1, "             1000             1000      100     4         /somewhere/libsurprise.a(hidden.o):(.text)")
        bad_map.write_text("\n".join(lines) + "\n")
        case("apk: linker input nobody recorded", r"libsurprise\.a\(hidden\.o\).*native_inputs",
             [a if a != MAP else bad_map for a in apk_args])
        case("apk: map from another build, not accepted", r"is not this library", apk_args[:-1])
        case("aab: library in the bundle's own list, not in the registry",
             r"lists com\.google\.guava:listenablefuture:1\.0.*no origin",
             ["aab", AAB, *apk_args[2:]], without_origin(REGISTRY, "guava-listenablefuture"))
    else:
        results.append((False, "apk and aab cases", f"skipped: no link map at {MAP}"))

    # The published repository, through a throwaway clone so the real one is never touched.
    clone = work / "repo"
    subprocess.run(["git", "clone", "-q", "--shared", "--branch", "public", str(REPO), str(clone)], check=True)
    foreign = clone / "src/main/java/com/modcritic/invmgr/Pasted.java"
    foreign.write_text("/* Copyright (c) 2019 Someone Else. Licensed under the MIT License. */\nclass Pasted {}\n")
    (clone / "vendor").mkdir()
    (clone / "vendor/thing.js").write_text("var x = 1;\n")
    subprocess.run(["git", "-C", str(clone), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(clone), "-c", "user.name=selftest", "-c", "user.email=selftest@invalid",
                    "commit", "-q", "-m", "selftest"], check=True)
    case("repo: someone else's copyright line in our file", r"Pasted\.java:1.*license or copyright line",
         ["repo", "HEAD", "--repo", clone])
    case("repo: file no origin claims", r"vendor/thing\.js: no origin's repo_paths", ["repo", "HEAD", "--repo", clone])

    shutil.rmtree(work)
    for ok, name, detail in results:
        print(f"{'pass' if ok else 'FAIL'}  {name}: {detail}")
    failed = [r for r in results if not r[0]]
    print(f"\n{len(results) - len(failed)} of {len(results)} cases behaved as they must.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
