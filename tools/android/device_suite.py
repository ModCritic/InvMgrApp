#!/usr/bin/env python3
"""
The on-device test suite: every field on the phone, driven through adb.

WHY THIS EXISTS
===============

The Android bridge (the JNI calls between the app and Android's own Java) was reported
"verified on the phone" and then the user found four bugs in it in minutes. The reason is
worth stating plainly, because it is the lesson: the checks that were run confirmed that
things APPEARED (the dial pad came up, the dialog moved) and never confirmed that they
WORKED. Nobody typed a digit into the dial pad. The digit did
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

A case registered with opt_in=True is left out of a plain run and runs only when the pattern
matches its name. Those are the ones that leave something behind on the phone, like a file in
Downloads; each says so in its name.

⚠ Every coordinate below is for a 1080x2220 screen at density 3, which is the wired test
phone. On another phone they are all wrong, and the failure is a tap landing somewhere
harmless and an assertion reporting the wrong thing. UI.check_geometry() refuses to run
on a screen it does not recognize, so that cannot happen quietly.
"""

import collections
import hashlib
import html
import json
import os
import random
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
APK = os.path.join(REPO, "target/gluonfx/aarch64-android/gvm/InvMgr.apk")


def app_version():
    """The version the build wrote into the app, from the same file About reads it from."""
    path = os.path.join(REPO, "target/classes/com/modcritic/invmgr/app.properties")
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("version="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("no version in %s: build first" % path)
# The adb command, as a list. INVMGR_ADB replaces it with another command line, which is how
# the suite drives an emulator: tools/android/emulator.sh runs each one in a network namespace
# of its own, and only an adb started inside that namespace can reach it. `emulator.sh suite`
# sets this.
ADB = shlex.split(os.environ.get("INVMGR_ADB", "")) or [
    os.path.expanduser("~/.gluon/substrate/Android/platform-tools/adb")]
# The phone to drive, taken from the environment that adb-env.sh sets up rather than
# written here: it is a hardware identifier for somebody's own phone and this tree is
# published. Source `. tools/android/adb-env.sh` first, which is the documented way to
# run this anyway. Fails rather than guessing, for the reason that script gives.
SERIAL = os.environ.get("ANDROID_SERIAL", "").strip()
if not SERIAL:
    sys.exit("no phone pinned: source tools/android/adb-env.sh first, "
             "or set ANDROID_SERIAL to the serial `adb devices` lists")
# The app ID and the activity's class name are DIFFERENT STRINGS, and were the same
# until 2026-09-22. PKG is what Play and the phone file the app under; the class name
# is Java and did not move.
#
# ⚠ So ACT cannot be PKG + "/.android.InvMgrActivity" any more. A leading dot means
# "relative to the app ID", which would resolve to a class that does not exist.
PKG = "com.asterkazika.invmgr"
ACT = PKG + "/com.modcritic.invmgr.android.InvMgrActivity"
# One folder per device, so the phone and an emulator can run the suite at the same time without
# writing over each other's screenshots. Under target/, which is never published.
SHOTS = os.path.join(REPO, "target", "device-suite", SERIAL)


# --------------------------------------------------------------------------- the phone

def adb(*args, timeout=120, binary=False):
    """One adb call against the pinned phone."""
    out = subprocess.run([*ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)
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
    # ⚠ A release build on the phone blocks the install: it is signed with the upload
    # key, and Android will not replace an app with one signed by another. It could not run
    # these cases anyway, since they read the autosave through run-as, which Android allows
    # only for a debuggable app. So it is uninstalled first, which wipes its data.
    installed = shell("dumpsys package " + PKG)
    if "versionCode=" in installed and "DEBUGGABLE" not in installed:
        print("the phone has a release build of InvMgr: uninstalling it first")
        adb("uninstall", PKG)
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
    RESTORES ITS LAST SESSION on startup (that is the autosave doing its job), so without
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
    # island row, top bar FOLDED. Re-measured 2026-09-24 when the Menu button was added, off the
    # #555 button borders on a screenshot: the Menu square (33 design px) is 20 narrower than the
    # Units button it replaced, so the island re-centered and every button moved about 30 real
    # pixels.
    ADD = (163, 151)
    UNDO = (337, 151)
    FIT = (506, 151)
    PLAN = (658, 151)
    MENU = (801, 151)
    FOLD = (928, 151)

    # island row, top bar UNFOLDED (everything drops by the room rows above it)
    ADD_OPEN = (163, 561)
    UNFOLD_CLOSE = (928, 561)

    # With the bars hidden the island moves up by the 72 px the status bar had. Measured off
    # the Add button's fill on a screenshot: rows 21 to 137.
    ADD_BARS_HIDDEN = (163, 79)
    # The items tab, measured the same way, off its #555 border in column 1060: rows 1077 to
    # 1184. The search box is the island's 72 px higher than with the bars showing.
    ITEMS_TAB_BARS_HIDDEN = (1010, 1130)
    SEARCH_BARS_HIDDEN = (836, 362)

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
    # Re-measured when the Menu button was added, the same day as the portrait numbers, for the
    # same reason.
    LAND_FOLD = (1442, 156)                      # the island's last button, bar folded
    LAND_MENU = (1311, 156)                      # the one before it, bar folded
    LAND_ADD = (646, 593)                        # the island's first button, bar open

    # drawer tabs
    ITEMS_TAB = (1010, 1093)
    LAYER_TAB = (70, 1093)

    # item list panel
    SEARCH = (836, 434)
    EXPORT = (1010, 294)

    # ⚠ The Add / Edit dialog MOVES when the keyboard appears, which is the app keeping it clear
    # of the keyboard, so its controls have no fixed coordinates at all. find_confirm() finds the
    # dialog's Confirm button on the screen, and DLG_ABOVE and DLG_X say where each control sits
    # from there.

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
    ENTER = (986, 1981)

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
    MOVES when the keyboard appears (the app keeps it clear), so no control has a fixed place,
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


# ---------------------------------------------------------------------------- the cases

CASES = []


def case(name, opt_in=False):
    """Registers a case. An opt_in case runs only when a pattern on the command line names it.

    For the cases that leave something behind on the phone, like a file in Downloads, so that a
    plain run of the whole suite never does.
    """
    def register(function):
        CASES.append((name, function, opt_in))
        return function
    return register


class Failure(Exception):
    pass


class Skipped(Exception):
    """A case that needs something only the test phone has: its own keyboard, browser or mail
    apps. On an emulator or another phone it is reported as skipped, with the reason, rather
    than as a failure of the app."""


TEST_PHONE_FILE = os.path.expanduser("~/.config/invmgr/test-phone")


def on_test_phone():
    """Whether the suite is driving the wired test phone, whose serial adb-env.sh reads from here."""
    try:
        with open(TEST_PHONE_FILE) as pinned:
            return pinned.read().strip() == SERIAL
    except OSError:
        return False


def needs_the_test_phone(what):
    if not on_test_phone():
        raise Skipped("needs %s" % what)


# The keypad cases tap Samsung's number pad by position, measured off the test phone. Another
# keyboard puts other keys there: on the Android 16 emulator Gboard typed 1 where 4 was pressed.
SAMSUNG_PAD = "the test phone's Samsung number pad, whose key positions UI.DIGIT holds"


def started(action, data):
    """Whether Android started an activity for this intent action with data beginning like this,
    since the log was cleared. Read off ActivityTaskManager's own `START u0 {act=... dat=...}`
    line, which is written the moment Android takes the intent, before the app it goes to has
    started; on the emulators Gmail could take longer than the case waits to show up in
    dumpsys. Android cuts an https address after its host (`dat=https://github.com/...`)."""
    return any("act=%s" % action in line and ("dat=%s" % data) in line
               for line in adb("logcat", "-d").splitlines() if "START u0" in line)


def link_started(host):
    """Whether a browser was started on an https address at this host. As much as can be read
    of a link off a phone other than the test phone, whose browser shows the whole address."""
    return started("android.intent.action.VIEW", "https://%s/" % host)


def put_away_other_apps():
    """Closes whatever app a case sent the user to, if it is not InvMgr. Off the test phone that
    is a browser or a mail app nobody has set up, left open on a first-run screen."""
    front = resumed_package()
    if front and front not in (PKG, "android"):
        shell("am force-stop " + front)


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
    needs_the_test_phone(SAMSUNG_PAD)
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
    needs_the_test_phone(SAMSUNG_PAD)
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
    needs_the_test_phone(SAMSUNG_PAD)
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
    needs_the_test_phone(SAMSUNG_PAD)
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
    needs_the_test_phone(SAMSUNG_PAD)
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
    needs_the_test_phone(SAMSUNG_PAD)
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
    needs_the_test_phone(SAMSUNG_PAD)
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
    needs_the_test_phone(SAMSUNG_PAD)
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


# ---- a word still being typed (2026-09-26) --------------------------------------------
#
# Until the word gets a space, the keyboard is still composing it. When the composition ends,
# Android's BaseInputConnection hands the whole word back as a key event, and the app used to
# type it a second time: into the same box on a space, into the next box on a focus change.
# See InvMgrActivity.JavaFxInputConnection.resending.

@case("keyboard: a word still being typed stays in Name when Width is tapped")
def composing_stays_in_its_box():
    """Width then gets ".5", which makes the check possible at all: Confirm reads a Width it
    cannot parse as 12, so "12abcd" and "12" would store the same item. "12.5" and "12abcd.5"
    do not."""
    needs_the_test_phone(SAMSUNG_PAD)
    launch()
    tap(*UI.ADD)
    dlg("name")
    type_letters("abcd")
    dlg("width")
    type_digits(".5")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and s["items"][0]["name"] == "abcd"
                          and s["items"][0]["w_in"] == 12.5)
    item = seen["items"][0] if seen and seen["items"] else {}
    expect(item.get("name") == "abcd" and item.get("w_in") == 12.5,
           "expected a 12.5in item called 'abcd', autosave says name=%r w_in=%s. Before the fix"
           " this read 12, because Width held '12abcd.5': the word followed focus into it"
           % (item.get("name"), item.get("w_in")))


@case("keyboard: a space after a word in Name types the word once")
def space_types_the_word_once():
    launch()
    tap(*UI.ADD)
    dlg("name")
    type_letters("abc")
    tap(*UI.SPACE)
    type_letters("de")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and s["items"][0]["name"] == "abc de")
    got = seen["items"][0]["name"] if seen and seen["items"] else "no item"
    expect(got == "abc de", "typing 'abc de' into Name should store it, autosave says %r" % got)


