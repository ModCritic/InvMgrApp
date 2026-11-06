"""The host's side of runner.ps1: runs PowerShell inside the tiny10 VM's desktop session, through
the shared folder, and returns its result and output.

    python3 winguest.py 'Get-Process InvMgr'

The result is 0, or 1 when the command threw. The share is the folder Windows sees as Z:, set by
WINDOWS_VM_SHARE and by default ~/ClaudeWorkspace/WinVM/share. A scheduled task starts
runner\\runner.ps1 from it at every logon (windows-guest/install-runner.ps1 sets that up once), and
install_runner puts the guest side there. Files are written under a temporary name and renamed, so
the runner never reads half a command."""
import itertools, os, shutil, sys, time

SHARE = os.environ.get("WINDOWS_VM_SHARE", os.path.expanduser("~/ClaudeWorkspace/WinVM/share"))
GUEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "windows-guest")
QUEUE = os.path.join(SHARE, "runner", "queue")
GUEST_SHARE = "Z:"
_ids = itertools.count()


def run(command, timeout=60.0):
    name = os.path.join(QUEUE, f"{os.getpid()}-{next(_ids)}")
    with open(name + ".part", "w", encoding="utf-8") as f:
        f.write(command + "\n")
    os.rename(name + ".part", name + ".ps1")
    end = time.time() + timeout
    while not os.path.exists(name + ".rc"):
        if time.time() > end:
            raise TimeoutError(f"the VM did not run {command!r} within {timeout} s; is runner.ps1 going?")
        time.sleep(0.05)
    with open(name + ".rc", encoding="utf-8") as f:
        rc = int(f.read().strip() or "1")
    with open(name + ".out", encoding="utf-8", errors="replace") as f:
        out = f.read()
    for ext in (".run", ".out", ".rc"):
        os.remove(name + ext)
    return rc, out


def install_runner():
    """Copies windows-guest/ into the share's runner folder, where the scheduled task runs it."""
    runner = os.path.join(SHARE, "runner")
    os.makedirs(os.path.join(runner, "queue"), exist_ok=True)
    for name in os.listdir(GUEST):
        shutil.copy2(os.path.join(GUEST, name), os.path.join(runner, name))


def guest_path(host_path):
    """Where a file in the host's share folder appears inside the VM."""
    return GUEST_SHARE + os.path.abspath(host_path)[len(SHARE):].replace("/", "\\")


def quote(text):
    """A PowerShell string literal holding `text` exactly."""
    return "'" + str(text).replace("'", "''") + "'"


if __name__ == "__main__":
    rc, out = run(" ".join(sys.argv[1:]))
    sys.stdout.write(out)
    sys.exit(rc)
