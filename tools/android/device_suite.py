#!/usr/bin/env python3
"""
The on-device test suite: every field on the phone, driven through adb.

WHY THIS EXISTS
===============

M6.5c was reported "verified on the phone" and then the user found four bugs in it in
minutes. The reason is worth stating plainly, because it is the lesson: the checks that
were run confirmed that things APPEARED (the dial pad came up, the dialog moved) and
never confirmed that they WORKED. Nobody typed a digit into the dial pad. The digit did
not work.

So this suite drives the real app on real glass and asserts on real state. It is the
TestFX suite's counterpart for the things TestFX cannot reach: the system keyboard, the
system file picker, the navigation bar, and rotation.

HOW IT ASSERTS, WHICH IS THE PART THAT MAKES IT TRUSTWORTHY
===========================================================

Screenshots are the last resort here, not the first. Three readouts, in order of
preference, and none of them needs a line of test-only code in the app:

  1. THE APP'S OWN AUTOSAVE. The app writes .local/share/InvMgr/autosave.json about
     once a second, and the build is debuggable, so `adb shell run-as` can read it.
     That is the whole model (room, items, presets, modes) as structured JSON.
     "Type 7 into Width and confirm" becomes "assert the item is 7 inches wide".

  2. dumpsys input_method. The last `inputType=` line whose imeOptions is 0x10000000
     is OUR connection (that flag is set in InvMgrSurfaceView.onCreateInputConnection).
     0x1 is text, 0x2002 is number-with-decimal. That is how "which keyboard came up"
     is asserted without trusting a picture of a keyboard.

  3. Screenshots, for layout only: whether something is cut off or overlapping.

⚠ AND IT VERIFIES THE INSTALL TOOK. This burned a real debugging session: `adb install`
failed silently while its output was suppressed, and the next twenty minutes were spent
reading logs from a build that was not the one on the phone. install() now compares the
dex of the local APK against the dex pulled back off the device and refuses to continue
if they differ.

RUNNING IT
==========

    . tools/android/adb-env.sh
    python3 tools/android/device_suite.py            # everything
    python3 tools/android/device_suite.py keyboard   # only cases whose name matches
    python3 tools/android/device_suite.py --no-install   # use whatever is on the phone

⚠ Every coordinate below is for a 1080x2220 screen at density 3, which is the wired test
phone. On another phone they are all wrong, and the failure is a tap landing somewhere
harmless and an assertion reporting the wrong thing. UI.check_geometry() refuses to run
on a screen it does not recognize, so that cannot happen quietly.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
APK = os.path.join(REPO, "target/gluonfx/aarch64-android/gvm/InvMgr.apk")
ADB = os.path.expanduser("~/.gluon/substrate/Android/platform-tools/adb")
# The phone to drive, taken from the environment that adb-env.sh sets up rather than
# written here: it is a hardware identifier for somebody's own phone and this tree is
# published. Source `. tools/android/adb-env.sh` first, which is the documented way to
# run this anyway. Fails rather than guessing, for the reason that script gives.
SERIAL = os.environ.get("ANDROID_SERIAL", "").strip()
if not SERIAL:
    sys.exit("no phone pinned: source tools/android/adb-env.sh first, "
             "or set ANDROID_SERIAL to the serial `adb devices` lists")
PKG = "com.modcritic.invmgr"
ACT = PKG + "/.android.InvMgrActivity"
SHOTS = os.path.join(REPO, "target", "device-suite")


# --------------------------------------------------------------------------- the phone

def adb(*args, timeout=120, binary=False):
    """One adb call against the pinned phone."""
    out = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def shell(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def wake():
    """Wake AND unlock. Waking alone is not enough: with the keyguard up the window
    never gets a surface, the app stops after "InvMgrActivity created", and a
    screenshot comes back a blank white lock screen that reads as a dead app."""
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard")


def dex_fingerprint(path):
    """A hash of every classes*.dex in an APK, which is what tells two builds apart."""
    digest = hashlib.sha256()
    with zipfile.ZipFile(path) as apk:
        for name in sorted(n for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
            digest.update(apk.read(name))
    return digest.hexdigest()


def install():
    """Push the built APK and PROVE it is the one now on the phone."""
    if not os.path.exists(APK):
        raise SystemExit("no APK at %s: build first" % APK)
    result = adb("install", "-r", APK, timeout=600)
    if "Success" not in result:
        raise SystemExit("adb install failed:\n" + result)

    path = shell("pm path " + PKG).replace("package:", "").strip()
    pulled = os.path.join(SHOTS, "installed.apk")
    os.makedirs(SHOTS, exist_ok=True)
    adb("pull", path, pulled, timeout=600)
    if dex_fingerprint(APK) != dex_fingerprint(pulled):
        raise SystemExit(
            "THE INSTALL DID NOT TAKE. The phone is running different code from the APK\n"
            "just built, so every result below would be about the wrong build.")


def launch(timeout=60, clean=True):
    """Restart the app from a blank slate and block until it has built its scene.

    ⚠ `clean` is not optional hygiene, it is what makes an assertion mean anything. The app
    RESTORES ITS LAST SESSION on startup (that is M4's autosave doing its job), so without
    wiping the data first, every case begins with whatever the previous case left behind and
    `items[0]` is somebody else's item. That produced five confident failures whose real
    cause was a stale room.
    """
    wake()
    if clean:
        shell("pm clear " + PKG)
    shell("am force-stop " + PKG)
    adb("logcat", "-c")
    shell("am start -n " + ACT)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if "android true, reserving SystemInsets" in adb("logcat", "-d"):
            time.sleep(1.0)          # let the first frame land
            return
        time.sleep(0.5)
    raise SystemExit("the app never reported a scene within %ss" % timeout)


def tap(x, y, settle=0.6):
    shell("input tap %d %d" % (x, y))
    time.sleep(settle)


def keyevent(name, settle=0.4):
    shell("input keyevent " + name)
    time.sleep(settle)


def screenshot(name):
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, name + ".png")
    with open(path, "wb") as out:
        out.write(adb("exec-out", "screencap", "-p", binary=True, timeout=120))
    return path


# ------------------------------------------------------------------------ the readouts

AUTOSAVE = ".local/share/InvMgr/autosave.json"


def state(timeout=8.0):
    """The app's whole model, read out of its own autosave.

    Polls, because the autosave ticks about once a second and a change made a moment ago
    may not have reached the file yet. Returns None if it never appears.
    """
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        raw = shell("run-as %s cat %s" % (PKG, AUTOSAVE))
        try:
            last = json.loads(raw)
            return last
        except (ValueError, TypeError):
            time.sleep(0.5)
    return last


def wait_for_state(predicate, timeout=12.0):
    """Waits until the app's own state satisfies `predicate`, and returns it.

    This is the assertion primitive: it fails by timing out with the last state seen,
    which is far more useful than a bare False.
    """
    deadline = time.time() + timeout
    seen = None
    while time.time() < deadline:
        seen = state(timeout=2.0)
        if seen is not None:
            try:
                if predicate(seen):
                    return seen
            except (KeyError, IndexError, TypeError):
                pass
        time.sleep(0.5)
    return seen


TEXT = 0x1
NUMBER_DECIMAL = 0x2002
NUMBER = 0x2
# IME_FLAG_NO_EXTRACT_UI, which only our connections set. Matched as a BIT rather than as
# an exact value: a number field also carries IME_ACTION_NEXT, so its imeOptions reads
# 0x10000005. Comparing the whole word made every numeric connection invisible here and the
# suite reported the last TEXT one instead: a wrong answer, not an error.
NO_EXTRACT_UI = 0x10000000


def keyboard_type():
    """Which keyboard the app last asked for, straight from the system.

    dumpsys keeps a history; the last entry carrying OUR imeOptions is the live one.
    Returns None when the app has never raised a keyboard.
    """
    found = None
    for line in dumpsys_ime().splitlines():
        match = re.search(r"inputType=0x([0-9a-f]+) imeOptions=0x([0-9a-f]+)", line)
        if match and int(match.group(2), 16) & NO_EXTRACT_UI:
            found = int(match.group(1), 16)
    return found


def dumpsys_ime():
    return shell("dumpsys input_method")


def keyboard_showing():
    return "mInputShown=true" in dumpsys_ime()


def name_of_type(value):
    return {TEXT: "text", NUMBER: "number", NUMBER_DECIMAL: "number+decimal",
            None: "none"}.get(value, hex(value) if value is not None else "none")


# ------------------------------------------------------------------------------- the UI
#
# ⚠ ALL OF THESE ARE FOR 1080x2220 AT DENSITY 3. See check_geometry.
#
# ⚠ Never aim within ~50 px of the left or right edge: Samsung's edge gesture handler in
# SystemUI takes the touch before the app sees it, and logcat says "Delivering touch to
# (2055)", which is systemui rather than us. The drawer tabs sit on that edge, so they
# are aimed at x=70 and x=1010 rather than at their centers.

class UI:
    # island row, top bar FOLDED
    ADD = (132, 151)
    UNDO = (306, 151)
    FIT = (475, 151)
    PLAN = (627, 151)
    UNITS = (800, 151)
    FOLD = (957, 151)

    # island row, top bar UNFOLDED (everything drops by the room rows above it)
    ADD_OPEN = (132, 561)
    UNFOLD_CLOSE = (957, 561)

    # room fields, top bar UNFOLDED
    ROOM_W = (319, 141)
    ROOM_L = (610, 141)
    ROOM_H = (901, 141)
    SET_ROOM = (268, 258)
    LAYER_COLLISION = (712, 258)
    SAVE = (338, 393)
    LOAD = (566, 393)
    THREE_D = (768, 393)

    # ⚠ LANDSCAPE, where the island keeps its own width and is centered in what the
    # navigation bar leaves, so nothing here is the portrait number scaled. Measured off a
    # screenshot at 2220x1080. The fold button is at the same x whether the bar is out or
    # away; only the row's y moves, by the height of the three rows above it.
    LAND_FOLD = (1477, 156)                      # the island's last button, bar folded
    LAND_ADD = (613, 593)                        # the island's first button, bar open

    # drawer tabs
    ITEMS_TAB = (1010, 1093)
    LAYER_TAB = (70, 1093)

    # item list panel
    SEARCH = (836, 434)
    EXPORT = (1010, 294)

    # ⚠ The Add / Edit dialog MOVES when the keyboard appears, which is B7 working, so its
    # controls have no fixed coordinates at all. dialog_top() finds the panel on the screen
    # and DLG_OFFSET says how far below its top edge each control sits.

    # the numeric keypad
    DIGIT = {
        "1": (145, 1433), "2": (408, 1433), "3": (670, 1433),
        "4": (145, 1610), "5": (408, 1610), "6": (670, 1610),
        "7": (145, 1786), "8": (408, 1786), "9": (670, 1786),
        "0": (408, 1963),
    }
    DOT = (933, 1786)
    NUM_BACKSPACE = (933, 1433)
    NUM_ENTER = (933, 1610)

    # the letter keyboard
    LETTER = {
        "q": (65, 1533), "w": (171, 1533), "e": (276, 1533), "r": (381, 1533),
        "t": (486, 1533), "y": (590, 1533), "u": (696, 1533), "i": (800, 1533),
        "o": (906, 1533), "p": (1010, 1533),
        "a": (119, 1682), "s": (223, 1682), "d": (328, 1682), "f": (434, 1682),
        "g": (538, 1682), "h": (642, 1682), "j": (748, 1682), "k": (853, 1682),
        "l": (958, 1682),
        "z": (223, 1829), "x": (328, 1829), "c": (434, 1829), "v": (538, 1829),
        "b": (642, 1829), "n": (748, 1829), "m": (853, 1829),
    }
    SPACE = (539, 1978)

    @staticmethod
    def check_geometry():
        """Refuses to run on a screen these coordinates were not measured on.

        Without this the suite would still "run" on another phone: every tap would land
        somewhere harmless and every assertion would report something untrue.
        """
        size = shell("wm size")
        wanted = "1080x2220"
        if wanted not in size:
            raise SystemExit(
                "This suite's coordinates are measured for %s and this phone reports:\n  %s\n"
                "Re-measure UI before running it here." % (wanted, size.strip()))


def find_confirm():
    """Where the dialog's Confirm button is right now, or None if no dialog is open.

    ⚠ ANCHORED ON A COLOR, because nothing else about a dialog's position is knowable. It
    MOVES when the keyboard appears (that is B7 working), so no control has a fixed place,
    and two earlier attempts at guessing failed in ways that reported the wrong fault:
    picking one of two coordinate tables from `dumpsys` used a flag that lags the keyboard,
    and finding the panel's top edge picked the wrong edge when the dialog was lifted.

    Confirm is the only green thing on a dialog apart from the small preset "+", and it is
    always the LOWER of the two, so the bottom cluster of green is it.
    """
    from PIL import Image
    image = Image.open(screenshot("dialog-probe")).convert("RGB")
    width, height = image.size
    green = []
    for y in range(240, height - 160, 3):
        for x in range(620, width - 30, 3):
            red, grn, blu = image.getpixel((x, y))
            if grn > red + 18 and grn > blu + 12 and 55 < grn < 130:
                green.append((x, y))
    if not green:
        return None
    bottom = max(point[1] for point in green)
    button = [point for point in green if point[1] > bottom - 70]
    return (sum(p[0] for p in button) // len(button),
            sum(p[1] for p in button) // len(button))


# How far ABOVE Confirm each control sits, and its x. Measured once off a screenshot; every
# one of them travels with the dialog, so only the distance between them is fixed.
DLG_ABOVE = {"name": 463, "width": 354, "length": 239, "height": 126,
             "id": 0, "cancel": 0, "confirm": 0}
DLG_X = {"name": 702, "width": 879, "length": 879, "height": 879,
         "id": 377, "cancel": 622, "confirm": 888}


def dlg(which):
    """Taps a dialog control, wherever the dialog happens to be."""
    confirm = find_confirm()
    expect(confirm is not None, "no dialog is on screen to tap %r in" % which)
    tap(DLG_X[which], confirm[1] - DLG_ABOVE[which])


def confirm_dialog():
    """Presses Confirm.

    ⚠ NEVER press Back to drop the keyboard first, which is what this did at first. The app
    turns Back into Escape (deliberately, so a phone's Back button closes a dialog), so
    dismissing the keyboard that way CANCELS the dialog and the Confirm tap then lands on the
    room behind it.
    """
    dlg("confirm")


def type_digits(text):
    """Presses the keypad, one key per character. '.' is the decimal key."""
    for character in text:
        where = UI.DOT if character == "." else UI.DIGIT[character]
        tap(where[0], where[1], settle=0.35)


def type_letters(text):
    for character in text:
        where = UI.LETTER[character]
        tap(where[0], where[1], settle=0.35)


def clear_field(count=12):
    """Empties a focused field with the hardware DEL, which reaches the app directly."""
    for _ in range(count):
        shell("input keyevent KEYCODE_DEL")
    time.sleep(0.5)


def open_top_bar():
    tap(*UI.FOLD)


def close_top_bar():
    tap(*UI.UNFOLD_CLOSE)


def dismiss_keyboard():
    keyevent("KEYCODE_BACK")


# ---------------------------------------------------------------------------- the cases

CASES = []


def case(name):
    def register(function):
        CASES.append((name, function))
        return function
    return register


class Failure(Exception):
    pass


def expect(condition, message):
    if not condition:
        raise Failure(message)


def expect_type(wanted, where):
    """Waits for the keyboard to settle on `wanted`, then insists on it.

    ⚠ Retries, because `dumpsys` lags the keyboard. Asking once samples a value that may still
    be the previous field's, which fails while reporting the opposite of what is happening,
    the same staleness that made three other cases blame the wrong thing.
    """
    got = None
    for _ in range(12):
        got = keyboard_type()
        if got == wanted:
            return
        time.sleep(0.4)
    expect(False, "%s should raise the %s keyboard, got %s"
           % (where, name_of_type(wanted), name_of_type(got)))


# ---- typing into number fields, which is where it was broken --------------------------

@case("room W accepts every digit")
def room_w_every_digit():
    """One launch, nine digits. Relaunching per digit would be truer isolation and would
    also take three minutes; the field is cleared between each, which is enough."""
    launch()
    open_top_bar()
    wrong = []
    for digit in "123456789":
        tap(*UI.ROOM_W)
        clear_field()
        type_digits(digit)
        tap(*UI.SET_ROOM)
        seen = wait_for_state(lambda s, d=int(digit): s["room"]["w"] == d, timeout=8.0)
        got = seen["room"]["w"] if seen else None
        if got != int(digit):
            wrong.append("%s->%s" % (digit, got))
    expect(not wrong, "these digits did not reach the room's W field: " + ", ".join(wrong))


@case("room W accepts a two-digit number")
def room_w_two_digits():
    launch()
    open_top_bar()
    tap(*UI.ROOM_W)
    clear_field()
    type_digits("24")
    tap(*UI.SET_ROOM)
    seen = wait_for_state(lambda s: s["room"]["w"] == 24)
    expect(seen and seen["room"]["w"] == 24,
           "typing 24 should give w=24, autosave says %s" % (seen["room"]["w"] if seen else "nothing"))


@case("room W accepts zero as a digit")
def room_w_zero():
    launch()
    open_top_bar()
    tap(*UI.ROOM_W)
    clear_field()
    type_digits("10")
    tap(*UI.SET_ROOM)
    seen = wait_for_state(lambda s: s["room"]["w"] == 10)
    expect(seen and seen["room"]["w"] == 10,
           "typing 10 should give w=10, autosave says %s" % (seen["room"]["w"] if seen else "nothing"))


@case("room L and H accept digits too")
def room_l_and_h():
    launch()
    open_top_bar()
    tap(*UI.ROOM_L)
    clear_field()
    type_digits("14")
    tap(*UI.ROOM_H)
    clear_field()
    type_digits("9")
    tap(*UI.SET_ROOM)
    seen = wait_for_state(lambda s: s["room"]["l"] == 14 and s["room"]["h"] == 9)
    expect(seen and seen["room"]["l"] == 14 and seen["room"]["h"] == 9,
           "L should be 14 and H 9, autosave says l=%s h=%s"
           % (seen["room"]["l"] if seen else "?", seen["room"]["h"] if seen else "?"))


@case("the keypad's backspace deletes")
def keypad_backspace():
    launch()
    open_top_bar()
    tap(*UI.ROOM_W)
    clear_field()
    type_digits("15")
    tap(*UI.NUM_BACKSPACE)
    type_digits("7")                       # 15 -> 1 -> 17
    tap(*UI.SET_ROOM)
    seen = wait_for_state(lambda s: s["room"]["w"] == 17)
    expect(seen and seen["room"]["w"] == 17,
           "15, backspace, 7 should give 17, autosave says %s"
           % (seen["room"]["w"] if seen else "nothing"))


@case("a dialog dimension accepts digits and a decimal point")
def dialog_decimal():
    launch()
    tap(*UI.ADD)
    dlg("width")
    clear_field()
    type_digits("18.5")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and abs(s["items"][0]["w_in"] - 18.5) < 0.001)
    got = seen["items"][0]["w_in"] if seen and seen["items"] else "no item"
    expect(seen and seen["items"] and abs(seen["items"][0]["w_in"] - 18.5) < 0.001,
           "typing 18.5 into Width should give w_in=18.5, autosave says %s" % got)


@case("all three dialog dimensions take their own value")
def dialog_all_three():
    launch()
    tap(*UI.ADD)
    for which, value in (("width", "20"), ("length", "30"), ("height", "40")):
        dlg(which)
        clear_field()
        type_digits(value)
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and s["items"][0]["w_in"] == 20
                          and s["items"][0]["l_in"] == 30 and s["items"][0]["h_in"] == 40)
    item = seen["items"][0] if seen and seen["items"] else {}
    expect(item.get("w_in") == 20 and item.get("l_in") == 30 and item.get("h_in") == 40,
           "expected 20x30x40, autosave says %sx%sx%s"
           % (item.get("w_in"), item.get("l_in"), item.get("h_in")))


# ---- which keyboard each field asks for ----------------------------------------------

@case("keyboard: room W asks for the number pad")
def type_room_w():
    launch()
    open_top_bar()
    tap(*UI.ROOM_W)
    expect_type(NUMBER_DECIMAL, "the room's W field")


@case("keyboard: room L and H ask for the number pad")
def type_room_l_h():
    launch()
    open_top_bar()
    tap(*UI.ROOM_L)
    expect_type(NUMBER_DECIMAL, "the room's L field")
    tap(*UI.ROOM_H)
    expect_type(NUMBER_DECIMAL, "the room's H field")


@case("keyboard: a dialog dimension asks for the number pad")
def type_dialog_dimension():
    launch()
    tap(*UI.ADD)
    dlg("width")
    expect_type(NUMBER_DECIMAL, "the dialog's Width field")


@case("keyboard: the dialog's Name asks for letters")
def type_dialog_name():
    launch()
    tap(*UI.ADD)
    dlg("name")
    expect_type(TEXT, "the dialog's Name field")


@case("keyboard: the dialog's ID asks for letters")
def type_dialog_id():
    launch()
    tap(*UI.ADD)
    dlg("id")
    expect_type(TEXT, "the dialog's ID field")


@case("keyboard: the item search asks for letters")
def type_search():
    launch()
    tap(*UI.ITEMS_TAB)
    tap(*UI.SEARCH)
    expect_type(TEXT, "the item list's search box")


@case("keyboard: a number field then a text field puts letters back")
def type_switches_back():
    """The trap this exists for: the phone has ONE keyboard setting for the whole window,
    so a text field that does not ask for letters keeps whatever the last number field
    wanted."""
    launch()
    tap(*UI.ADD)
    dlg("width")
    expect_type(NUMBER_DECIMAL, "the dialog's Width field")
    dlg("name")
    expect_type(TEXT, "the Name field after a number field")


@case("keyboard: a text field then a number field brings the pad back")
def type_switches_forward():
    launch()
    tap(*UI.ADD)
    dlg("name")
    expect_type(TEXT, "the dialog's Name field")
    dlg("height")
    expect_type(NUMBER_DECIMAL, "the Height field after a text field")


# ---- typing into text fields ----------------------------------------------------------

@case("the dialog's Name takes letters")
def name_takes_letters():
    launch()
    tap(*UI.ADD)
    dlg("name")
    type_letters("desk")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and s["items"][0]["name"] == "desk")
    got = seen["items"][0]["name"] if seen and seen["items"] else "no item"
    expect(got == "desk", "typing 'desk' into Name should store it, autosave says %r" % got)


@case("a name survives a number field being used in between")
def name_with_number_between():
    launch()
    tap(*UI.ADD)
    dlg("width")
    clear_field()
    type_digits("36")
    dlg("name")
    type_letters("box")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and s["items"][0]["name"] == "box"
                          and s["items"][0]["w_in"] == 36)
    item = seen["items"][0] if seen and seen["items"] else {}
    expect(item.get("name") == "box" and item.get("w_in") == 36,
           "expected a 36in box called 'box', autosave says name=%r w_in=%s"
           % (item.get("name"), item.get("w_in")))


# ---- the keypad's Enter ---------------------------------------------------------------

@case("the keypad's Enter moves to the next number field")
def enter_moves_on():
    launch()
    tap(*UI.ADD)
    dlg("width")
    clear_field()
    type_digits("11")
    tap(*UI.NUM_ENTER)            # should land on Length
    clear_field()
    type_digits("22")
    tap(*UI.NUM_ENTER)            # should land on Height
    clear_field()
    type_digits("33")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and s["items"][0]["w_in"] == 11
                          and s["items"][0]["l_in"] == 22 and s["items"][0]["h_in"] == 33)
    item = seen["items"][0] if seen and seen["items"] else {}
    expect(item.get("w_in") == 11 and item.get("l_in") == 22 and item.get("h_in") == 33,
           "Enter should step Width -> Length -> Height; expected 11x22x33, autosave says %sx%sx%s"
           % (item.get("w_in"), item.get("l_in"), item.get("h_in")))


# ---- the screen flashing --------------------------------------------------------------
#
# ⚠ NEEDS ffmpeg, which is the only thing in this file that is not adb or PIL. There is no
# other way to see a flash: it is over in a fifth of a second, and a screenshot takes longer
# than that to come back, so sampling stills catches it only by luck. The phone can record
# its own screen at the panel's own rate, and then the question is arithmetic.


def record(action, seconds=6.0, press_at=2.0):
    """Runs `action` part way through a screen recording and returns how much each frame
    changed from the one before it, as a percentage of the screen.

    A still interface reads near zero. Something moving over the whole screen reads tens of
    percent, and that is what a flash is.
    """
    remote = "/sdcard/invmgr-record.mp4"
    local = os.path.join(SHOTS, "record.mp4")
    frames = os.path.join(SHOTS, "frames")
    os.makedirs(SHOTS, exist_ok=True)
    shutil.rmtree(frames, ignore_errors=True)
    os.makedirs(frames)

    recorder = subprocess.Popen(
        [ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", str(int(seconds)),
         "--bit-rate", "8000000", remote],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(press_at)
    action()
    recorder.wait(timeout=seconds + 30)
    time.sleep(1.0)
    adb("pull", remote, local, timeout=300)
    shell("rm -f " + remote)

    ffmpeg = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", local, "-vf", "fps=30,scale=270:-1",
         os.path.join(frames, "f%04d.png")], capture_output=True)
    if ffmpeg.returncode != 0:
        raise Failure("ffmpeg could not read the recording: "
                      + ffmpeg.stderr.decode("utf-8", "replace")[:400])

    from PIL import Image, ImageChops
    changes = []
    previous = None
    for name in sorted(os.listdir(frames)):
        image = Image.open(os.path.join(frames, name)).convert("L")
        if previous is not None:
            difference = ImageChops.difference(image, previous).histogram()
            moved = sum(difference[12:])          # pixels more than ~12 levels apart
            changes.append(100.0 * moved / (image.size[0] * image.size[1]))
        previous = image
    expect(len(changes) > 30, "the recording came back with only %d frames" % len(changes))
    return changes


# How much of the screen one frame may repaint before it reads as a flash rather than as a
# caret moving. Measured on the build that had the fault: 76% in the worst frame with 30 to
# 46% on the ones either side of it, against under 3% when nothing but the caret moves.
FLASH_PERCENT = 15.0


# ⚠ SIX PRESSES, NOT ONE, and that is the whole reason this case is trustworthy. The fault
# is a race between two of JavaFX's skins, so a single press catches it about half the time:
# written with one press this case passed against the very build that had the bug. Six
# presses caught it twice over in each of two runs. A test that only sometimes fails is
# worse than no test, because it reads as proof.
PRESSES = 6


@case("the keypad's Next does not flash the screen")
def next_does_not_flash():
    """The user's report of 2026-09-05: pressing Next flashes the whole interface.

    The cause was two of JavaFX's own skins disagreeing. The box being left asks for the
    keyboard to go away and the box being entered asks for it back, and the host layer
    obeyed both. See InvMgrActivity.HIDE_SETTLE_MS.
    """
    launch()
    tap(*UI.ADD)
    dlg("width")
    clear_field()
    type_digits("11")

    def press_next_repeatedly():
        for _ in range(PRESSES):
            tap(UI.NUM_ENTER[0], UI.NUM_ENTER[1], settle=0.7)

    worst = max(record(press_next_repeatedly, seconds=10.0, press_at=1.5))
    expect(worst < FLASH_PERCENT,
           "pressing Next repainted %.0f%% of the screen in a single frame. The keyboard is"
           " being dismissed and brought straight back, and the dialog drops and rises with"
           " it." % worst)


@case("moving between two fields by tapping does not flash either")
def tapping_between_fields_does_not_flash():
    """The same fault on the path nobody was looking at. Next is where the user noticed it,
    but every move from one text box to another goes through the same pair of requests."""
    launch()
    tap(*UI.ADD)
    dlg("width")
    clear_field()
    type_digits("11")

    # Where the two boxes are, worked out once. Asking again mid-recording would cost a
    # screenshot per tap, and the dialog cannot move while the keyboard stays up, which is
    # the very thing being checked.
    confirm = find_confirm()
    expect(confirm is not None, "no dialog is on screen to tap between")
    width = (DLG_X["width"], confirm[1] - DLG_ABOVE["width"])
    length = (DLG_X["length"], confirm[1] - DLG_ABOVE["length"])

    def tap_between_them():
        for index in range(PRESSES):
            where = length if index % 2 == 0 else width
            tap(where[0], where[1], settle=0.7)

    worst = max(record(tap_between_them, seconds=10.0, press_at=1.5))
    expect(worst < FLASH_PERCENT,
           "tapping between Width and Length repainted %.0f%% of the screen in a single"
           " frame" % worst)


# ---- rotation -------------------------------------------------------------------------

def set_rotation(value):
    shell("settings put system accelerometer_rotation 0")
    shell("settings put system user_rotation %d" % value)
    time.sleep(3.0)


@case("landscape: the room is inset from the navigation bar, not under it")
def landscape_inset():
    launch()
    try:
        set_rotation(1)
        path = screenshot("landscape")
        from PIL import Image
        image = Image.open(path).convert("RGB")
        width, height = image.size
        expect((width, height) == (2220, 1080),
               "landscape should be 2220x1080, the screenshot is %dx%d" % (width, height))

        # The navigation bar is 144 real pixels and is drawn light. If the app has reserved
        # space for it the app's own dark interface stops just before it; if the app is
        # drawing underneath it, the app's pixels run all the way to the edge and the bar is
        # painted on top. Sampling the row through the middle tells the two apart.
        middle = height // 2
        lightest_app_x = 0
        for x in range(width - 1, 0, -2):
            red, green, blue = image.getpixel((x, middle))
            if red < 120 and green < 120 and blue < 120:      # the app's own dark interface
                lightest_app_x = x
                break
        expect(width - 200 < lightest_app_x < width - 100,
               "the app's interface ends at x=%d of %d. The navigation bar is 144 px, so it"
               " should stop between %d and %d: further right means it is drawing underneath"
               " the bar, further left means something else is eating the width"
               % (lightest_app_x, width, width - 200, width - 100))
    finally:
        set_rotation(0)


@case("landscape: opening the top bar keeps every row and the status bar on screen")
def landscape_top_bar():
    """The user's report: turning the phone sideways and opening the bar lost both ends of the
    interface. LandscapeLayoutTest pins the arithmetic; this pins that it reaches the glass."""
    launch()
    try:
        set_rotation(1)
        # The fold button is the island's last one, and the island is centered in whatever the
        # navigation bar has left, so its place in landscape is nothing like its place upright.
        tap(*UI.LAND_FOLD)
        time.sleep(2.0)
        path = screenshot("landscape-unfolded")
        from PIL import Image
        image = Image.open(path).convert("RGB")
        width, height = image.size

        def row_has_light_text(y):
            return sum(1 for x in range(40, width - 200, 4)
                       if sum(image.getpixel((x, y))) > 300) > 10

        expect(any(row_has_light_text(y) for y in range(80, 200, 3)),
               "the room's W, L and H boxes are not on screen: the top bar's first row is"
               " drawn off the top of the window")
        expect(any(row_has_light_text(y) for y in range(height - 90, height - 10, 3)),
               "the app's status bar is not on screen: opening the top bar pushed it off"
               " the bottom")
    finally:
        set_rotation(0)


@case("landscape: the island's buttons still work with the top bar open")
def landscape_island_takes_a_tap():
    """The user's report of 2026-09-05: sideways, with the top bar down, the island's six
    buttons do nothing.

    The room's container was 64 design pixels tall with a 268-pixel drawer inside it, so the
    StackPane centered the drawer and the container's bounds reached 100 pixels up over the
    island. Everything aimed at a button landed on that instead. See Clips.

    ⚠ Asserted through to the autosave rather than off a screenshot: a dialog appearing
    proves the tap arrived, and an item appearing proves the dialog was real.
    """
    launch()
    try:
        set_rotation(1)
        tap(*UI.LAND_FOLD)                       # opens the top bar
        time.sleep(2.0)
        tap(*UI.LAND_ADD)                        # the island's Add, with the bar open
        time.sleep(1.5)
        confirm = find_confirm()
        expect(confirm is not None,
               "tapping the island's Add opened no dialog, so the tap never reached the"
               " button. Something is floating over the island and taking its taps.")
        tap(*confirm)
        seen = wait_for_state(lambda s: s["items"])
        expect(seen is not None and seen["items"],
               "the dialog opened but confirming it added no item")
    finally:
        set_rotation(0)


@case("rotating back to portrait restores the layout")
def rotation_round_trip():
    launch()
    set_rotation(1)
    set_rotation(0)
    path = screenshot("back-to-portrait")
    from PIL import Image
    image = Image.open(path).convert("RGB")
    expect(image.size == (1080, 2220),
           "portrait should be 1080x2220, the screenshot is %dx%d" % image.size)
    # ⚠ NOT via state(): the autosave file only exists once something has CHANGED, and a
    # launch that wipes the data and then only rotates changes nothing. Asserting on it here
    # failed for a reason that had nothing to do with rotation. Ask the process instead.
    expect(shell("pidof " + PKG).strip() != "",
           "the app should still be running after a rotation round trip")


# ------------------------------------------------------------------------------ the run

def main():
    arguments = [a for a in sys.argv[1:]]
    skip_install = "--no-install" in arguments
    if skip_install:
        arguments.remove("--no-install")
    pattern = arguments[0] if arguments else None

    UI.check_geometry()
    if not skip_install:
        print("installing and verifying the build on the phone ...")
        install()

    selected = [(n, f) for n, f in CASES if not pattern or pattern.lower() in n.lower()]
    if not selected:
        raise SystemExit("no case matches %r" % pattern)

    passed, failed = 0, []
    for name, function in selected:
        try:
            function()
            print("  pass      %s" % name)
            passed += 1
        except Failure as problem:
            print("  FAIL      %s\n              %s" % (name, problem))
            failed.append(name)
        except Exception as problem:                       # noqa: BLE001 - report, keep going
            print("  ERROR     %s\n              %s: %s" % (name, type(problem).__name__, problem))
            failed.append(name)

    print("\n%d passed, %d failed, of %d" % (passed, len(failed), len(selected)))
    for name in failed:
        print("  failed: %s" % name)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