# ---- the keypad's Enter ---------------------------------------------------------------

@case("the keypad's Enter moves to the next number field")
def enter_moves_on():
    needs_the_test_phone(SAMSUNG_PAD)
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


@case("keyboard: Enter in the dialog's Name brings up the number pad for Width")
def enter_from_name_keeps_the_keyboard():
    """Found 2026-10-01: Enter in Name moved the focus to Width and put the keyboard away, so
    Width had the focus and nothing to type with. The fix asks for the keyboard again, the way
    the keypad's Next already did."""
    needs_the_test_phone(SAMSUNG_PAD)
    launch()
    tap(*UI.ADD)
    dlg("name")
    type_letters("box")
    tap(*UI.ENTER)
    time.sleep(1.0)
    expect(keyboard_showing(), "the keyboard went away after Enter in Name")
    expect_type(NUMBER_DECIMAL, "Width, after Enter in Name,")
    type_digits("7")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"] and s["items"][0]["w_in"] == 7)
    item = seen["items"][0] if seen and seen["items"] else {}
    expect(item.get("w_in") == 7 and item.get("name") == "box",
           "Enter in Name then 7 should make a 7 in wide box named 'box'; autosave says %s"
           % ({k: item.get(k) for k in ("name", "w_in")},))


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
        [*ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", str(int(seconds)),
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


def is_border(pixel):
    """The app's #555 border color, give or take the screenshot's rounding."""
    return all(abs(channel - 0x55) <= 3 for channel in pixel)


def items_tab_right_edge(image):
    """How far right the item-list tab's borders run, in real pixels, or None if no tab.

    The tab sits against the right-hand edge of whatever width the app has, and it has no
    border on that side, so its top and bottom #555 borders run right up to that edge and
    stop. Only a horizontal run of border at least 40 px long counts. That rules out the
    island's buttons, which sit above the band scanned, and the navigation bar's own light
    icons, whose anti-aliased edges pass for #555 a few pixels at a time. The first version
    counted single pixels and found the home button at x=2164.
    """
    width, height = image.size
    edge = None
    for y in range(height // 4, height * 3 // 4):
        run = 0
        for x in range(width - 400, width):
            run = run + 1 if is_border(image.getpixel((x, y))) else 0
            if run >= 40 and (edge is None or x > edge):
                edge = x
    return edge


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

        # ⚠ THIS USED TO FIND THE APP'S EDGE BY THE NAVIGATION BAR BEING LIGHT, and the bar was
        # only light because of a bug: from when this project replaced Gluon's Android host code
        # with its own until 2026-09-24 the window painted the default theme's #f2f2f2 over it.
        # With the bar transparent again, the app's own dark pixels reach the glass either way, so
        # the old test could no longer tell the two layouts apart. The tab's borders say where
        # the app thinks its edge is, whatever the bar looks like: 144 px short of the glass when
        # the bar's width is reserved, at the glass when not.
        edge = items_tab_right_edge(image)
        expect(edge is not None, "no item-list tab on the right-hand side of the screen")
        expect(width - 150 <= edge <= width - 140,
               "the item-list tab's borders run to x=%d of %d. The navigation bar is 144 px, so"
               " they should stop between %d and %d: further right means the app is drawing"
               " underneath the bar, further left means something else is eating the width"
               % (edge, width, width - 150, width - 140))
    finally:
        set_rotation(0)


HOLE = "com.android.internal.display.cutout.emulation.hole"


def reserved_insets():
    """The app's last reserved insets in its own units, from its "reserving SystemInsets" line."""
    match = re.search(r"top=([\d.]+), right=([\d.]+), bottom=([\d.]+), left=([\d.]+)",
                      last_reserved())
    return tuple(float(v) for v in match.groups()) if match else None


@case("cutout: sideways, the app keeps clear of a camera hole")
def cutout_kept_clear():
    """From Android 15, for an app targeting 35 or later, Android lays the window into the camera
    cutout, where before it kept the app out of it when sideways. So the app has to reserve the
    cutout itself, and InvMgrActivity.systemInsets asks for it with the bars. Checked with the
    punch hole Android can simulate: sideways, the hole's strip down one edge must be at most
    what the app reserves on that edge. A screenshot does not draw a simulated cutout, so the
    hole is read from dumpsys window."""
    if sdk_level() < 35:
        raise Skipped("before Android 15, Android keeps the app out of the cutout itself")
    if HOLE not in shell("cmd overlay list"):
        raise Skipped("needs Android's simulated punch hole, which this device does not list")
    shell("cmd overlay enable " + HOLE)
    try:
        time.sleep(3.0)
        launch()
        set_rotation(1)
        time.sleep(2.0)
        match = re.search(r"mDisplayCutout=DisplayCutout\{insets=Rect\((\d+), (\d+) - (\d+), (\d+)\)",
                          shell("dumpsys window"))
        expect(match is not None, "dumpsys window shows no cutout with the hole switched on")
        left, top, right, bottom = (int(v) for v in match.groups())
        expect(left or right, "sideways, the hole should take a strip down the left or right edge;"
                              " dumpsys says Rect(%d, %d - %d, %d)" % (left, top, right, bottom))
        density = int(re.search(r"(\d+)\s*$", shell("wm density").strip()).group(1)) / 160
        reserved = reserved_insets()
        expect(reserved is not None, "the app printed no reserved insets")
        app_left, app_right = reserved[3] * density, reserved[1] * density
        expect(app_left >= left - 1 and app_right >= right - 1,
               "the hole takes %d px on the left and %d on the right; the app reserves %.0f and %.0f,"
               " so part of it draws under the camera" % (left, right, app_left, app_right))
    finally:
        set_rotation(0)
        shell("cmd overlay disable " + HOLE)
        time.sleep(2.0)


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


def through_the_task_switcher():
    """Out to the phone's task switcher and back into the app.

    ⚠ Back in by starting the app, NOT by pressing the task-switcher key a second time. That
    second press opens whichever card the switcher has centered, and Samsung centers the app used
    BEFORE this one: the moment anything else was recent (Brave, after About's Source code case)
    it opened that instead, and both keyboard cases failed on "the app did not come back". Starting
    the app brings its existing task forward, and the window arrives the way the bug needs it to,
    marked IS_FORWARD_NAVIGATION, checked in dumpsys when this was changed (2026-09-25).
    """
    keyevent("KEYCODE_APP_SWITCH", settle=2.5)
    shell("am start -n " + ACT)
    time.sleep(3.0)
    expect(resumed_package() == PKG, "the app did not come back to the front after the task switcher")


@case("keyboard: it stays down after the task switcher once no box wants it")
def keyboard_stays_down_after_switcher():
    """The user's report of 2026-09-24: type in a box, close the dialog it was in, go to the
    task switcher and back, and the keyboard was up again over a room with no box on it. It was
    Android showing it, not the app: dumpsys read mShowExplicitlyRequested=false. See
    keyboardWanted in InvMgrActivity."""
    launch()
    tap(*UI.ADD)
    time.sleep(1.2)
    dlg("name")
    time.sleep(1.5)
    expect(keyboard_showing(), "tapping the dialog's Name box brought up no keyboard")
    dlg("cancel")
    time.sleep(2.0)
    expect(not keyboard_showing(), "the keyboard stayed up after the dialog closed")
    through_the_task_switcher()
    expect(not keyboard_showing(),
           "the keyboard came back after the task switcher, with no box on screen to type in")


@case("keyboard: it comes back after the task switcher while a box is still being typed in")
def keyboard_returns_while_typing():
    """The other half of the fix above, and the reason it is not simply "never show it on the
    way back": leave in the middle of typing a name, and the box is still there, still focused,
    and still wants the keyboard."""
    launch()
    tap(*UI.ADD)
    time.sleep(1.2)
    dlg("name")
    time.sleep(1.5)
    expect(keyboard_showing(), "tapping the dialog's Name box brought up no keyboard")
    through_the_task_switcher()
    expect(keyboard_showing(),
           "the keyboard did not come back after the task switcher, with the Name box still"
           " open and focused")
    # And it still types into that box: the connection survived the trip.
    type_letters("ab")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"])
    expect(seen is not None and seen["items"] and seen["items"][0]["name"] == "ab",
           "the keyboard came back but typing did not reach the Name box; last items seen: %r"
           % (seen and seen.get("items")))


# ---------------------------------------------------------------- the phone's own bars
#
# From when this project replaced Gluon's Android host code with its own until 2026-09-24 the
# window painted the default theme's opaque bars over the app: a black status bar and a #f2f2f2
# navigation bar with dark icons, in both views. Nothing noticed for three weeks, because nothing
# here looked at the bars except a landscape case that had come to depend on the navigation bar
# being light. See InvMgrActivity.setEdgeToEdge.

# What the 2D view draws under the two bars: Tokens.TOP_BAR_BG under the status bar, and
# Tokens.BODY_BG, the app's own status bar, under the navigation bar. Both are grays.
TOP_BAR_GRAY = 0x25
BODY_GRAY = 0x1a


def window_flags():
    """The system UI flags Android holds for the app's window, from `dumpsys window`, or None."""
    dump = shell("dumpsys window windows")
    block = re.search(r"Window\{[^}]*%s/[^}]*\}:(.*?)(?=\n  Window #|\Z)" % re.escape(PKG),
                      dump, re.S)
    found = block and re.search(r"mSystemUiVisibility=0x([0-9a-f]+)", block.group(1))
    return int(found.group(1), 16) if found else None


def dark_bar_icons():
    """Which bars draw dark icons, as a list of names, or None when dumpsys has no record.

    Below API 30 both come from the window's own flags. From API 30 the app sets them through
    the insets controller and the flags stay 0; the only record found on the Android 16
    emulator is the status bar's, `AppearanceRegion{LIGHT_STATUS_BARS bounds=...}` in dumpsys
    window when its icons are dark and `AppearanceRegion{ bounds=...}` when they are light. The
    navigation bar's shade was not found anywhere there, so from API 30 only the status bar
    is checked.
    """
    if sdk_level() < 30:
        flags = window_flags()
        if flags is None:
            return None
        # View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR and _LIGHT_NAVIGATION_BAR: set means dark icons
        return [name for bit, name in ((0x8, "status"), (0x10, "navigation")) if flags & bit]
    match = re.search(r"mLastStatusBarAppearanceRegions=\s*\n\s*AppearanceRegion\{([^}]*)\}",
                      shell("dumpsys window"))
    if not match:
        return None
    return ["status"] if "LIGHT_STATUS_BARS" in match.group(1) else []


def painted_bars():
    """Which of Android's own bar backgrounds the app's window is drawing over itself, or None.

    A window draws a bar's background as a view of its own, on top of everything the app
    draws. On this phone it holds those views only while the bar has a color: the builds
    on Gluon's host code and the fixed one have neither, and every build between has both, visible.
    """
    dump = shell("dumpsys activity top")
    # "TASK com.asterkazika.invmgr" on the test phone, "TASK 10216:com.asterkazika.invmgr" on
    # the Android 16 emulator
    ours = re.split(r"TASK (?:\d+:)?%s\b" % re.escape(PKG), dump, maxsplit=1)
    if len(ours) < 2:
        return None
    section = ours[1].split("\nTASK ", 1)[0]
    return [name for name in ("statusBarBackground", "navigationBarBackground")
            if re.search(r"\{[0-9a-f]+ V[^}]*android:id/" + name, section)]


def bar_strips(sideways):
    """Where to sample each bar, and the strip of app just inside it, as (columns, rows).

    Clear of the phone's own icons: the clock and the status icons at either end of the
    status bar, and the three navigation buttons.
    """
    if sideways:
        rows = [*range(80, 160, 4), *range(360, 460, 4), *range(640, 740, 4), *range(960, 1070, 4)]
        return ((range(400, 1800, 8), range(4, 68, 4)),
                (range(400, 1800, 8), range(76, 90, 2)),
                (range(2084, 2214, 4), rows),
                (range(2050, 2070, 2), rows))
    columns = [*range(20, 160, 4), *range(360, 440, 4), *range(640, 720, 4), *range(940, 1060, 4)]
    return ((range(330, 620, 4), range(4, 68, 4)),
            (range(330, 620, 4), range(76, 90, 2)),
            (columns, range(2082, 2214, 4)),
            (columns, range(2056, 2072, 2)))


def median_gray(image, columns, rows):
    """The median red channel over a grid of points. A median, so a stray icon is ignored."""
    values = sorted(image.getpixel((x, y))[0] for x in columns for y in rows)
    return values[len(values) // 2]


def expect_bars_show_the_app(where, image, sideways, in_3d):
    painted = painted_bars()
    expect(painted is not None, "%s dumpsys lists no task for %s" % (where, PKG))
    expect(not painted,
           "%s the app's window draws Android's own %s over the app, so that bar has a color of"
           " its own. InvMgrActivity.setEdgeToEdge is what makes both transparent"
           % (where, " and ".join(painted)))
    dark = dark_bar_icons()
    expect(dark is not None, "%s dumpsys has no record of the bars' icons for %s" % (where, PKG))
    expect(not dark, "%s the %s bar's icons are dark, over the app's dark interface"
           % (where, " and ".join(dark)))

    status, under_status, nav, beside_nav = bar_strips(sideways)
    status_gray, nav_gray = median_gray(image, *status), median_gray(image, *nav)
    if in_3d:
        # The room runs on under both bars, so each bar reads like the room beside it. An
        # opaque bar is 14 gray levels off at the top (black over the ceiling) and over 200 at
        # the bottom (#f2f2f2 over the floor).
        under_gray = median_gray(image, *under_status)
        expect(under_gray < 30,
               "%s the 3D view did not open: the app under the status bar reads gray %d, which is"
               " the 2D top bar's %d" % (where, under_gray, TOP_BAR_GRAY))
        for name, bar_gray, room_gray in (("status", status_gray, under_gray),
                                          ("navigation", nav_gray, median_gray(image, *beside_nav))):
            expect(abs(bar_gray - room_gray) <= 8,
                   "%s the %s bar reads gray %d where the room beside it reads %d: something"
                   " opaque is drawn over the room there" % (where, name, bar_gray, room_gray))
    else:
        expect(abs(status_gray - TOP_BAR_GRAY) <= 2,
               "%s the status bar reads gray %d; the top bar under it is %d (Tokens.TOP_BAR_BG)"
               % (where, status_gray, TOP_BAR_GRAY))
        expect(abs(nav_gray - BODY_GRAY) <= 2,
               "%s the navigation bar reads gray %d; the app under it is %d (Tokens.BODY_BG)"
               % (where, nav_gray, BODY_GRAY))


@case("bars: the 2D view shows through the phone's bars, with light icons, both ways up")
def bars_over_2d():
    from PIL import Image
    launch()
    try:
        expect_bars_show_the_app(
            "2D upright,", Image.open(screenshot("bars-2d-upright")).convert("RGB"), False, False)
        set_rotation(1)
        expect_bars_show_the_app(
            "2D on its side,", Image.open(screenshot("bars-2d-sideways")).convert("RGB"), True, False)
    finally:
        set_rotation(0)


@case("bars: the 3D room runs on under the phone's bars, with light icons, both ways up")
def bars_over_3d():
    from PIL import Image
    launch()
    try:
        open_top_bar()
        tap(*UI.THREE_D, settle=6.0)             # the flight down into the room takes a moment
        expect_bars_show_the_app(
            "3D upright,", Image.open(screenshot("bars-3d-upright")).convert("RGB"), False, True)
        set_rotation(1)
        expect_bars_show_the_app(
            "3D on its side,", Image.open(screenshot("bars-3d-sideways")).convert("RGB"), True, True)
    finally:
        set_rotation(0)


# ---------------------------------------------------------------------- the Menu

# The Menu window's edge in real pixels on this phone: 334 design pixels at density 3. Both
# ways up, which is the user's rule; MenuWindow.phoneSide works the number out.
MENU_SIDE = 3 * 334


def find_menu_panel(image, left, top, right, bottom, shortest=None):
    """The Menu window's outer edge on a screenshot, as (x0, y0, x1, y1), or None.

    Found by its SHAPE, not by its color alone: the window's border is the only #555 line on the
    screen longer than half the window. Color alone failed both ways on the first run. With the
    window open, the island's white glyphs under the darkening come out #595959, close enough to
    count; with it closed, the island's own #555 button borders are back at full strength. The
    island's longest border is 178 real pixels and the window's is 1002, so a run length rules
    both out.

    ⚠ Scanned only inside the box given, which is the part of the glass the app has. Android
    draws its own clock and navigation buttons over the rest.
    """
    if shortest is None:
        shortest = MENU_SIDE // 2
    rows = [y for y in range(top, bottom)
            if sum(1 for x in range(left, right, 2) if is_border(image.getpixel((x, y))))
            * 2 >= shortest]
    columns = [x for x in range(left, right)
               if sum(1 for y in range(top, bottom, 2) if is_border(image.getpixel((x, y))))
               * 2 >= shortest]
    if not rows or not columns:
        return None
    return (min(columns), min(rows), max(columns), max(rows))


def menu_panel(name, sideways=False):
    from PIL import Image
    image = Image.open(screenshot(name)).convert("RGB")
    width, height = image.size
    # What the phone's bars take, in real pixels: 72 at the top, 144 at the bottom upright or
    # down the right-hand side on its side.
    if sideways:
        return find_menu_panel(image, 0, 72, width - 144, height)
    return find_menu_panel(image, 0, 72, width, height - 144)


# ---------------------------------------------------------------------- Reset Room

# The Reset Room window: 300 design pixels wide. Its border is found the way the Menu's is, with
# the shortest run it may have set by its own width rather than the Menu's.
RESET_WIDTH = 3 * 300

# Where the Menu's entries sit before any scrolling, as their middles' distance below the window's
# top edge, in design pixels: the window's 24 px padding, the 41 px title row and its 12 px margin,
# then 40 px entries 8 px apart. Metric, Theme, Settings, Feedback, About, Reset Room. Since the
# Feedback entry was added on 2026-09-26 only the first five are on screen: the page shows 233 px
# of its 280 and scrolls.
def menu_entry_below_top(index):
    return 24 + 41 + 12 + index * (40 + 8) + 20


# Reset Room's red, BUTTON_DANGER_BG. Once the Menu has scrolled, where the entry sits depends on
# how far the scroll went, so it is found by its fill rather than worked out.
DANGER_FILL = (0x5a, 0x20, 0x20)


def near(pixel, color, tolerance=8):
    return all(abs(c - t) <= tolerance for c, t in zip(pixel, color))


def scroll_menu_to_end(panel):
    """A finger dragged up the Menu's entries, far enough to reach the bottom of the list. It
    starts on the About entry, so it also checks that a swipe opens nothing (DragCancelsTap)."""
    x = panel[0] + MENU_SIDE // 2
    shell("input swipe %d %d %d %d 400" % (x, panel[1] + 3 * 280, x, panel[1] + 3 * 90))
    time.sleep(1.5)


def danger_entry_middle(name, panel):
    """The middle of the red Reset Room entry on a fresh screenshot, or None if it is not there."""
    from PIL import Image
    image = Image.open(screenshot(name)).convert("RGB")
    # 6 design pixels inside the entry's left edge: on its fill, left of its words.
    x = panel[0] + 3 * 30
    rows = [y for y in range(panel[1], panel[3]) if near(image.getpixel((x, y)), DANGER_FILL)]
    if not rows:
        return None
    return panel[0] + MENU_SIDE // 2, (rows[0] + rows[-1]) // 2


def reset_dialog(name):
    """The Reset Room window's edge and its buttons' centers, left to right, or (None, [])."""
    from PIL import Image
    image = Image.open(screenshot(name)).convert("RGB")
    width, height = image.size
    panel = find_menu_panel(image, 0, 72, width, height - 144, shortest=RESET_WIDTH // 2)
    if panel is None:
        return None, []
    # A row 3 design pixels above the buttons' bottom edge: inside them, below their words, so
    # the only border-colored pixels on it are their left and right sides. Reset's border is the
    # danger red #833 rather than #555, so both count.
    y = panel[3] - 3 * (24 + 3)

    def edge(pixel):
        return is_border(pixel) or all(abs(c - t) <= 6 for c, t in zip(pixel, (0x88, 0x33, 0x33)))

    xs = [x for x in range(panel[0] + 10, panel[2] - 10) if edge(image.getpixel((x, y)))]
    runs = []
    for x in xs:
        if runs and x - runs[-1][-1] <= 2:
            runs[-1].append(x)
        else:
            runs.append([x])
    sides = [(run[0] + run[-1]) // 2 for run in runs]
    buttons = [((sides[i] + sides[i + 1]) // 2, y) for i in range(0, len(sides) - 1, 2)]
    return panel, buttons


def open_reset_room():
    """From the room: the Menu, then its Reset Room entry. Returns the window's buttons."""
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("reset-menu")
    expect(panel is not None, "the Menu window did not open")
    scroll_menu_to_end(panel)
    entry = danger_entry_middle("reset-menu-scrolled", panel)
    expect(entry is not None, "after scrolling the Menu to its end, no red Reset Room entry shows")
    tap(*entry)
    time.sleep(1.0)
    dialog, buttons = reset_dialog("reset-ask")
    expect(dialog is not None, "tapping the Menu's Reset Room entry opened no Reset Room window")
    # The Reset Room window's 900, not the Menu's 1002: the window on screen is the new one.
    expect(abs(dialog[2] - dialog[0] + 1 - RESET_WIDTH) <= 4,
           "the window on screen is %d real pixels wide; the Reset Room window is %d"
           % (dialog[2] - dialog[0] + 1, RESET_WIDTH))
    expect(len(buttons) == 3,
           "the first Reset Room window should have Cancel, No and Yes; found %d buttons"
           % len(buttons))
    return buttons


def add_one_item():
    tap(*UI.ADD)
    time.sleep(1.2)
    confirm_dialog()
    seen = wait_for_state(lambda s: len(s["items"]) == 1)
    expect(seen is not None, "adding an item to reset did not work")


# Android's file picker. AOSP calls it com.android.documentsui, which is what the test phone has;
# Google's own builds, the emulator images among them, ship it as com.google.android.documentsui.
PICKER_PACKAGES = ("com.android.documentsui", "com.google.android.documentsui")


def picker_save_button():
    """Android's save screen's SAVE button, read from its own layout, or None if it is not up."""
    # ⚠ The id is android:id/button1, the framework's generic dialog button, NOT one of
    # DocumentsUI's own; the first version of this looked for com.android.documentsui:id/button1
    # and found nothing while the picker sat on screen. So the package is checked as well.
    xml = shell("uiautomator dump /sdcard/invmgr-ui.xml >/dev/null && cat /sdcard/invmgr-ui.xml")
    for node in re.findall(r'<node [^>]*>', xml):
        if ('resource-id="android:id/button1"' in node
                and any('package="%s"' % p in node for p in PICKER_PACKAGES)):
            match = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if match:
                x0, y0, x1, y1 = (int(v) for v in match.groups())
                return ((x0 + x1) // 2, (y0 + y1) // 2)
    return None


@case("reset: No, then Reset, empties the room")
def reset_no_then_reset():
    launch()
    add_one_item()
    cancel, no, yes = open_reset_room()
    tap(*no)
    time.sleep(1.0)
    dialog, buttons = reset_dialog("reset-confirm")
    expect(dialog is not None and len(buttons) == 2,
           "after No, the last window should have Cancel and Reset; found %d buttons"
           % len(buttons))
    tap(*buttons[1])
    seen = wait_for_state(lambda s: s["items"] == [])
    expect(seen is not None and seen["items"] == [],
           "Reset did not empty the room; the autosave still lists %r"
           % (seen and seen.get("items")))


@case("reset: backing out of the file picker comes back to the first window")
def reset_back_out_of_picker():
    launch()
    add_one_item()
    cancel, no, yes = open_reset_room()
    tap(*yes)
    time.sleep(3.0)
    expect(picker_save_button() is not None, "Yes did not open Android's save screen")
    keyevent("KEYCODE_BACK", settle=2.0)
    # Waited for rather than read once: coming back from the picker gives the app a new drawing
    # surface, and on the Android 15 emulator the screen was still black at two seconds in two
    # runs of three, then showed the window. Timed there at about a second, and up to eight here.
    deadline = time.time() + 6.0
    dialog, buttons = reset_dialog("reset-after-back-out")
    while (dialog is None or len(buttons) != 3) and time.time() < deadline:
        time.sleep(1.0)
        dialog, buttons = reset_dialog("reset-after-back-out")
    expect(dialog is not None and len(buttons) == 3,
           "backing out of the save screen should show Cancel, No and Yes again; found %s"
           % ("no window" if dialog is None else "%d buttons" % len(buttons)))
    seen = state()
    expect(seen is not None and len(seen["items"]) == 1, "and the room was left alone")


@case("reset: Yes saves a file first, then Reset empties the room (writes to Downloads)",
      opt_in=True)
def reset_saves_first():
    """⚠ Opt in: it leaves a new room_inventory.json in the phone's Downloads every run, because
    Android renames a duplicate rather than replacing it. Run it by naming it:
    device_suite.py "saves a file first"."""
    launch()
    add_one_item()
    cancel, no, yes = open_reset_room()
    tap(*yes)
    time.sleep(3.0)
    save = picker_save_button()
    expect(save is not None, "Yes did not open Android's save screen")
    tap(*save)
    time.sleep(3.0)
    dialog, buttons = reset_dialog("reset-after-save")
    expect(dialog is not None and len(buttons) == 2,
           "after a save, the last window should have Cancel and Reset; found %s"
           % ("no window" if dialog is None else "%d buttons" % len(buttons)))
    tap(*buttons[1])
    seen = wait_for_state(lambda s: s["items"] == [])
    expect(seen is not None and seen["items"] == [], "Reset did not empty the room")


# ---------------------------------------------------------------------------- About

ABOUT_ENTRY_BELOW_TOP = menu_entry_below_top(4)

# The About page's Source code entry. Not worked out here: printed by
# TouchLayoutTest.theAboutPagesOnAPhone from the real layout, because the version line and the
# three-line description above it are text, and text heights are exactly what arithmetic gets wrong.
ABOUT_SOURCE_BELOW_TOP = 186


def open_about():
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("about-menu")
    expect(panel is not None, "the Menu window did not open")
    tap(panel[0] + MENU_SIDE // 2, panel[1] + 3 * ABOUT_ENTRY_BELOW_TOP)
    time.sleep(1.0)
    return panel


def resumed_package():
    for line in shell("dumpsys activity activities").splitlines():
        # mResumedActivity on the test phone; Android 16 prints topResumedActivity instead
        if "mResumedActivity" in line or "topResumedActivity" in line:
            match = re.search(r"u0 ([\w.]+)/", line)
            return match.group(1) if match else line.strip()
    return None


@case("about: About opens in the Menu's own square")
def about_opens():
    launch()
    open_about()
    panel = menu_panel("about-page")
    expect(panel is not None, "tapping About closed the window instead of opening a page in it")
    expect_side(panel, "on the About page,")


@case("about: the phone's Back goes back to the Menu first, then closes it")
def about_back_steps_back():
    """Back means back one page on a phone. On the Menu itself it closes the window."""
    launch()
    open_about()
    keyevent("KEYCODE_BACK", settle=1.0)
    expect(menu_panel("about-back-once") is not None,
           "Back on the About page closed the whole window; it should go back to the Menu")
    keyevent("KEYCODE_BACK", settle=1.0)
    expect(menu_panel("about-back-twice") is None, "and Back on the Menu should close it")
    expect(shell("pidof " + PKG).strip() != "", "without closing the app")


@case("about: Source code opens this version's Release page in the phone's browser")
def about_source_code():
    """About's link out of the app. It opens this version's Release page,
    github.com/ModCritic/InvMgrApp/releases/tag/v<version>, in whatever browser the phone uses,
    which then loads it: the browser's connection, not this app's.

    ⚠ It comes back with `am start`, not Back. Brave keeps a tab for an address it has already
    been sent, and Back from a reused tab stays in Brave (seen 2026-09-25, on the suite's second
    run of the day). This address never changes, so from the second run on it always would."""
    launch()
    panel = open_about()
    tap(panel[0] + MENU_SIDE // 2, panel[1] + 3 * ABOUT_SOURCE_BELOW_TOP)
    time.sleep(4.0)
    front = resumed_package()
    expect(front is not None and front != PKG,
           "tapping Source code left InvMgr in front (%s); a browser should have opened" % front)
    # Android's own "open with" chooser runs under the package called just "android"; a phone
    # with no default browser would show that instead of a browser.
    expect(front != "android",
           "Android asked which app to open the link with instead of opening a browser")
    if on_test_phone():
        address = [text for text in window_text() if "github.com" in text]
        release = "github.com/ModCritic/InvMgrApp/releases/tag/v" + app_version()
        expect(release in address,
               "the browser's address bar should show %s; it shows %r" % (release, address))
    else:
        expect(link_started("github.com"), "no browser was started on github.com")
        put_away_other_apps()
    shell("am start -n " + ACT)
    time.sleep(3.0)
    expect(resumed_package() == PKG, "InvMgr did not come back to the front")


# -------------------------------------------------------------------------- Settings

SETTINGS_ENTRY_BELOW_TOP = menu_entry_below_top(2)

# The Hide system bars row: the page's padding, title row and margin, then half of a 40 px row.
# Its switch sits at the right-hand end; aimed about 50 design pixels in from the window's edge.
HIDE_BARS_BELOW_TOP = 24 + 41 + 12 + 20
HIDE_BARS_IN_FROM_RIGHT = 50

# View.SYSTEM_UI_FLAG_HIDE_NAVIGATION, _FULLSCREEN and _IMMERSIVE_STICKY: what the Activity sets
# below API 30 to hide the bars. Read back out of dumpsys window's mLastSystemUiFlags, which is
# the focused window's own record of them.
HIDE_NAVIGATION = 0x2
FULLSCREEN = 0x4
IMMERSIVE_STICKY = 0x1000

# View's hidden STATUS_BAR_TRANSIENT and NAVIGATION_BAR_TRANSIENT: Android sets both while a swipe
# has the bars out. Measured 2026-09-25: 0x970e with the bars hidden, 0xc00970e for about a second
# and a half after a swipe from the top, then 0x970e again.
BARS_TRANSIENT = 0x0c000000


def system_ui_flags():
    for line in shell("dumpsys window").splitlines():
        match = re.search(r"mLastSystemUiFlags=0x([0-9a-f]+)", line)
        if match:
            return int(match.group(1), 16)
    return None


_SDK = []


def sdk_level():
    """The device's API level, read once."""
    if not _SDK:
        _SDK.append(int(shell("getprop ro.build.version.sdk").strip() or 0))
    return _SDK[0]


Bars = collections.namedtuple("Bars", "hidden out seen")


def bar_state():
    """Whether the bars are hidden, whether a swipe has them out for a moment, and what was read.

    Below API 30 the Activity hides them with the view flags above, and dumpsys window keeps
    the focused window's own copy: hidden means all three set, out means both transient bits.
    From API 30 it asks the insets controller instead, which sets none of those flags (they
    read 0x0 on Android 16), so the answer is the system's own record of each bar,
    `InsetsSource ... type=statusBars ... visible=false` and the same for navigationBars.
    A swipe shows them as visible=true for a few seconds (measured on the Android 16 emulator
    2026-09-29), and there is no separate record of the setting underneath, so there out is
    simply not hidden.
    """
    if sdk_level() < 30:
        flags = system_ui_flags() or 0
        wanted = HIDE_NAVIGATION | FULLSCREEN | IMMERSIVE_STICKY
        return Bars(flags & wanted == wanted, flags & BARS_TRANSIENT == BARS_TRANSIENT,
                    "flags 0x%x" % flags)
    seen = {}
    for line in shell("dumpsys window").splitlines():
        match = re.search(r"InsetsSource id=\S+ type=(statusBars|navigationBars) .*visible=(true|false)",
                          line)
        if match:
            seen.setdefault(match.group(1), set()).add(match.group(2))
    hidden = seen.get("statusBars") == {"false"} and seen.get("navigationBars") == {"false"}
    shown = "true" in seen.get("statusBars", set()) | seen.get("navigationBars", set())
    return Bars(hidden, shown, "bars visible: %s" % {k: sorted(v) for k, v in seen.items()})


def last_reserved():
    """The app's own last "reserving SystemInsets[...]" line, which it prints on every change."""
    lines = [l for l in adb("logcat", "-d").splitlines() if "reserving SystemInsets" in l]
    return lines[-1] if lines else ""


def toggle_hide_bars():
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("settings-menu")
    expect(panel is not None, "the Menu window did not open")
    tap(panel[0] + MENU_SIDE // 2, panel[1] + 3 * SETTINGS_ENTRY_BELOW_TOP)
    time.sleep(1.0)
    panel = menu_panel("settings-page")
    expect(panel is not None, "tapping Settings closed the window instead of opening its page")
    tap(panel[2] - 3 * HIDE_BARS_IN_FROM_RIGHT, panel[1] + 3 * HIDE_BARS_BELOW_TOP)
    time.sleep(1.5)
    keyevent("KEYCODE_BACK", settle=0.8)            # back to the Menu
    keyevent("KEYCODE_BACK", settle=1.0)            # and closed


@case("settings: Hide system bars hides both, and the room takes their space")
def settings_hide_bars():
    launch()
    expect(not bar_state().hidden, "the bars start hidden on a fresh install")
    toggle_hide_bars()
    bars = bar_state()
    expect(bars.hidden, "after the switch the bars are not hidden: %s" % bars.seen)
    expect("top=0.0, right=0.0, bottom=0.0, left=0.0" in last_reserved(),
           "the app still reserves space for the bars: %s" % last_reserved())


@case("settings: a restart keeps the bars hidden")
def settings_survive_restart():
    launch()
    toggle_hide_bars()
    expect(bar_state().hidden, "the switch did not hide the bars")
    launch(clean=False)                             # the settings file stays this time
    expect(bar_state().hidden, "after a restart the bars are back: %s" % bar_state().seen)
    expect("top=0.0, right=0.0, bottom=0.0, left=0.0" in last_reserved(),
           "after a restart the app reserves space for bars that are hidden: %s"
           % last_reserved())


@case("settings: a swipe from the top brings the bars back for a moment")
def settings_swipe_reveals():
    launch()
    toggle_hide_bars()
    shell("input swipe 540 1 540 700 300")
    time.sleep(0.5)
    revealed = bar_state()
    expect(revealed.out, "a swipe in from the top edge did not bring the bars out: %s" % revealed.seen)
    if sdk_level() < 30:
        expect(revealed.hidden, "and the setting underneath should be untouched: %s" % revealed.seen)
    time.sleep(4.0)
    settled = bar_state()
    expect(settled.hidden and not settled.out,
           "a few seconds later the bars should be hidden again: %s" % settled.seen)


@case("settings: the bars stay hidden after leaving the app and coming back")
def settings_survive_leaving():
    """Android clears the hiding flags when the window goes away and comes back; the Activity
    puts them back in onWindowFocusChanged. Without that, this failed on all three routes out:
    0x970e before, 0x9708 after. See InvMgrActivity.onWindowFocusChanged."""
    launch()
    toggle_hide_bars()
    expect(bar_state().hidden, "the switch did not hide the bars")

    tap(UI.FOLD[0], UI.FOLD[1] - 72)                # the top bar, one bar-height higher
    time.sleep(1.5)
    tap(UI.SAVE[0], UI.SAVE[1] - 72)
    time.sleep(3.0)
    expect(resumed_package() in PICKER_PACKAGES, "Save did not open the file picker")
    keyevent("KEYCODE_BACK", settle=2.5)
    expect(bar_state().hidden, "back from the file picker the bars are showing: %s" % bar_state().seen)

    keyevent("KEYCODE_HOME", settle=2.0)
    shell("am start -n " + ACT)
    time.sleep(3.0)
    expect(bar_state().hidden, "back from the home screen the bars are showing: %s" % bar_state().seen)


@case("settings: with the bars hidden, the keyboard still types into a dialog")
def settings_keyboard_with_bars_hidden():
    launch()
    toggle_hide_bars()
    tap(*UI.ADD_BARS_HIDDEN)
    time.sleep(1.2)
    dlg("name")
    time.sleep(1.5)
    expect(keyboard_showing(), "tapping the Name box brought up no keyboard")
    type_letters("ab")
    confirm_dialog()
    seen = wait_for_state(lambda s: s["items"])
    expect(seen is not None and seen["items"] and seen["items"][0]["name"] == "ab",
           "typing with the bars hidden did not reach the Name box: %r"
           % (seen and seen.get("items")))


@case("settings: the bars go away again when the keyboard does")
def settings_bars_after_keyboard():
    """Showing the keyboard makes Android clear the hiding flags on this window, 0x970e to
    0x9718, and putting it away leaves them cleared, 0x9708, with both bars over the island and
    the status line. The keyboard never takes the window's focus, so onWindowFocusChanged cannot
    help; InvMgrActivity.rehideBarsAfterKeyboard puts them back from the insets listener.
    Found 2026-09-29 while taking the listing's screenshots."""
    launch()
    toggle_hide_bars()
    expect(bar_state().hidden, "the switch did not hide the bars")
    tap(*UI.ITEMS_TAB_BARS_HIDDEN)
    tap(*UI.SEARCH_BARS_HIDDEN)
    time.sleep(1.5)
    expect(keyboard_showing(), "tapping the search box brought up no keyboard")
    type_letters("ab")
    keyevent("KEYCODE_BACK", settle=2.0)            # the keyboard goes, the search box keeps focus
    expect(not keyboard_showing(), "Back left the keyboard up")
    expect(bar_state().hidden, "with the keyboard gone the bars are still showing: %s" % bar_state().seen)


# -------------------------------------------------------------------------- Feedback

FEEDBACK_ENTRY_BELOW_TOP = menu_entry_below_top(3)

# ScrollHint's thumb: SCROLL_HINT #888, 4 design px wide, centered in the window's 24 px right-hand
# padding (SCROLL_HINT_GAP 10 past the page's edge at 310). So its middle column is 322 design px
# from the window's left edge.
THUMB_GRAY = (0x88, 0x88, 0x88)
THUMB_IN_FROM_LEFT = 334 - 24 + 10 + 2

# The Menu page on this phone, as TouchLayoutTest.theMenusEntriesScroll prints it: the page starts
# 78 design px below the window's top and shows 231 px of 280 px of entries. So the thumb is
# 231 * 231 / 280 = 191 long, and scrolled to the end it reaches the page's bottom at 309.
# Worked out from the padding and the title row these came to 77 and 233; the printed numbers are
# the real ones, which is why they are read rather than added up.
MENU_PAGE_TOP = 78
MENU_PAGE_HEIGHT = 231
MENU_PAGE_BOTTOM = MENU_PAGE_TOP + MENU_PAGE_HEIGHT
MENU_THUMB_LENGTH = MENU_PAGE_HEIGHT * MENU_PAGE_HEIGHT / 280

# The message box: the first thing on its page, about 86 design px tall. Aimed 40 px into it.
MESSAGE_BOX_BELOW_TOP = MENU_PAGE_TOP + 40

# A confirm button's green, BUTTON_CONFIRM_BG: OK on the warning, GitHub and Email on the page.
CONFIRM_FILL = (0x2e, 0x4e, 0x3e)


def thumb_rows(name, panel):
    """The first and last row of ScrollHint's thumb on a fresh screenshot, or None."""
    from PIL import Image
    image = Image.open(screenshot(name)).convert("RGB")
    x = panel[0] + 3 * THUMB_IN_FROM_LEFT
    rows = [y for y in range(panel[1], panel[3]) if near(image.getpixel((x, y)), THUMB_GRAY)]
    return (rows[0], rows[-1]) if rows else None


def green_buttons(name, panel):
    """The middles of the green buttons inside the window on a fresh screenshot, left to right.

    Found by color because the page's buttons sit below text, and move with the keyboard.
    """
    from PIL import Image
    image = Image.open(screenshot(name)).convert("RGB")
    points = sorted((x, y) for y in range(panel[1], panel[3], 3)
                    for x in range(panel[0], panel[2], 3)
                    if near(image.getpixel((x, y)), CONFIRM_FILL))
    clusters = []
    for x, y in points:
        # Buttons in a row are 8 design px apart, 24 real; one button's columns are 3 apart.
        if clusters and x - clusters[-1][-1][0] <= 12:
            clusters[-1].append((x, y))
        else:
            clusters.append([(x, y)])
    return [(sum(p[0] for p in c) // len(c), sum(p[1] for p in c) // len(c))
            for c in clusters if len(c) >= 20]


def open_feedback():
    """From the room: the Menu, then its Feedback entry. Returns the window's edge."""
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("feedback-menu")
    expect(panel is not None, "the Menu window did not open")
    tap(panel[0] + MENU_SIDE // 2, panel[1] + 3 * FEEDBACK_ENTRY_BELOW_TOP)
    time.sleep(1.0)
    return panel


def open_message_page():
    """Feedback, then OK on its warning. Returns the window's edge, wherever the keyboard has put
    it."""
    panel = open_feedback()
    ok = green_buttons("feedback-notice", panel)
    expect(len(ok) == 1, "the warning page should have one green button, OK; found %d" % len(ok))
    tap(*ok[0])
    time.sleep(1.5)
    panel = menu_panel("feedback-page")
    expect(panel is not None, "OK on the warning closed the window instead of opening the page")
    return panel


def tap_message_box(panel):
    """Taps the box, which is what raises the keyboard on a phone; opening the page only focuses
    it (JavaFX's TextAreaSkinAndroid raises the keyboard on a click and nothing else)."""
    tap(panel[0] + MENU_SIDE // 2, panel[1] + 3 * MESSAGE_BOX_BELOW_TOP)
    time.sleep(1.5)
    expect(keyboard_showing(), "a tap on the message box brought up no keyboard")


def write_and_find_buttons(panel, word="hi"):
    """Types a word into the box and returns the page's two buttons: GitHub, then Email."""
    tap_message_box(panel)
    type_letters(word)
    time.sleep(0.5)
    panel = menu_panel("feedback-typing")
    expect(panel is not None, "with the keyboard up, the Feedback page is gone")
    buttons = green_buttons("feedback-typed", panel)
    expect(len(buttons) == 2,
           "the message page should show two green buttons, GitHub and Email; found %d"
           % len(buttons))
    return buttons


def window_text():
    """Every piece of text on screen, from Android's own view of the window in front. A browser's
    address bar is in it, which is the one place the whole address a link opened can be read:
    Android's own records of an intent show only `https://github.com/...`."""
    dump = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    return [html.unescape(text) for text in re.findall(r'text="([^"]*)"', dump)]


@case("feedback: the Menu scrolls on a phone, with a scrollbar that says where it is")
def feedback_menu_scrolls():
    """Six entries are more than the square holds, so the page scrolls, and ScrollHint draws the
    bar that JavaFX draws on a touch screen only once a finger is already scrolling."""
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("scroll-before")
    expect(panel is not None, "the Menu window did not open")
    expect(danger_entry_middle("scroll-before-reset", panel) is None,
           "Reset Room is on screen before any scrolling, so the page is not the one measured")
    thumb = thumb_rows("scroll-before-thumb", panel)
    expect(thumb is not None, "no scrollbar on a Menu page that runs past the square")
    length = (thumb[1] - thumb[0] + 1) / 3
    # Within 3 design px: its rounded ends shade off into the entries behind and are not counted.
    expect(abs(length - MENU_THUMB_LENGTH) <= 3,
           "the bar is %.0f design px long; %d px showing of 280 makes it %.0f"
           % (length, MENU_PAGE_HEIGHT, MENU_THUMB_LENGTH))
    expect(abs(thumb[0] - (panel[1] + 3 * MENU_PAGE_TOP)) <= 9,
           "before scrolling the bar should start at the page's top, row %d; it starts at %d"
           % (panel[1] + 3 * MENU_PAGE_TOP, thumb[0]))

    scroll_menu_to_end(panel)
    after = thumb_rows("scroll-after-thumb", panel)
    expect(after is not None and abs(after[1] - (panel[1] + 3 * MENU_PAGE_BOTTOM)) <= 9,
           "scrolled to the end, the bar should end at the page's bottom, row %d; it is at %r"
           % (panel[1] + 3 * MENU_PAGE_BOTTOM, after))
    expect(danger_entry_middle("scroll-after-reset", panel) is not None,
           "and scrolled to the end, Reset Room should be in sight")


@case("feedback: a warning first, then the message page, where a tap on the box types")
def feedback_warning_then_page():
    launch()
    panel = open_message_page()
    tap_message_box(panel)
    type_letters("hi")
    time.sleep(0.5)
    expect(menu_panel("feedback-page-typed") is not None,
           "typing into the message box closed the Feedback page")


@case("feedback: a finger scrolling the Menu opens none of its entries")
def feedback_swipe_opens_nothing():
    """Found on the phone on 2026-09-25: a swipe that began on About scrolled the list AND opened
    About. DragCancelsTap is the fix; this is the swipe that found it."""
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("swipe-before")
    expect(panel is not None, "the Menu window did not open")
    scroll_menu_to_end(panel)                       # it starts on About
    expect(danger_entry_middle("swipe-after", panel) is not None,
           "after a swipe that began on About, the Menu is not showing its end: the swipe opened"
           " About, or did not scroll")


@case("feedback: GitHub hands the message to the browser, as a new issue")
def feedback_github():
    """The word typed is new every run, so the address is too, and the check is the exact address
    in Brave's own address bar.

    ⚠ It comes back with `am start`, not Back, like about_source_code. Whether Back returns is
    Brave's decision: with a new address it came back 8 times in 10 on 2026-09-25, and with an
    address Brave had been sent before it stayed in Brave every time."""
    launch()
    panel = open_message_page()
    word = "".join(random.choice("qwertyuiopasdfghjklzxcvbnm") for _ in range(6))
    github, email = write_and_find_buttons(panel, word)
    tap(*github)
    time.sleep(4.0)
    front = resumed_package()
    expect(front not in (None, PKG, "android"),
           "GitHub should have opened the browser; %s is in front" % front)
    wanted = "github.com/ModCritic/InvMgrApp/issues/new?title=%s&body=%s" % (word, word)
    if on_test_phone():
        address = [text for text in window_text() if "github.com" in text]
        expect(wanted in address,
               "the browser's address bar should show %s; it shows %r" % (wanted, address))
    else:
        expect(link_started("github.com"), "no browser was started on github.com")
        put_away_other_apps()
    shell("am start -n " + ACT)
    time.sleep(3.0)
    expect(resumed_package() == PKG, "InvMgr did not come back to the front")
    expect(menu_panel("feedback-back-from-browser") is not None,
           "back from the browser the Feedback page should still be open")


@case("feedback: a word before Enter or a space goes in once")
def feedback_words_go_in_once():
    """Where the doubling was found on 2026-09-26: "abc" and a space left "abcabc " in the box.
    Read back the same way as feedback_github, off the address in Brave's address bar, which
    keeps a space and a line break percent-encoded. Before the fix it read
    title=abab&body=abab%0Acdcd%20ef.

    ⚠ The words are fixed, where feedback_github's is random: these three are ones Samsung's
    keyboard was seen to leave alone, and a random word before a space can be autocorrected
    into a different one."""
    needs_the_test_phone("Brave, whose address bar is the only place the whole address shows")
    launch()
    panel = open_message_page()
    tap_message_box(panel)
    type_letters("ab")
    tap(*UI.ENTER)
    type_letters("cd")
    tap(*UI.SPACE)
    type_letters("ef")
    time.sleep(0.5)
    panel = menu_panel("feedback-words-typed")
    expect(panel is not None, "with the keyboard up, the Feedback page is gone")
    buttons = green_buttons("feedback-words-buttons", panel)
    expect(len(buttons) == 2, "the message page should show GitHub and Email; found %d"
           % len(buttons))
    tap(*buttons[0])
    time.sleep(4.0)
    wanted = "github.com/ModCritic/InvMgrApp/issues/new?title=ab&body=ab%0Acd%20ef"
    address = [text for text in window_text() if "github.com" in text]
    shell("am start -n " + ACT)
    time.sleep(3.0)
    expect(wanted in address,
           "the browser's address bar should show %s; it shows %r" % (wanted, address))


@case("feedback: Email asks for a mail app, and offers only mail apps")
def feedback_email():
    """This phone has two mail apps and no default, so Android asks which. The case stops at that
    question on purpose: a compose screen on a phone signed in to a real account could leave a
    draft in it. Whether the address, subject and message arrive is checked by hand."""
    launch()
    panel = open_message_page()
    github, email = write_and_find_buttons(panel)
    tap(*email)
    time.sleep(3.0)
    dump = shell("dumpsys activity activities")
    expect(("act=android.intent.action.SENDTO" in dump and "dat=mailto:" in dump)
           or started("android.intent.action.SENDTO", "mailto:"),
           "Email did not hand Android a mailto send")
    if not on_test_phone():
        put_away_other_apps()
        raise Skipped("the send went out; the rest needs the test phone's two mail apps and"
                      " Android's question between them")
    front = resumed_package()
    expect(front == "android",
           "Android should be asking which mail app to use; %s is in front, and the case stops"
           " here rather than leave a draft behind" % front)
    offered = window_text()
    expect("Gmail" in offered and "Email" in offered,
           "the question should offer the phone's two mail apps; it shows %r" % offered)
    keyevent("KEYCODE_BACK", settle=2.0)
    expect(resumed_package() == PKG, "Back from the question did not come back to InvMgr")
    expect(menu_panel("feedback-back-from-mail") is not None,
           "back from the question the Feedback page should still be open")


@case("feedback: a tap outside leaves the half-written message open")
def feedback_outside_tap():
    launch()
    panel = open_message_page()
    type_letters("hi")
    # The 13 design px of darkened room left of the square.
    tap(15, panel[1] + 3 * 100)
    time.sleep(1.0)
    expect(menu_panel("feedback-after-outside") is not None,
           "a tap outside closed the page with a message on it; see MenuWindow.closesOnOutsideTap")


# -------------------------------------------------------- stopping at the ends (2026-09-26)

# JavaFX's own scroll bar, which a touch screen shows while a finger scrolls and for a second
# after: a #1a1a1a track filling the column 300 design px in from the Menu window's left edge,
# with a #888 thumb beside it. Measured on the build before the fix. Without it, that column holds
# only the entries' own grays and the gaps between them, and never this one.
JAVAFX_TRACK = (0x1a, 0x1a, 0x1a)
JAVAFX_TRACK_IN_FROM_LEFT = 300

TEXT_INPUT = (0xdd, 0xdd, 0xdd)

# The Add dialog's Confirm while the dialog is not lifted, which is whenever no keyboard is up.
# Measured 2026-09-26 as find_confirm's answer on an empty room, where nothing else is green.
ADD_CONFIRM_UNLIFTED = (890, 1452)


def no_dialog_open():
    """True when no dialog is darkening the room, read off the island's Add button: its glyph
    measured 204 gray with nothing open and 72 under a dialog's overlay (2026-09-26)."""
    from PIL import Image
    image = Image.open(screenshot("no-dialog-check")).convert("RGB")
    return max(image.getpixel(UI.ADD)) > 140


# The item list's color dots with the list drawer open, in real px from the screen's left:
# measured off a screenshot, a column through their middles. The room's boxes show left of the
# drawer, so a column further left would find them instead.
LIST_DOT_X = 530


def slow_swipe_and_shoot(name, x1, y1, x2, y2, after=1.3):
    """A 2.5 s swipe, photographed part way through, which is when a stretch shows."""
    finger = subprocess.Popen([*ADB, "-s", SERIAL, "shell",
                               "input swipe %d %d %d %d 2500" % (x1, y1, x2, y2)])
    time.sleep(after)
    path = screenshot(name)
    finger.wait()
    return path


def first_border_row(path, panel):
    """The first #555 row down the Menu page's left side: the top edge of its first entry."""
    from PIL import Image
    image = Image.open(path).convert("RGB")
    x = panel[0] + 3 * 30
    for y in range(panel[1] + 3 * MENU_PAGE_TOP, panel[1] + 3 * MENU_PAGE_BOTTOM):
        if is_border(image.getpixel((x, y))):
            return y
    return None


@case("menu: scrolling shows one bar, ours, and not JavaFX's as well")
def menu_one_bar():
    """The user saw two on 2026-09-26: ours in the margin, and JavaFX's own flashing up inside the
    page while a finger scrolled. ScrollHint hides JavaFX's."""
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("one-bar-open")
    expect(panel is not None, "the Menu window did not open")
    x = panel[0] + MENU_SIDE // 2
    from PIL import Image
    image = Image.open(slow_swipe_and_shoot("one-bar-mid", x, panel[1] + 3 * 250,
                                            x, panel[1] + 3 * 200)).convert("RGB")
    column = panel[0] + 3 * JAVAFX_TRACK_IN_FROM_LEFT
    track = sum(1 for y in range(panel[1] + 3 * MENU_PAGE_TOP, panel[1] + 3 * MENU_PAGE_BOTTOM)
                if near(image.getpixel((column, y)), JAVAFX_TRACK, 3))
    expect(track < 10, "mid-scroll, JavaFX's own scroll bar is showing inside the page:"
           " %d rows of its track" % track)


@case("menu: pulled down at its top, the Menu stays put rather than stretching")
def menu_no_stretch():
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("stretch-open")
    expect(panel is not None, "the Menu window did not open")
    before = first_border_row(screenshot("stretch-before"), panel)
    x = panel[0] + MENU_SIDE // 2
    mid = first_border_row(slow_swipe_and_shoot("stretch-mid", x, panel[1] + 3 * 122,
                                                x, panel[1] + 3 * 300), panel)
    expect(before is not None and mid is not None and abs(mid - before) <= 3,
           "pulled down at the top, the first entry moved from row %s to row %s; before the fix"
           " it went 195 real px down part way through, then sprang back" % (before, mid))


@case("add item: pulled right at its start, the preset strip stays put")
def presets_no_stretch():
    """Before the fix the strip's heading slid 189 real px right part way through this pull, then
    sprang back (measured 2026-09-26)."""
    from PIL import Image
    launch()
    tap(*UI.ADD)
    time.sleep(1.2)
    confirm = find_confirm()
    expect(confirm is not None, "the Add dialog did not open")
    image = Image.open(screenshot("strip-before")).convert("RGB")
    rows = [y for y in range(confirm[1] - 800, confirm[1] - 480)
            if any(image.getpixel((x, y)) == TEXT_INPUT for x in range(60, 400, 2))]
    expect(rows, "found no heading text above the Add dialog's fields")
    y = (rows[0] + rows[-1]) // 2
    start = min(x for x in range(40, 1040) if image.getpixel((x, y)) == TEXT_INPUT)
    mid = Image.open(slow_swipe_and_shoot("strip-mid", start + 40, y, start + 500, y)).convert("RGB")
    moved = [x for x in range(40, 1040) if mid.getpixel((x, y)) == TEXT_INPUT]
    expect(moved and abs(min(moved) - start) <= 3,
           "pulled right, the heading moved from x=%d to x=%s" % (start, min(moved) if moved else None))


@case("items: pulled down at its top, the item list stays put")
def list_no_stretch():
    """Found while checking the Menu's fix on 2026-09-26: the item list stretched the same way,
    its first row about 100 real px down part way through this pull."""
    from PIL import Image
    launch()
    for count in range(1, 21):                      # enough rows to scroll
        tap(*UI.ADD)
        time.sleep(1.0)
        # ⚠ Not confirm_dialog(): find_confirm looks for Confirm's green, and once the room holds
        # a few random-colored boxes, a green one counts too. Seen 2026-09-26: taps landing on a
        # box, and a dialog left open over the list's tab. With no keyboard up the Add dialog is
        # not lifted, so its Confirm is always here.
        tap(*ADD_CONFIRM_UNLIFTED)
        expect(wait_for_state(lambda s: len(s["items"]) >= count) is not None,
               "item %d was not added" % count)
    expect(no_dialog_open(), "an Add dialog is still open over the list's tab")
    tap(*UI.ITEMS_TAB)
    time.sleep(1.2)
    for _ in range(3):                              # to the top of the list
        shell("input swipe 980 500 980 1400 150")
        time.sleep(0.8)
    time.sleep(2.0)

    def first_dot_row(path):
        # The first row's color dot: the first saturated pixel down the dots' column.
        image = Image.open(path).convert("RGB")
        for y in range(420, 1400):
            r, g, b = image.getpixel((LIST_DOT_X, y))
            if max(r, g, b) - min(r, g, b) > 40:
                return y
        return None

    before = first_dot_row(screenshot("list-stretch-before"))
    mid = first_dot_row(slow_swipe_and_shoot("list-stretch-mid", 980, 500, 980, 1100))
    expect(before is not None and mid is not None and abs(mid - before) <= 3,
           "pulled down at the top, the first row's dot moved from row %s to row %s" % (before, mid))


def expect_side(panel, where):
    width = panel[2] - panel[0] + 1
    height = panel[3] - panel[1] + 1
    expect(abs(width - MENU_SIDE) <= 4 and abs(height - MENU_SIDE) <= 4,
           "%s the Menu window is %d x %d real pixels; it should be %d square (334 design"
           " pixels at density 3)" % (where, width, height, MENU_SIDE))


@case("menu: the island's Menu button opens a 334 px square between the phone's bars")
def menu_opens_upright():
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("menu-upright")
    expect(panel is not None, "tapping the island's Menu button opened no window")
    expect_side(panel, "upright,")
    # Centered between the clock bar and the navigation bar: 24 + (668 - 334) / 2 = 191 design
    # pixels down, 13 in from the left.
    expect(abs(panel[1] - 3 * 191) <= 4 and abs(panel[0] - 3 * 13) <= 4,
           "upright, the Menu window's corner is at (%d, %d); centered between the phone's bars"
           " it would be at (%d, %d)" % (panel[0], panel[1], 3 * 13, 3 * 191))


@case("menu: on its side the window is the same size, and clear of the clock")
def menu_opens_sideways():
    launch()
    try:
        set_rotation(1)
        tap(*UI.LAND_MENU)
        time.sleep(1.0)
        panel = menu_panel("menu-sideways", sideways=True)
        expect(panel is not None, "tapping the island's Menu button on its side opened no window")
        expect_side(panel, "on its side,")
        # 336 design pixels below the clock bar, so the square has one to spare above and below:
        # its top edge is at 25 design pixels, never under the clock at 0 to 24.
        expect(panel[1] >= 72,
               "on its side the Menu window's top edge is at y=%d, under the clock bar that ends"
               " at 72" % panel[1])
        expect(abs(panel[1] - 3 * 25) <= 4,
               "on its side the Menu window's top edge is at y=%d; centered below the clock it"
               " would be at %d" % (panel[1], 3 * 25))
    finally:
        set_rotation(0)


@case("menu: Metric switches the room to meters, and the window stays open")
def menu_metric():
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    panel = menu_panel("menu-before-metric")
    expect(panel is not None, "the Menu window did not open")
    # Metric is the first entry: the window's 24 px padding, the 41 px title row and its 12 px
    # margin, then half of a 40 px entry. 97 design pixels below the top edge.
    tap(panel[0] + MENU_SIDE // 2, panel[1] + 3 * 97)
    seen = wait_for_state(lambda s: s["metricMode"] is True)
    expect(seen is not None and seen["metricMode"] is True,
           "tapping Metric did not switch the room to meters; last state seen: %r"
           % (seen and seen.get("metricMode")))
    expect(menu_panel("menu-after-metric") is not None,
           "the Menu window closed on its own after Metric; it should stay open so it can be"
           " switched back")


@case("menu: a tap outside closes it, and the room behind hears nothing")
def menu_outside_tap():
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    expect(menu_panel("menu-before-outside") is not None, "the Menu window did not open")
    # On the island's Add button, which is outside the window and under the darkening. A tap
    # that closed the window AND reached the button would open the Add dialog, and the dialog's
    # green Confirm is something find_confirm already knows how to see. (The drawer tabs would
    # have been the other obvious target, and they sit inside the window's width.)
    tap(*UI.ADD)
    time.sleep(1.0)
    expect(menu_panel("menu-after-outside") is None,
           "a tap on the darkened room outside the Menu window left the window open")
    expect(find_confirm() is None,
           "the tap that closed the Menu window also reached the Add button behind it and"
           " opened the Add dialog")
    expect(shell("pidof " + PKG).strip() != "", "and the app is still running")


@case("menu: the phone's Back button closes it, and not the app")
def menu_back():
    launch()
    tap(*UI.MENU)
    time.sleep(1.0)
    expect(menu_panel("menu-before-back") is not None, "the Menu window did not open")
    keyevent("KEYCODE_BACK", settle=1.0)
    expect(menu_panel("menu-after-back") is None,
           "Back left the Menu window open. It arrives as Escape, and Escape only reaches the"
           " window when focus is inside it; see MenuWindow.onShown")
    expect(shell("pidof " + PKG).strip() != "", "and Back did not close the app")


# --------------------------------------------- a box keeps its text color (2026-09-26)

# How many of the #ddd text pixels a word must leave in its box, and how many pure black ones it
# may. Measured 2026-09-26. Without SkinnedTextArea, "abcd" left 0 pixels of #ddd and 806 black in
# either box once focus had moved on; with it, 549 and 0 in the Name box and 547 and 0 in the
# Feedback box. Nothing else in either rectangle is black: the box is #1a1a1a and its border #555.
# The caret handle has a black outline, which is why no count is taken while a box has focus.
MIN_TEXT_PIXELS = 150
MAX_BLACK_PIXELS = 20


def text_colors(path, left, top, right, bottom):
    """How many pixels in a rectangle of a screenshot are the text's #ddd, and how many are black."""
    from PIL import Image
    image = Image.open(path).convert("RGB")
    light = black = 0
    for y in range(top, bottom):
        for x in range(left, right):
            pixel = image.getpixel((x, y))
            if near(pixel, TEXT_INPUT, 12):
                light += 1
            elif max(pixel) < 0x10:
                black += 1
    return light, black


def expect_text_kept_its_color(where, light, black):
    expect(black <= MAX_BLACK_PIXELS and light >= MIN_TEXT_PIXELS,
           "%s: %d pixels of #ddd text and %d black once focus left the box. Black text there is"
           " JavaFX resetting the text color, fixed by SkinnedTextArea" % (where, light, black))


@case("text boxes: the Add dialog's Name keeps its color once Width takes focus")
def name_keeps_its_color():
    """The path the bug was found on. ⚠ Without the fix this fails only when a pulse runs before
    the dialog's queued requestFocus, which the phone decides: 1 run in 9 on 2026-09-26, after 4 of 5
    that morning. The Feedback case below fails every time, and SkinnedTextAreaTest covers this
    order on the desktop without the race."""
    launch()
    tap(*UI.ADD)
    dlg("name")
    type_letters("abcd")
    dlg("width")
    time.sleep(1.0)
    # The number pad is what says focus really moved; without it the Name box could still be
    # focused, and a focused box draws light either way.
    expect(keyboard_type() == NUMBER_DECIMAL,
           "tapping Width did not bring up the number pad, so focus never left Name; got %s"
           % name_of_type(keyboard_type()))
    confirm = find_confirm()
    expect(confirm is not None, "the Add dialog closed")
    middle = confirm[1] - DLG_ABOVE["name"]
    light, black = text_colors(screenshot("name-after-width"), 548, middle - 28, 1008, middle + 28)
    expect_text_kept_its_color("the Name box", light, black)


@case("text boxes: the Feedback message keeps its color once focus moves off it")
def feedback_keeps_its_color():
    launch()
    panel = open_message_page()
    github = write_and_find_buttons(panel, "abcd")[0]
    # A finger pressed on GitHub and slid off it. The press moves focus to the button, and the
    # slide means it never fires (DragCancelsTap), so nothing opens. A tap outside the window
    # would be simpler and does not work: it leaves focus in the box.
    shell("input swipe %d %d %d %d 600" % (github[0], github[1], github[0], github[1] - 150))
    time.sleep(1.2)
    expect(resumed_package() == PKG, "the slide off GitHub opened %s" % resumed_package())
    # The keyboard going away is what says focus left the box: the box's skin hides it then.
    expect(not keyboard_showing(), "the keyboard is still up, so focus never left the box")
    panel = menu_panel("feedback-after-slide")
    expect(panel is not None, "the slide off GitHub closed the Menu window")
    top = panel[1] + 3 * MENU_PAGE_TOP
    light, black = text_colors(screenshot("feedback-after-slide"),
                               panel[0] + 3 * 26, top, panel[0] + 3 * 300, top + 3 * 86)
    expect_text_kept_its_color("the Feedback box", light, black)


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

    if pattern:
        selected = [(n, f) for n, f, _ in CASES if pattern.lower() in n.lower()]
    else:
        selected = [(n, f) for n, f, opt_in in CASES if not opt_in]
    if not selected:
        raise SystemExit("no case matches %r" % pattern)

    passed, skipped, failed = 0, 0, []
    for name, function in selected:
        try:
            function()
            print("  pass      %s" % name)
            passed += 1
        except Skipped as reason:
            print("  skip      %s\n              %s" % (name, reason))
            skipped += 1
        except Failure as problem:
            print("  FAIL      %s\n              %s" % (name, problem))
            failed.append(name)
        except Exception as problem:                       # noqa: BLE001 - report, keep going
            print("  ERROR     %s\n              %s: %s" % (name, type(problem).__name__, problem))
            failed.append(name)

    print("\n%d passed, %d skipped, %d failed, of %d"
          % (passed, skipped, len(failed), len(selected)))
    for name in failed:
        print("  failed: %s" % name)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
