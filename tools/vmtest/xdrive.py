"""Drives the Xvfb display :99 like a person would, through the XTEST extension.

Moves are eased curves rather than jumps, so a screen recording shows the pointer travel."""
import math, os, time
from Xlib import X, XK, display
from Xlib.ext import xtest

d = display.Display(os.environ.get("XDRIVE_DISPLAY", ":99"))
# Every pointer glide takes this share of the time it is asked for. Recordings used 0.5; a test
# wants the pointer there, not a nice curve, so XDRIVE_MOVE_SCALE=0.1 or so.
MOVE_SCALE = float(os.environ.get("XDRIVE_MOVE_SCALE", "0.5"))
root = d.screen().root
pos = [1280, 720]

def _motion(x, y):
    xtest.fake_input(d, X.MotionNotify, x=int(x), y=int(y)); d.sync()
    pos[0], pos[1] = x, y

def move(x, y, seconds=0.5):
    seconds *= MOVE_SCALE
    x0, y0 = pos
    steps = max(1, int(seconds * 60))
    for i in range(1, steps + 1):
        t = i / steps
        e = 0.5 - 0.5 * math.cos(math.pi * t)
        _motion(x0 + (x - x0) * e, y0 + (y - y0) * e)
        time.sleep(seconds / steps)

def down(button=1):
    xtest.fake_input(d, X.ButtonPress, button); d.sync()

def up(button=1):
    xtest.fake_input(d, X.ButtonRelease, button); d.sync()

def click(x, y, button=1, travel=0.5, settle=0.35):
    move(x, y, travel); time.sleep(0.08); down(button); time.sleep(0.07); up(button); time.sleep(settle)

def double_click(x, y, travel=0.5, settle=0.5):
    move(x, y, travel); time.sleep(0.08)
    for _ in range(2):
        down(); time.sleep(0.05); up(); time.sleep(0.08)
    time.sleep(settle)

def drag(x1, y1, x2, y2, seconds=1.2, travel=0.5, settle=0.4):
    move(x1, y1, travel); time.sleep(0.1); down(); time.sleep(0.15)
    move(x2, y2, seconds); time.sleep(0.15); up(); time.sleep(settle)

SHIFTED = {'!': '1', '@': '2', '#': '3', '$': '4', '%': '5', '^': '6', '&': '7', '*': '8',
           '(': '9', ')': '0', '_': 'minus', '+': 'equal', ':': 'semicolon', '"': 'apostrophe',
           '<': 'comma', '>': 'period', '?': 'slash'}
NAMES = {' ': 'space', ',': 'comma', '.': 'period', '-': 'minus', '/': 'slash', "'": 'apostrophe',
         ';': 'semicolon', '=': 'equal'}

def _code(name):
    return d.keysym_to_keycode(XK.string_to_keysym(name))

def key(name, settle=0.25, modifiers=()):
    mods = [_code(m) for m in modifiers]
    for m in mods:
        xtest.fake_input(d, X.KeyPress, m)
    c = _code(name)
    xtest.fake_input(d, X.KeyPress, c); xtest.fake_input(d, X.KeyRelease, c)
    for m in reversed(mods):
        xtest.fake_input(d, X.KeyRelease, m)
    d.sync(); time.sleep(settle)

def hold(name, seconds):
    c = _code(name)
    xtest.fake_input(d, X.KeyPress, c); d.sync(); time.sleep(seconds)
    xtest.fake_input(d, X.KeyRelease, c); d.sync()

def type_text(text, per_char=0.09):
    for ch in text:
        if ch.isupper():
            key(ch.lower(), per_char, ("Shift_L",))
        elif ch in SHIFTED:
            key(SHIFTED[ch], per_char, ("Shift_L",))
        else:
            key(NAMES.get(ch, ch), per_char)

def pause(seconds):
    time.sleep(seconds)

def keys_down(*names):
    for n in names:
        xtest.fake_input(d, X.KeyPress, _code(n))
    d.sync()

def keys_up(*names):
    for n in reversed(names):
        xtest.fake_input(d, X.KeyRelease, _code(n))
    d.sync()

def look(dx, dy, seconds, keys=(), at=(1280, 720), ease=False):
    """A look drag of (dx, dy) over `seconds`, with `keys` held for the whole of it, so the camera
    moves and turns at once. Starts at `at`, and the pointer is put there first without a drag."""
    _motion(*at)
    keys_down(*keys)
    down()
    steps = max(1, int(seconds * 60))
    for i in range(1, steps + 1):
        t = i / steps
        e = (0.5 - 0.5 * math.cos(math.pi * t)) if ease else t
        _motion(at[0] + dx * e, at[1] + dy * e)
        time.sleep(seconds / steps)
    up()
    keys_up(*keys)
