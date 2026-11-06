"""The host's side of guest-runner.sh: runs a shell command inside a test VM's desktop session,
through the shared folder, and returns its exit code and output.

    python3 vmguest.py 'ls -la ~/.local/share'

The share is the folder the Linux VMs mount at /mnt/share: LINUX_VM_SHARE, by default
~/ClaudeWorkspace/LinuxVMs/share. Each VM runs runner/guest-runner.sh from it at login
(linux-guest/setup-session.sh sets that up once); install_runner puts the guest side there.
Files are written under a temporary name and renamed, so the runner never reads half a command."""
import itertools, os, shutil, sys, time

SHARE = os.environ.get("LINUX_VM_SHARE", os.path.expanduser("~/ClaudeWorkspace/LinuxVMs/share"))
GUEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "linux-guest")
QUEUE = os.path.join(SHARE, "runner", "queue")
GUEST_SHARE = "/mnt/share"
_ids = itertools.count()


def run(command, timeout=60.0):
    name = os.path.join(QUEUE, f"{os.getpid()}-{next(_ids)}")
    with open(name + ".part", "w") as f:
        f.write(command + "\n")
    os.rename(name + ".part", name + ".sh")
    end = time.time() + timeout
    while not os.path.exists(name + ".rc"):
        if time.time() > end:
            raise TimeoutError(f"the VM did not run {command!r} within {timeout} s; is guest-runner.sh going?")
        time.sleep(0.05)
    with open(name + ".rc") as f:
        rc = int(f.read().strip() or "1")
    with open(name + ".out", errors="replace") as f:
        out = f.read()
    for ext in (".run", ".out", ".rc"):
        os.remove(name + ext)
    return rc, out


def install_runner():
    """Copies linux-guest/ into the share's runner folder, where the VMs run it. wm.py there also
    needs python-xlib and six in runner/pylib: the VMs have no network to install them, so a copy
    of each is kept in the share, outside this repository."""
    runner = os.path.join(SHARE, "runner")
    os.makedirs(os.path.join(runner, "queue"), exist_ok=True)
    for name in os.listdir(GUEST):
        shutil.copy2(os.path.join(GUEST, name), os.path.join(runner, name))
    if not os.path.isdir(os.path.join(runner, "pylib", "Xlib")):
        sys.exit(f"{runner}/pylib needs python-xlib (the Xlib folder) and six.py for wm.py")


def guest_path(host_path):
    """Where a file in the host's share folder appears inside the VM."""
    return GUEST_SHARE + os.path.abspath(host_path)[len(SHARE):]


if __name__ == "__main__":
    rc, out = run(" ".join(sys.argv[1:]))
    sys.stdout.write(out)
    sys.exit(rc)
