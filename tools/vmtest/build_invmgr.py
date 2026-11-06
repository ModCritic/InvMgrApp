#!/usr/bin/env python3
"""Builds InvMgr's Windows installer in the tiny10 VM, twice, and keeps it only if both match.

    python3 build_invmgr.py <InvMgrApp checkout> <invmgr-<version>.jar> <out folder on this machine>

It copies the checkout's Windows build files (tools/installers/build-windows.cmd, invmgr.iss,
cacerts.md, assets/icons/InvMgr.ico) and the jar into the share, starts the VM if it is off, and
runs build-windows.cmd through the guest agent into two fresh folders on C:. The two setups have to
be byte-identical: with Temurin and Inno Setup pinned, that is the proof the license check rests
on, since nothing on Linux can open a current Inno Setup file. The setup and the app folder it was
made from land in the out folder, with build.log. Last it runs the checkout's license check on
the setup, which stops the script on a failure: CLASSES names the compiled classes that match the
jar (default the checkout's target/classes), and INSTALLERS_RELEASE, when set, the release folder
whose source zips the check also proves. It shuts the VM down if it started it.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

import winguest

HERE = Path(__file__).resolve().parent
STAGE = Path(winguest.SHARE) / "invmgr-build"
VMCTL = HERE / "vmctl.py"


def virsh(*args):
    return subprocess.run(["virsh", "-c", "qemu:///session", *args],
                          check=True, capture_output=True, text=True).stdout.strip()


def ga(*argv):
    """Runs a program in Windows and returns its output; stops on a non-zero exit."""
    out = subprocess.run([sys.executable, str(VMCTL), "ga", *argv], env={**os.environ, "VM": "tiny10"},
                         capture_output=True, text=True).stdout
    code = re.search(r"\[exit (-?\d+)\]\s*$", out)
    if not code or code.group(1) != "0":
        sys.exit(f"Windows command failed: {' '.join(argv)}\n{out}")
    return out


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__.split("\n\n")[1])
    repo, jar, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve()
    version = re.fullmatch(r"invmgr-(.+)\.jar", jar.name).group(1)
    if STAGE.exists():
        shutil.rmtree(STAGE)
    for rel in ("tools/installers/build-windows.cmd", "tools/installers/invmgr.iss",
                "tools/installers/cacerts.md", "assets/icons/InvMgr.ico"):
        (STAGE / "repo" / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(repo / rel, STAGE / "repo" / rel)
    shutil.copy2(jar, STAGE / jar.name)
    started = virsh("domstate", "tiny10") != "running"
    if started:
        if virsh("list", "--name", "--state-running"):
            sys.exit("Another VM is running; one at a time")
        virsh("start", "tiny10")
        end = time.time() + 240
        while True:
            try:
                ga("cmd", "/c", "ver")
                break
            except SystemExit:
                if time.time() > end:
                    raise
                time.sleep(5)
    log = []
    for run in ("one", "two"):
        log.append(ga("cmd", "/c", rf"Z:\invmgr-build\repo\tools\installers\build-windows.cmd "
                                   rf"Z:\invmgr-build\{jar.name} {version} C:\invmgr-out\{run}"))
    setup = f"InvMgr-{version}-windows-x64-setup.exe"
    hashes = ga("powershell", "-NoProfile", "-Command",
                rf"(Get-FileHash C:\invmgr-out\one\{setup}).Hash; (Get-FileHash C:\invmgr-out\two\{setup}).Hash").split()
    if hashes[0] != hashes[1]:
        sys.exit(f"The two builds differ: {hashes[0]} and {hashes[1]}")
    # robocopy's exit codes below 8 all mean success; 1 is "files were copied".
    ga("cmd", "/c", r"robocopy C:\invmgr-out\one Z:\invmgr-build\result /E /NFL /NDL /NJH /NJS /NP"
                    r" & if errorlevel 8 (exit 1) else (exit 0)")
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(STAGE / "result", out)
    (out / "build.log").write_text("\n".join(log) + f"\nboth builds: {hashes[0]}\n")
    if sha256(out / setup).upper() != hashes[0]:
        sys.exit("The setup changed on its way out of the VM")
    print(f"{out / setup}\nboth builds: {hashes[0].lower()}")
    if started:
        virsh("shutdown", "tiny10")
    check = [sys.executable, str(repo / "tools/licenses/check.py"), "windows", str(out / setup),
             "--classes", os.environ.get("CLASSES", str(repo / "target/classes")), "--repo", str(repo)]
    if os.environ.get("INSTALLERS_RELEASE"):
        check += ["--release", os.environ["INSTALLERS_RELEASE"], "--version", version]
    result = subprocess.run(check, capture_output=True, text=True)
    (out / "license-check.txt").write_text(result.stdout + result.stderr)
    if result.returncode != 0:
        sys.exit(f"License check FAILED; the whole report is {out / 'license-check.txt'}")
    print("License check passed" + (", sources included" if os.environ.get("INSTALLERS_RELEASE") else ""))


if __name__ == "__main__":
    main()
