#!/usr/bin/env python3
"""Runs the whole test round for InvMgr's Windows installer in the tiny10 VM.

    python3 windows_round.py <InvMgr-<version>-windows-x64-setup.exe>

In turn: start the VM, which logs in by itself, and wait for runner.ps1; set its screen to
2560x1440; remove any InvMgr already installed, its data folder and JavaFX's cache of its native
files, so the round sees what a new user sees; copy the setup into Downloads and go through its
wizard pressing Enter on every page, as someone taking every default would, with a screenshot per
page; check the app that the wizard's last page opens; run feature_test.py on the installed app;
open it from the Start menu's search; then remove it with its own uninstaller, answering its
questions, and list what is left behind. Screenshots and logs go to share/runs/<round>/, a table of
the results to SUMMARY.txt there. It refuses to start while any VM is running, and shuts tiny10
down at the end; a round that stops partway leaves it running, to look at.
"""
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

os.environ["VM"] = "tiny10"
HERE = Path(__file__).resolve().parent

import drive_win
import winguest
from winguest import quote

SHARE = Path(winguest.SHARE)

INSTALL_DIR = r"C:\Users\tester\AppData\Local\Programs\InvMgr"
INSTALLED = INSTALL_DIR + r"\InvMgr.exe"
SHORTCUT = r"C:\Users\tester\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\InvMgr.lnk"
UNINSTALL_KEY = r"HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{5239BBF2-E4C2-4B96-A8AD-D71E6BBC195D}_is1"
JAVAFX_CACHE = r"C:\Users\tester\.openjfx"
# What the setup puts on the machine, which its uninstaller has to take away again, and what the
# app makes for itself while it runs, which no uninstaller knows about.
FROM_SETUP = {"install folder": INSTALL_DIR, "Start menu shortcut": SHORTCUT, "uninstall entry": UNINSTALL_KEY}
FROM_APP = {"data folder": drive_win.DATA, "JavaFX's native file cache": JAVAFX_CACHE}
WIZARD_BUTTONS = {"next", "install", "finish"}
MODE_DIALOG = "Select Setup Install Mode"
SETUP_WINDOWS = ("[Win]::List() | ? { (Get-Process -Id $_.Pid -ErrorAction SilentlyContinue).ProcessName "
                 "-like 'InvMgr-*-setup*' }")


def virsh(*args):
    return subprocess.run(["virsh", "-c", "qemu:///session", *args],
                          check=True, capture_output=True, text=True).stdout.strip()


def run(command, timeout=60.0):
    rc, out = winguest.run(command, timeout)
    if rc != 0:
        raise RuntimeError(f"Windows command failed: {command}\n{out}")
    return out.strip()


def shot(path):
    drive_win.shot().save(path)


def front(handle):
    run(f"if ([Win]::GetForegroundWindow() -ne [IntPtr]{handle}) {{ [Win]::Front([IntPtr]{handle}) | Out-Null }}")


def windows_with_controls(query):
    """[(handle, title, [control texts])] for each window `query` lists."""
    # A label's text can run over several lines; each window has to stay on one.
    out = run(f"@({query}) | % {{ \"$($_.Handle)`t$($_.Title)`t\" + "
              f"(([Win]::Controls([IntPtr]$_.Handle) | % {{ $_ -replace '\\s+', ' ' }}) -join '|') }}")
    found = []
    for line in out.splitlines():
        handle, title, controls = (line.split("\t") + ["", ""])[:3]
        found.append((int(handle), title, [c.strip() for c in controls.split("|")] if controls else []))
    return found


def button(text):
    """A wizard button's label without its keyboard marker or arrow: "&Next >" is "next"."""
    return text.replace("&", "").strip(" <>").lower()


def wait(test, seconds, step=0.5):
    end = time.time() + seconds
    while time.time() < end:
        result = test()
        if result:
            return result
        time.sleep(step)
    return None


