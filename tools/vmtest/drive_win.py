"""Drives InvMgr inside the tiny10 Windows VM the way a person at its screen would, for
feature_test.py ("win" as its first argument). Clicks, keys and screenshots go through QEMU exactly
as drive_vm.py sends them to the Linux VMs, so this borrows those. Starting the app, reading its
files and finding its windows happen inside Windows through runner.ps1 (see winguest.py).

The VM's screen has to be 2560x1440 at 100% scaling (windows-guest/display.ps1 sets it), and OUTDIR
has to be inside the share, which Windows sees as Z:.
"""
import json, os, time

os.environ.setdefault("VM", "tiny10")
import drive_vm
import winguest
from winguest import quote

click, double_click, drag, hold, key, look, move, pause, type_text, shot = (
    drive_vm.click, drive_vm.double_click, drive_vm.drag, drive_vm.hold, drive_vm.key, drive_vm.look,
    drive_vm.move, drive_vm.pause, drive_vm.type_text, drive_vm.shot)

HOME = r"C:\Users\tester"
DATA = HOME + r"\AppData\Roaming\InvMgr"
INSTALLED = HOME + r"\AppData\Local\Programs\InvMgr\InvMgr.exe"
# The app's windows: InvMgr.exe starts a second copy of itself, and either may own them.
APP_WINDOWS = "[Win]::List() | ? { (Get-Process -Id $_.Pid -ErrorAction SilentlyContinue).ProcessName -eq 'InvMgr' }"


def _run(command, timeout=60.0):
    rc, out = winguest.run(command, timeout)
    if rc != 0:
        raise RuntimeError(f"Windows command failed: {command}\n{out}")
    return out


def _windows():
    return json.loads(_run(f"ConvertTo-Json -Compress -InputObject @({APP_WINDOWS})") or "[]")


def app_titles():
    return [w["Title"] for w in _windows() if w["Title"] == "InvMgr"]


def dialog_titles():
    """Every titled window of the app's other than its main one: a file dialog, or a message box."""
    return sorted({w["Title"] for w in _windows() if w["Title"] != "InvMgr"})


def files_dir(out):
    """Where Save and Export are pointed: a folder in the test user's home, made fresh."""
    path = HOME + r"\invmgr-test-files"
    _run(f"Remove-Item -Recurse -Force -LiteralPath {quote(path)} -ErrorAction SilentlyContinue; "
         f"New-Item -ItemType Directory {quote(path)} | Out-Null")
    return path


def start(launch, log_path):
    """Starts the app at the path LAUNCH names, from the test user's home, with what it prints
    going to log_path and log_path's .err beside it."""
    exe, args = launch[0], launch[1:]
    guest_log = winguest.guest_path(log_path)
    arg_list = f"-ArgumentList {', '.join(quote(a) for a in args)} " if args else ""
    _run(f"Start-Process -FilePath {quote(exe)} {arg_list}-WorkingDirectory {quote(HOME)} "
         f"-RedirectStandardOutput {quote(guest_log)} -RedirectStandardError {quote(guest_log + '.err')}")
    return {"launch": launch, "log": log_path}


def log_text(handle):
    text = ""
    for path in (handle["log"], handle["log"] + ".err"):
        try:
            with open(path, errors="replace") as f:
                text += f.read()
        except OSError:
            pass
    return text


def fill_screen():
    """Takes the main window's frame away and makes it cover the screen, as full screen does on
    Linux, and brings it to the front."""
    _run(f"$w = @({APP_WINDOWS}) | ? Title -eq 'InvMgr' | Select -First 1; [Win]::FillScreen([IntPtr]$w.Handle)")


def into_dialog():
    """Windows gives a new dialog the keyboard by itself."""


def into_open_dialog():
    """As into_dialog."""


def back_to_app():
    """As into_dialog: closing a dialog hands the keyboard back to the app."""


def _dialog_path(path):
    """Types a whole path into a Windows file dialog's File name box, which has the keyboard when
    the dialog opens, once the dialog is up, and presses Return."""
    end = time.time() + 15
    while not dialog_titles():
        if time.time() > end:
            return False
        time.sleep(0.3)
    pause(1.0)
    key("a", 0.1, ("Control_L",))
    type_text(path.replace("/", "\\"), 0.02)
    key("Return", 0.5)
    end = time.time() + 15
    while dialog_titles():
        if time.time() > end:
            return False
        time.sleep(0.3)
    return True


save_to = _dialog_path
open_file = _dialog_path


def running(_handle=None):
    return _run("@(Get-Process InvMgr -ErrorAction SilentlyContinue).Count").strip() not in ("", "0")


def ask_to_close(handle):
    """Alt+F4, Windows' own shortcut for a window's close button."""
    key("F4", 0.3, ("Alt_L",))
    end = time.time() + 15
    while time.time() < end:
        if not running(handle):
            return True
        time.sleep(0.5)
    return False


def stop(_handle):
    _run("Get-Process InvMgr -ErrorAction SilentlyContinue | Stop-Process -Force; Start-Sleep 1")


def read_text(path):
    rc, out = winguest.run(f"[IO.File]::ReadAllText({quote(path)})")
    return out if rc == 0 else None


def listdir(path):
    rc, out = winguest.run(f"Get-ChildItem -Force -LiteralPath {quote(path)} | % Name")
    return [line.strip() for line in out.splitlines() if line.strip()] if rc == 0 else None


def copy_out(path, host_path):
    """Keeps a copy of a file the app wrote, beside the screenshots."""
    winguest.run(f"Copy-Item -LiteralPath {quote(path)} -Destination {quote(winguest.guest_path(host_path))}")
