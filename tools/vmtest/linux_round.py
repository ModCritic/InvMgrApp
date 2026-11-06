#!/usr/bin/env python3
"""Runs the whole test round for InvMgr's Linux downloads, one VM at a time.

    python3 linux_round.py <folder with the four downloads> [vm ...]

For each VM (debian-testing, ubuntu-26.04 and fedora-45 unless some are named), in turn: start
it, log in, and run feature_test.py on the .tar.gz, on the AppImage, and on that distribution's own
package launched from the app menu; then remove the package and check that its menu entry and its
folder are gone, and shut the VM down before the next one starts. Each run's screenshots and logs
go to share/runs/<round>/<vm>-<format>/, and a table of the results to share/runs/<round>/SUMMARY.txt.

It refuses to start while any VM is running, and stops rather than start the next VM if one will
not shut down. Before the first VM starts it copies linux-guest/ into the share (vmguest.py). The
test account's password, which the login screen and sudo need, is read from the file
VM_PASSWORD_FILE names, by default ~/.config/invmgr/vm-password, kept outside this repository.
"""
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

import vmguest

TOOLS = Path(__file__).resolve().parent
SHARE = Path(vmguest.SHARE)
VMCTL = TOOLS / "vmctl.py"
PASSWORD_FILE = Path(os.environ.get("VM_PASSWORD_FILE", Path.home() / ".config/invmgr/vm-password"))
VMS = {"debian-testing": "deb", "ubuntu-26.04": "deb", "fedora-45": "rpm"}
INSTALL = {"deb": "apt-get install -y {}", "rpm": "dnf install -y --disablerepo='*' {}"}
REMOVE = {"deb": "apt-get remove -y invmgr", "rpm": "dnf remove -y --disablerepo='*' invmgr"}
# The package's install script puts the menu entry in the first folder of $XDG_DATA_DIRS, which
# is /usr/local/share on Fedora, whose package manager runs scripts without that variable.
ENTRIES = "/usr/share/applications/*invmgr* /usr/local/share/applications/*invmgr*"
GUEST_HOME = "/home/tester"


def virsh(*args):
    return subprocess.run(["virsh", "-c", "qemu:///session", *args],
                          check=True, capture_output=True, text=True).stdout.strip()


def vmctl(vm, *args):
    subprocess.run([sys.executable, str(VMCTL), *args], env={**os.environ, "VM": vm},
                   check=True, capture_output=True)


def password():
    try:
        return PASSWORD_FILE.read_text().strip()
    except OSError:
        sys.exit(f"No test account password: put it in {PASSWORD_FILE}, or name another file in VM_PASSWORD_FILE")


def as_root(command):
    """A command for vmguest.run that runs as root through sudo, the password on standard input
    and no prompt printed; pipefail keeps the command's own exit status."""
    return f"set -o pipefail; echo '{password()}' | sudo -S -p '' sh -c \"{command}\" 2>&1 | cat"


def log_in(vm):
    """Waits for the login screen, logs the test account in, and waits for the command runner,
    which starts with the desktop session."""
    time.sleep(60)
    vmctl(vm, "wait", "60")
    vmctl(vm, "key", "ENTER")      # selects the only account on the login screen
    time.sleep(2)
    vmctl(vm, "type", password())
    vmctl(vm, "key", "ENTER")
    vmguest.run("echo ready", timeout=240)
    time.sleep(10)                 # the desktop settles after the session starts


def shut_down(vm):
    virsh("shutdown", vm)
    end = time.time() + 180
    while virsh("domstate", vm) != "shut off":
        if time.time() > end:
            sys.exit(f"{vm} did not shut down within 3 minutes; not starting another VM")
        time.sleep(3)


def feature_test(vm, out, launch):
    """One run of feature_test.py, with the app's data folder cleared first and GNOME's overview
    closed, since the menu launch opens it with a key that would otherwise close it."""
    vmguest.run("pkill -f /bin/InvMgr; rm -rf ~/.local/share/InvMgr ~/invmgr-test-files; true")
    vmctl(vm, "key", "ESC")
    out.mkdir(parents=True)
    out.chmod(0o777)
    result = subprocess.run([sys.executable, str(TOOLS / "feature_test.py"), "vm", str(out), launch],
                            env={**os.environ, "VM": vm}, cwd=TOOLS, capture_output=True, text=True)
    (out / "result.txt").write_text(result.stdout + result.stderr)
    passed = re.search(r"(\d+) of (\d+) passed", result.stdout)
    return passed.group(0) if passed else "did not finish, see result.txt"


def run_vm(vm, kind, downloads, round_dir):
    row = {"vm": vm}
    print(f"{vm}: starting", flush=True)
    virsh("start", vm)
    log_in(vm)
    guest = vmguest.guest_path(str(downloads))
    vmguest.run(f"rm -rf ~/round && mkdir -p ~/round && cp {guest}/* ~/round/")
    tar = next(downloads.glob("*.tar.gz")).name
    appimage = next(downloads.glob("*.AppImage")).name
    package = next(downloads.glob(f"*.{kind}")).name
    vmguest.run(f"cd ~/round && tar -xzf {tar} && chmod +x {appimage}")

    row["tar.gz"] = feature_test(vm, round_dir / f"{vm}-tar", f"{GUEST_HOME}/round/InvMgr/bin/InvMgr")
    print(f"{vm}: .tar.gz {row['tar.gz']}", flush=True)
    row["AppImage"] = feature_test(vm, round_dir / f"{vm}-appimage", f"{GUEST_HOME}/round/{appimage}")
    print(f"{vm}: AppImage {row['AppImage']}", flush=True)

    rc, out = vmguest.run(as_root(INSTALL[kind].format(f"{GUEST_HOME}/round/{package}")), timeout=600)
    (round_dir / f"{vm}-install.txt").write_text(out)
    row[kind] = feature_test(vm, round_dir / f"{vm}-{kind}", "menu:InvMgr") if rc == 0 else "install failed"
    print(f"{vm}: .{kind} {row[kind]}", flush=True)

    vmguest.run("pkill -f /bin/InvMgr; true")
    rc, out = vmguest.run(as_root(REMOVE[kind]), timeout=600)
    _, left = vmguest.run(f"ls -d {ENTRIES} /opt/invmgr 2>/dev/null; true")
    (round_dir / f"{vm}-remove.txt").write_text(out + "\nleft behind:\n" + left)
    row["removal"] = "clean" if rc == 0 and not left.strip() else f"left: {left.split()}"
    print(f"{vm}: removal {row['removal']}", flush=True)
    shut_down(vm)
    return row


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__.split("\n\n")[1])
    downloads = Path(sys.argv[1]).resolve()
    vms = sys.argv[2:] or list(VMS)
    if virsh("list", "--name", "--state-running"):
        sys.exit("A VM is already running; this runs one at a time")
    password()
    vmguest.install_runner()
    round_dir = SHARE / "runs" / f"round-{datetime.now():%Y%m%d-%H%M}"
    staged = SHARE / "downloads" / round_dir.name
    staged.mkdir(parents=True)
    for f in sorted(downloads.iterdir()):
        if f.suffix in (".gz", ".AppImage", ".deb", ".rpm"):
            shutil.copy2(f, staged)
    round_dir.mkdir(parents=True)
    rows = [run_vm(vm, VMS[vm], staged, round_dir) for vm in vms]
    lines = [f"Downloads: {downloads}", ""]
    for row in rows:
        lines.append("  ".join(f"{k}: {v}" for k, v in row.items()))
    (round_dir / "SUMMARY.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\nResults in {round_dir}")


if __name__ == "__main__":
    main()