def clean_slate():
    """Uninstalls a copy left by an earlier round, silently, and removes what a new user would not have."""
    uninstaller = run(f"if (Test-Path {quote(UNINSTALL_KEY)}) {{ (Get-ItemProperty {quote(UNINSTALL_KEY)}).UninstallString }}")
    if uninstaller:
        exe = uninstaller.strip('"')
        run(f"Start-Process -Wait -FilePath {quote(exe)} -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES'", timeout=120)
        wait(lambda: run(f"Test-Path {quote(INSTALL_DIR)}") == "False", 60)
    run("Get-Process InvMgr -ErrorAction SilentlyContinue | Stop-Process -Force; "
        + "; ".join(f"Remove-Item -Recurse -Force -LiteralPath {quote(p)} -ErrorAction SilentlyContinue"
                    for p in (INSTALL_DIR, drive_win.DATA, JAVAFX_CACHE, r"C:\Users\tester\invmgr-test-files"))
        + r"; Remove-Item -Force C:\Users\tester\Downloads\InvMgr-*-setup.exe -ErrorAction SilentlyContinue")


def through_wizard(setup_name, out):
    """Starts the setup from Downloads and presses Enter on each page once that page is up: the
    install mode question, then every wizard page with a Next, Install or Finish button. The
    install itself has none, so it is waited out. Returns the pages seen, by their headings, and
    what went wrong, if anything."""
    run(rf"Copy-Item {quote(winguest.guest_path(SHARE / 'round' / setup_name))} C:\Users\tester\Downloads\; "
        rf"Start-Process C:\Users\tester\Downloads\{setup_name}")
    pages, last = [], None

    def next_page():
        for window in windows_with_controls(SETUP_WINDOWS):
            buttons = {button(c) for c in window[2]}
            if (window[1], window[2]) != last and (window[1] == MODE_DIALOG or WIZARD_BUTTONS & buttons):
                return window
        return None

    for _ in range(12):
        page = wait(next_page, 300)
        if not page:
            return pages, "the wizard stopped changing"
        handle, title, controls = page
        heading = title if title == MODE_DIALOG else next(
            (c for c in controls if button(c) not in WIZARD_BUTTONS | {"back", "cancel"}), title)
        time.sleep(1)
        shot(out / f"setup-{len(pages) + 1:02d}.png")
        pages.append(heading)
        front(handle)
        drive_win.key("Return", 1.0)
        if "finish" in {button(c) for c in controls}:
            return pages, None
        last = (title, controls)
    return pages, "more than 12 pages"


def app_window():
    titles = drive_win._windows()
    return next((w for w in titles if w["Title"] == "InvMgr"), None)


def check_running_app(name, out):
    """Waits for the app's window, then checks that it is the installed InvMgr.exe running the Java
    installed beside it, saves a screenshot, and closes it with Alt+F4."""
    window = wait(app_window, 90)
    if not window:
        return "no window"
    time.sleep(3)
    shot(out / f"{name}.png")
    found = run(f"$p = Get-Process -Id {window['Pid']}; $p.Path; "
                f"$p.Modules | ? {{ $_.ModuleName -eq 'jvm.dll' }} | % FileName").splitlines()
    front(window["Handle"])
    drive_win.key("F4", 0.3, ("Alt_L",))
    closed = wait(lambda: not drive_win.running(), 20)
    if found[:1] != [INSTALLED]:
        return f"window belongs to {found[:1]}"
    if found[1:] != [INSTALL_DIR + r"\runtime\bin\server\jvm.dll"]:
        return f"runs Java from {found[1:]}"
    return "opened the installed app" + ("" if closed else ", but Alt+F4 did not close it")


def feature_test(out):
    out.mkdir(parents=True)
    result = subprocess.run([sys.executable, str(HERE / "feature_test.py"), "win", str(out), INSTALLED],
                            cwd=HERE, capture_output=True, text=True)
    (out / "result.txt").write_text(result.stdout + result.stderr)
    passed = re.search(r"(\d+) of (\d+) passed", result.stdout)
    return passed.group(0) if passed else "did not finish, see result.txt"


def from_start_menu(out):
    target = run(f"(New-Object -ComObject WScript.Shell).CreateShortcut({quote(SHORTCUT)}).TargetPath")
    if target != INSTALLED:
        return f"the Start menu shortcut points at {target!r}"
    drive_win.key("Super_L", 1.5)
    drive_win.type_text("InvMgr", 0.08)
    time.sleep(2.5)
    shot(out / "start-search.png")
    drive_win.key("Return", 0.5)
    return check_running_app("from-start-menu", out)


