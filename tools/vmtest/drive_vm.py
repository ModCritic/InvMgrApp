"""Drives InvMgr inside a test VM the way a person at its screen would. Clicks and keys go in through
QEMU's virtual tablet and keyboard, so they reach the app through the guest's own desktop, and
screenshots are QEMU's picture of the VM's screen. Starting the app and reading its files happen
inside the VM through guest-runner.sh (see vmguest.py).

Set VM to the libvirt domain's name. Its screen has to be 2560x1440, and its desktop logged in
with the share mounted and the runner going.
"""
import io, json, math, os, shlex, time
import libvirt, libvirt_qemu
from PIL import Image

import vmguest

VM = os.environ["VM"]
_conn = libvirt.open("qemu:///session")
_dom = _conn.lookupByName(VM)
WIDTH, HEIGHT = 2560, 1440
ABS_MAX = 0x7FFF
# Every pointer glide takes this share of the time it is asked for, as in xdrive.py.
MOVE_SCALE = float(os.environ.get("XDRIVE_MOVE_SCALE", "0.1"))
RUNNER = vmguest.GUEST_SHARE + "/runner"
WM = f"cd {RUNNER} && PYTHONPATH=pylib python3 wm.py"
HOME = "/home/tester"
DATA = HOME + "/.local/share/InvMgr"
# Where a package's install script leaves the menu entry. jpackage's scripts run xdg-desktop-menu,
# which writes to the first folder of $XDG_DATA_DIRS: /usr/share on Debian and Ubuntu, but
# /usr/local/share on Fedora, whose package manager runs scripts without that variable.
MENU_ENTRIES = "/usr/share/applications/*invmgr*.desktop /usr/local/share/applications/*invmgr*.desktop"
pos = [WIDTH // 2, HEIGHT // 2]

# X keysym names, as the test writes them, to QEMU's key codes.
QCODES = {"Tab": "tab", "Return": "ret", "Escape": "esc", "Control_L": "ctrl", "Shift_L": "shift",
          "Alt_L": "alt", "Super_L": "meta_l", "F4": "f4", "BackSpace": "backspace", "space": "spc"}
CHARS = {" ": "spc", ".": "dot", "/": "slash", "\\": "backslash", "-": "minus", ",": "comma",
         "'": "apostrophe", ";": "semicolon", "=": "equal"}
SHIFTED = {"_": "minus", ":": "semicolon", "~": "grave_accent", "?": "slash", "!": "1", "@": "2"}


def _input(events):
    libvirt_qemu.qemuMonitorCommand(
        _dom, json.dumps({"execute": "input-send-event", "arguments": {"events": events}}), 0)


def _motion(x, y):
    _input([{"type": "abs", "data": {"axis": "x", "value": round(x * ABS_MAX / (WIDTH - 1))}},
            {"type": "abs", "data": {"axis": "y", "value": round(y * ABS_MAX / (HEIGHT - 1))}}])
    pos[0], pos[1] = x, y


def _button(down, button=1):
    _input([{"type": "btn", "data": {"down": down, "button": "right" if button == 3 else "left"}}])


def _key(qcode, down):
    _input([{"type": "key", "data": {"down": down, "key": {"type": "qcode", "data": qcode}}}])


def _qcode(name):
    return QCODES.get(name, name.lower())


def move(x, y, seconds=0.5):
    seconds *= MOVE_SCALE
    x0, y0 = pos
    steps = max(1, int(seconds * 60))
    for i in range(1, steps + 1):
        e = 0.5 - 0.5 * math.cos(math.pi * i / steps)
        _motion(x0 + (x - x0) * e, y0 + (y - y0) * e)
        time.sleep(seconds / steps)


def click(x, y, button=1, travel=0.5, settle=0.35):
    move(x, y, travel); time.sleep(0.08)
    _button(True, button); time.sleep(0.07); _button(False, button); time.sleep(settle)


def double_click(x, y, travel=0.5, settle=0.5):
    move(x, y, travel); time.sleep(0.08)
    for _ in range(2):
        _button(True); time.sleep(0.05); _button(False); time.sleep(0.08)
    time.sleep(settle)


def drag(x1, y1, x2, y2, seconds=1.2, travel=0.5, settle=0.4):
    move(x1, y1, travel); time.sleep(0.1); _button(True); time.sleep(0.15)
    move(x2, y2, seconds); time.sleep(0.15); _button(False); time.sleep(settle)


def key(name, settle=0.25, modifiers=()):
    for m in modifiers:
        _key(_qcode(m), True)
    _key(_qcode(name), True); _key(_qcode(name), False)
    for m in reversed(modifiers):
        _key(_qcode(m), False)
    time.sleep(settle)


def hold(name, seconds):
    _key(_qcode(name), True); time.sleep(seconds); _key(_qcode(name), False)


def type_text(text, per_char=0.09):
    for ch in text:
        if ch.isupper():
            key(ch.lower(), per_char, ("Shift_L",))
        elif ch in SHIFTED:
            key(SHIFTED[ch], per_char, ("Shift_L",))
        else:
            key(CHARS.get(ch, ch), per_char)


def pause(seconds):
    time.sleep(seconds)


def look(dx, dy, seconds, keys=(), at=(1280, 720)):
    """A look drag of (dx, dy) over `seconds`, from `at`, with `keys` held throughout."""
    _motion(*at)
    for k in keys:
        _key(_qcode(k), True)
    _button(True)
    steps = max(1, int(seconds * 60))
    for i in range(1, steps + 1):
        _motion(at[0] + dx * i / steps, at[1] + dy * i / steps)
        time.sleep(seconds / steps)
    _button(False)
    for k in reversed(keys):
        _key(_qcode(k), False)


def shot():
    stream = _conn.newStream(0)
    _dom.screenshot(stream, 0, 0)
    data = bytearray()
    stream.recvAll(lambda _s, buf, _o: data.extend(buf), None)
    stream.finish()
    return Image.open(io.BytesIO(bytes(data))).convert("RGB")


def _windows():
    rc, out = vmguest.run(f"{WM} list")
    return json.loads(out) if rc == 0 else []


def app_titles():
    return [w["name"] for w in _windows() if w["name"] == "InvMgr"]


def dialog_titles():
    """Every titled window that is neither the app's main window nor one of mutter's own. The window
    manager's frame carries its window's title too, so each title counts once."""
    return sorted({w["name"] for w in _windows() if w["name"] not in ("InvMgr", "mutter guard window")})


def menu_entry_class():
    """The StartupWMClass of the installed menu entry, and the classes of the app's windows. A
    desktop ties a running window to its menu entry when the two match."""
    _, entry = vmguest.run(f"sed -n 's/^StartupWMClass=//p' $(ls {MENU_ENTRIES} 2>/dev/null)")
    _, classes = vmguest.run(f"{WM} classes")
    return entry.strip(), classes.split()


def files_dir(out):
    """Where Save and Export are pointed: a folder in the test user's home, made fresh."""
    path = HOME + "/invmgr-test-files"
    vmguest.run(f"rm -rf {path} && mkdir -p {path}")
    return path


def start(launch, log_path):
    """Starts the app as LAUNCH says: a command run in the VM, or menu:NAME, which finds NAME in the
    desktop's app search and opens it, as someone would after installing a package. A menu launch
    has no terminal, so its output is read from the user's journal."""
    since = vmguest.run("date +%s.%N")[1].strip()
    if launch[0].startswith("menu:"):
        # GNOME Shell notices a newly installed menu entry some seconds late: on Ubuntu 26.04 a
        # search made straight after apt finished found nothing, so Return searched the web.
        vmguest.run(f"f=$(ls {MENU_ENTRIES} 2>/dev/null | head -1); [ -n \"$f\" ] || exit 1; "
                    "while [ $(( $(date +%s) - $(stat -c %Y \"$f\") )) -lt 20 ]; do sleep 1; done")
        key("Super_L", 1.2)
        type_text(launch[0][len("menu:"):], 0.08)
        pause(1.5)
        shot().save(log_path.replace(".log", "-menu-search.png"))
        key("Return", 0.5)
    else:
        command = " ".join(shlex.quote(a) for a in launch)
        guest_log = vmguest.guest_path(log_path)
        vmguest.run(f"cd ~ && (setsid {command} > {shlex.quote(guest_log)} 2>&1 < /dev/null &)")
    return {"launch": launch, "log": log_path, "since": since}


def log_text(handle):
    if handle["launch"][0].startswith("menu:"):
        rc, out = vmguest.run(f"journalctl --user -o cat --since @{handle['since']} | grep -a 'InvMgr\\|javafx\\|JavaFX'")
        with open(handle["log"], "w") as f:
            f.write(out)
        return out
    try:
        with open(handle["log"], errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def fill_screen():
    vmguest.run(f"{WM} fullscreen")


def into_dialog():
    """The desktop gives a new dialog the keyboard by itself."""


def into_open_dialog():
    """As into_dialog."""


def back_to_app():
    """As into_dialog: closing a dialog hands the keyboard back to the app."""


def running(_handle=None):
    rc, _ = vmguest.run("for e in /proc/[0-9]*/exe; do case \"$(readlink $e)\" in */bin/InvMgr) exit 0;; esac; done; exit 1")
    return rc == 0


def ask_to_close(handle):
    """Alt+F4, the desktop's own shortcut for a window's close button."""
    key("F4", 0.3, ("Alt_L",))
    end = time.time() + 15
    while time.time() < end:
        if not running(handle):
            return True
        time.sleep(0.5)
    return False


def stop(_handle):
    vmguest.run(f"bash {RUNNER}/stop_app.sh")


def read_text(path):
    rc, out = vmguest.run(f"cat {shlex.quote(path)}")
    return out if rc == 0 else None


def listdir(path):
    rc, out = vmguest.run(f"ls -1A {shlex.quote(path)}")
    return out.split() if rc == 0 else None


def copy_out(path, host_path):
    """Keeps a copy of a file the app wrote, beside the screenshots."""
    vmguest.run(f"cp {shlex.quote(path)} {shlex.quote(vmguest.guest_path(host_path))}")
