"""Drives InvMgr on an X display with no window manager (Xvfb :99 here), through XTEST. With nothing
managing windows, this module does a window manager's few jobs itself: it stretches the app over
the screen, and gives each new dialog the keyboard.

XDRIVE_DISPLAY picks the display (default :99). The app's data folder is $XDG_DATA_HOME/InvMgr, or
~/.local/share/InvMgr.
"""
import os, subprocess, time
from Xlib import X, display, protocol
from PIL import Image

os.environ.setdefault("XDRIVE_MOVE_SCALE", "0.1")
from xdrive import click, double_click, drag, hold, key, look, move, pause, type_text  # noqa: E402,F401

DISP = os.environ.get("XDRIVE_DISPLAY", ":99")
DATA = os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "InvMgr")
GTK_NAME = (900, 26)


def shot():
    raw = display.Display(DISP).screen().root.get_image(0, 0, 2560, 1440, X.ZPixmap, 0xffffffff)
    return Image.frombytes("RGB", (2560, 1440), raw.data, "raw", "BGRX")


def _app_windows():
    d = display.Display(DISP)

    def walk(w, depth=0):
        for c in w.query_tree().children:
            name = c.get_wm_name()
            if name and "InvMgr" in str(name) and c.get_attributes().map_state:
                yield d, c
            if depth < 2:
                yield from walk(c, depth + 1)
    return list(walk(d.screen().root))


def _dialogs():
    """GTK's file dialogs: every mapped top-level window that is not the app's own."""
    d = display.Display(DISP)
    return [(d, c) for c in d.screen().root.query_tree().children
            if c.get_attributes().map_state and c.get_wm_name() and "InvMgr" not in str(c.get_wm_name())]


def app_titles():
    return [str(w.get_wm_name()) for _, w in _app_windows()]


def dialog_titles():
    return [str(w.get_wm_name()) for _, w in _dialogs()]


def files_dir(out):
    return out


def start(launch, log_path):
    log = open(log_path, "w")
    return subprocess.Popen(launch, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                            env=dict(os.environ, DISPLAY=DISP), start_new_session=True), log_path


def log_text(handle):
    with open(handle[1], errors="replace") as f:
        return f.read()


def fill_screen():
    for d, w in _app_windows():
        w.configure(x=0, y=0, width=2560, height=1440)
        d.sync()


def _focus(pairs):
    """Gives a window the keyboard, as a window manager does when a window opens or is clicked."""
    for d, w in pairs[:1]:
        w.set_input_focus(X.RevertToParent, X.CurrentTime)
        d.sync()
    time.sleep(0.3)


def into_dialog():
    """With no window manager, keys go to the window under the pointer, so the dialog gets the
    keyboard and the pointer, on its name box."""
    _focus(_dialogs())
    click(*GTK_NAME, settle=0.3)


def into_open_dialog():
    _focus(_dialogs())
    move(600, 300, 0.2)


def back_to_app():
    _focus(_app_windows())


def running(handle):
    return handle[0].poll() is None


def ask_to_close(handle):
    """Sends the window what a window manager's close button sends."""
    for d, w in _app_windows():
        ev = protocol.event.ClientMessage(window=w, client_type=d.intern_atom("WM_PROTOCOLS"),
                                          data=(32, [d.intern_atom("WM_DELETE_WINDOW"), X.CurrentTime, 0, 0, 0]))
        w.send_event(ev)
        d.sync()
    try:
        handle[0].wait(15)
        return True
    except subprocess.TimeoutExpired:
        return False


def stop(handle):
    if not ask_to_close(handle):
        handle[0].kill()


def read_text(path):
    try:
        with open(path, errors="replace") as f:
            return f.read()
    except OSError:
        return None


def listdir(path):
    return sorted(os.listdir(path)) if os.path.isdir(path) else None


def copy_out(path, host_path):
    """Save and Export already write beside the screenshots here."""