def uninstall(out):
    """Runs the command Windows' list of installed apps runs for InvMgr, and answers it: Yes to
    removing it, then OK. Returns the result, and what is still there afterwards."""
    exe = run(f"(Get-ItemProperty {quote(UNINSTALL_KEY)}).UninstallString").strip('"')
    run(f"Start-Process -FilePath {quote(exe)}")
    uninstall_windows = "[Win]::List() | ? Title -eq 'InvMgr Uninstall'"
    answered = []
    end = time.time() + 180
    while time.time() < end and "OK" not in answered:
        for handle, _, controls in windows_with_controls(uninstall_windows):
            # The question's default button is No, so Yes is its Y key, as a person would press.
            if "&Yes" in controls and "Yes" not in answered:
                time.sleep(1)
                shot(out / "uninstall-confirm.png")
                front(handle)
                drive_win.key("y", 1.0)
                answered.append("Yes")
            elif "OK" in controls:
                time.sleep(1)
                shot(out / "uninstall-done.png")
                front(handle)
                drive_win.key("Return", 1.0)
                answered.append("OK")
        time.sleep(0.5)
    if not wait(lambda: not windows_with_controls(uninstall_windows), 30):
        answered.append("but its window stayed open")
    wait(lambda: run(f"Test-Path {quote(INSTALL_DIR)}") == "False", 60)
    present = lambda places: [name for name, path in places.items() if run(f"Test-Path {quote(path)}") == "True"]
    left = present(FROM_SETUP)
    result = "clean" if answered == ["Yes", "OK"] and not left else f"answered {answered}, left {left}"
    return result, present(FROM_APP)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.split("\n\n")[1])
    setup = Path(sys.argv[1]).resolve()
    if virsh("list", "--name", "--state-running"):
        sys.exit("A VM is already running; this runs one at a time")
    round_dir = SHARE / "runs" / f"windows-{datetime.now():%Y%m%d-%H%M}"
    round_dir.mkdir(parents=True)
    staged = SHARE / "round"
    if staged.exists():
        shutil.rmtree(staged)
    staged.mkdir()
    shutil.copy2(setup, staged)
    shutil.rmtree(SHARE / "runner" / "queue", ignore_errors=True)
    winguest.install_runner()

    row = {"setup": setup.name}
    print("tiny10: starting", flush=True)
    virsh("start", "tiny10")
    winguest.run('"ready"', timeout=300)
    # Straight after logon Windows can refuse a new screen size, and take it a few seconds later.
    if not wait(lambda: run(r"& Z:\runner\display.ps1 2560 1440; [Win]::GetSystemMetrics(0); "
                            r"[Win]::GetSystemMetrics(1)").splitlines()[-2:] == ["2560", "1440"], 60, 3):
        sys.exit("Windows would not take a 2560x1440 screen")
    time.sleep(3)
    clean_slate()

    pages, problem = through_wizard(setup.name, round_dir)
    (round_dir / "setup-pages.txt").write_text("\n".join(pages) + "\n")
    row["install"] = f"{len(pages)} pages" + (f", {problem}" if problem else "")
    print(f"tiny10: install, {row['install']}: {' / '.join(pages)}", flush=True)
    left = None
    if not problem:
        row["launched by setup"] = check_running_app("from-setup", round_dir)
        print(f"tiny10: launched by setup, {row['launched by setup']}", flush=True)
        run(f"Remove-Item -Recurse -Force -LiteralPath {quote(drive_win.DATA)} -ErrorAction SilentlyContinue")
        row["installed app"] = feature_test(round_dir / "installed")
        print(f"tiny10: installed app {row['installed app']}", flush=True)
        row["Start menu"] = from_start_menu(round_dir)
        print(f"tiny10: Start menu {row['Start menu']}", flush=True)
        row["removal"], left = uninstall(round_dir)
        print(f"tiny10: removal {row['removal']}; the app's own files still there: {', '.join(left) or 'none'}",
              flush=True)

    virsh("shutdown", "tiny10", "--mode", "agent")
    wait(lambda: virsh("domstate", "tiny10") == "shut off", 180, 3)
    lines = [f"Setup: {setup}", ""] + [f"{k}: {v}" for k, v in row.items()]
    if left is not None:
        lines.append(f"made by the app, still there after removal: {', '.join(left) or 'none'}")
    (round_dir / "SUMMARY.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\nResults in {round_dir}")


if __name__ == "__main__":
    main()
