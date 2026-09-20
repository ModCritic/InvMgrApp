#!/usr/bin/env python3
"""
M6.7b's phone lane: how long the real phone takes to show the result of a real finger.

WHY THE PHONE NEEDS ITS OWN HARNESS AND ITS OWN NUMBERS
=======================================================

TestFX cannot reach the phone and never will, and the phone is the machine the user actually
reported lag on. So the app measures itself: LatencyClock is in the shipped code, it prints one
line per input event, and this reads those lines back off logcat and writes them in the same shape
the two desktop lanes write. One measuring implementation, three screens, one reporter
(tools/perf/perfstat.py).

HOW THE FLAG GETS ONTO A PHONE THAT HAS NO COMMAND LINE
=======================================================

    adb shell am start -n com.modcritic.invmgr/.android.InvMgrActivity --es verbose latency

InvMgrActivity.verboseTopicsFromTheIntent copies that extra into the argument vector the native
image starts with, after checking it against the topics Verbose knows. It is the only launch
setting an intent may choose; everything else in that vector is load-bearing and an exported
Activity takes intents from anything on the phone.

⚠ THE PHONE HAS ONLY THE "FELT" ARM
===================================

The desktop lanes run twice, once as the app ships and once with -Djavafx.animation.fullspeed=true
so the 60 Hz cap stops hiding the work. That property has to be on the command line before the
toolkit starts, and it is not in the allowed list above, so the phone reports the capped number
only. That is the honest limit: on the phone, a change that keeps the app inside one frame is
invisible here and shows up on the desktop work arm instead.

⚠ A FINGER ARRIVES TWICE
========================

CLAUDE.md §5.8 item 5: every touch is delivered as a touch event AND as a synthesized mouse event.
Both are recorded, with the type on the line. perfstat.py counts the touch family alone when one is
present, so the phone's median is not a blend of a finger and its shadow.

RUNNING IT
==========

    . tools/android/adb-env.sh
    python3 tools/perf/phone_latency.py --label baseline
    python3 tools/perf/phone_latency.py --label baseline --only drag
    python3 tools/perf/phone_latency.py --label baseline --no-install
"""

import argparse
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "android"))

# The phone driver already written for the device suite: adb, install-with-verification, wake,
# unlock, wait-for-scene, and every UI coordinate measured on this exact screen. Reusing it means
# the perf lane cannot drift from the functional lane about where the Add button is.
import device_suite as phone  # noqa: E402

# ⚠ NOT under target/; see the note in perfstat.py. `mvn clean` deletes that directory.
PERF = os.path.join(REPO, "perf-runs")
SAMPLE = re.compile(
    r"InvMgr\[latency\] sample seq=(\d+) type=(\S+) q=([\d.-]+) r=([\d.-]+) t=([\d.-]+) ms=(\d+)")

# How long to let the app settle after a launch before anything is timed. The native image takes
# seconds to start and the first frames pay for every texture at once; timing those would measure
# the start-up, which is a different question with a different answer.
SETTLE_AFTER_LAUNCH = 4.0

AUTOSAVE = ".local/share/InvMgr/autosave.json"


# --------------------------------------------------------------------------- the world it measures

def world(room_ft, boxes, colors):
    """A save file the app will load on its next launch.

    ⚠ WITHOUT THIS THE PHONE LANE MEASURES AN EMPTY ROOM. The app restores its own autosave at
    startup, so whatever was last on the phone is what the scenarios drive: the first run of this
    harness tapped bare floor in a 12 x 10 ft room with no items in it and reported perfectly good
    numbers for doing nothing. The desktop lanes build their world in the test; this is the phone's
    equivalent, and it builds the SAME world so the two are comparable.
    """
    span = room_ft * 96.0
    per_row = max(1, int(boxes ** 0.5))
    gap = span / (per_row + 1)
    items = []
    for i in range(boxes):
        items.append({
            "id": "item-id-%036d" % i,
            "serial": i + 1,
            "dragOrder": i + 1,
            "w_in": 24, "l_in": 24, "h_in": 12 + (i % 7) * 6,
            "x_px": gap * (1 + i % per_row),
            "y_px": gap * (1 + i // per_row),
            "color": "hsl(%d,55%%,42%%)" % ((i % colors) * (360 // colors)),
            "name": "b%d" % i,
            "customId": "",
            "baseHeight_in": 0,
            "planned": False,
        })
    return {
        "room": {"w": room_ft, "l": room_ft, "h": 12},
        "items": items,
        "itemCounter": boxes,
        "dragOrderCounter": boxes,
        "layerFeet": 12,
        "planMode": False,
        "metricMode": False,
        "layerCollision": True,
        "presets": [None] * 7,
    }


def seed(room_ft, boxes, colors):
    """Writes that world into the app's own autosave, through run-as.

    The app must not be running: it rewrites the file about once a second, so seeding underneath a
    live app is a race that the app wins.
    """
    phone.shell("am force-stop " + phone.PKG)
    time.sleep(0.6)
    blob = json.dumps(world(room_ft, boxes, colors), separators=(",", ":"))
    local = os.path.join(REPO, "target", "_invmgr-seed.json")
    os.makedirs(os.path.dirname(local), exist_ok=True)
    with open(local, "w") as f:
        f.write(blob)
    # Via /data/local/tmp, because `adb push` cannot write into an app's private directory and
    # `run-as ... cat >` through a shell argument mangles the JSON's quotes.
    phone.adb("push", local, "/data/local/tmp/invmgr-seed.json")
    phone.shell("run-as %s mkdir -p .local/share/InvMgr" % phone.PKG)
    phone.shell("run-as %s sh -c 'cat /data/local/tmp/invmgr-seed.json > %s'"
                % (phone.PKG, AUTOSAVE))
    back = phone.shell("run-as %s cat %s" % (phone.PKG, AUTOSAVE))
    if '"w":%d' % room_ft not in back.replace(" ", ""):
        sys.exit("the seed did not take; the phone still holds:\n  " + back[:200])


# --------------------------------------------------------------------------- driving the glass

def swipe(x1, y1, x2, y2, ms):
    """One finger, dragged. `input swipe` interpolates, so this is many events, not two."""
    phone.shell("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, ms))


# Where the nine boxes of the 20 ft world land on this screen, in device pixels.
#
# ⚠ MEASURED OFF A SCREENSHOT, NOT WORKED OUT. The first version of this harness guessed, and the
# guesses missed every box: the app does not start in fit mode, so a 20 ft room is drawn at its
# full 96 pixels to the foot and every box sits off the right-hand edge. The screen showed an empty
# grid and the numbers that came back were perfectly good measurements of tapping bare floor.
# CLAUDE.md §7, on a part of the app §7 never covered.
SMALL_BOXES = [
    (336, 891), (590, 891), (843, 891),
    (336, 1144), (590, 1144), (843, 1144),
    (336, 1398), (590, 1398), (843, 1398),
]


def fit():
    """Turns fit mode on, so the whole room is on screen and the boxes are where they look."""
    phone.tap(*phone.UI.FIT, settle=1.2)


def launch_measuring():
    """Starts the app with the latency topic on, and waits until it has a scene."""
    phone.wake()
    phone.adb("logcat", "-c")
    phone.shell("am force-stop " + phone.PKG)
    phone.shell("am start -n %s --es verbose latency" % phone.ACT)
    waited = 0
    while waited < 45:
        if "android true, reserving SystemInsets" in phone.adb("logcat", "-d"):
            break
        time.sleep(1)
        waited += 1
    else:
        sys.exit("the app did not report a scene within 45 s")
    log = phone.adb("logcat", "-d")
    if "verbose logging asked for: latency" not in log:
        sys.exit("the phone did not take the verbose extra. Is this build older than M6.7b?\n"
                 "Look for 'verbose logging asked for' in: adb logcat -d | grep InvMgr")
    time.sleep(SETTLE_AFTER_LAUNCH)


def drain():
    """Every sample the app has printed since the log was last cleared."""
    out = []
    for line in phone.adb("logcat", "-d").splitlines():
        found = SAMPLE.search(line)
        if found:
            out.append({
                "seq": int(found.group(1)),
                "type": found.group(2),
                "q": float(found.group(3)),
                "r": float(found.group(4)),
                "t": float(found.group(5)),
                "ms": int(found.group(6)),
            })
    return out


# --------------------------------------------------------------------------- the scenarios
#
# Each one returns nothing and is bracketed by the runner, which clears the log first and reads
# everything printed afterwards. Coordinates come from device_suite.UI, measured on this screen.

def scenario_tap_boxes(reps):
    """Tap boxes on the canvas. Each tap selects one and puts its details up."""
    fit()
    for rep in range(reps):
        phone.tap(*SMALL_BOXES[rep % len(SMALL_BOXES)], settle=0.45)


def scenario_drag_box(reps):
    """Carry a box across the room. Every step is collision, stacking and a redraw."""
    fit()
    here, there = SMALL_BOXES[0], SMALL_BOXES[4]
    for rep in range(reps):
        if rep % 2 == 0:
            swipe(here[0], here[1], there[0], there[1], 900)
        else:
            swipe(there[0], there[1], here[0], here[1], 900)
        time.sleep(0.6)


def scenario_pan(reps):
    """Drag bare floor, which slides the whole room under the window.

    Fit mode stays OFF here: fitting a room to the window is what makes panning pointless, and
    the desktop lane turns it off for the same scenario for the same reason.
    """
    for rep in range(reps):
        if rep % 2 == 0:
            swipe(200, 1500, 880, 1500, 800)
        else:
            swipe(880, 1500, 200, 1500, 800)
        time.sleep(0.5)


def scenario_open_drawers(reps):
    """The item list drawer in and out. A whole panel animates each time."""
    for rep in range(reps):
        phone.tap(*phone.UI.ITEMS_TAB, settle=0.8)
        phone.tap(*phone.UI.ITEMS_TAB, settle=0.8)


def scenario_scroll_list(reps):
    """Fling the item list. §5.8 item 5: this is the synthesized mouse copy doing the work."""
    phone.tap(*phone.UI.ITEMS_TAB, settle=1.2)
    for rep in range(reps):
        if rep % 2 == 0:
            swipe(860, 1500, 860, 900, 500)
        else:
            swipe(860, 900, 860, 1500, 500)
        time.sleep(0.5)
    phone.tap(*phone.UI.ITEMS_TAB, settle=0.8)


def scenario_three_d(reps):
    """Into the 3D room, turn on the spot, and back out. What the user called laggy."""
    phone.tap(*phone.UI.FOLD, settle=1.0)
    phone.tap(*phone.UI.THREE_D, settle=6.0)
    for rep in range(reps):
        if rep % 2 == 0:
            swipe(300, 1100, 800, 1100, 700)
        else:
            swipe(800, 1100, 300, 1100, 700)
        time.sleep(0.5)


# Each row is (name, what to run, repetitions, room feet, boxes, colors, notes). The world is
# rebuilt per scenario, because a scenario that left a drawer open or a box moved would be the next
# one's starting state. `small` is 20 ft and 12 boxes and `heavy` is 200 ft and 200 boxes, the same
# two the desktop lanes use.
SCENARIOS = [
    ("tap-boxes-small", scenario_tap_boxes, 12, 20, 12, 4,
     "tap a box on the canvas; the app draws a selection ring round it"),
    ("drag-box-small", scenario_drag_box, 6, 20, 12, 4,
     "carry a box across the room; every step is collision, stacking and a redraw"),
    ("pan-heavy", scenario_pan, 6, 200, 200, 8,
     "drag bare floor in a 200 ft room holding 200 boxes, which slides the whole room"),
    ("open-drawers-small", scenario_open_drawers, 8, 20, 12, 4,
     "the item list drawer in and out; a whole panel animates"),
    ("scroll-list-heavy", scenario_scroll_list, 8, 200, 200, 8,
     "fling a 200-row item list up and down"),
    ("scroll-list-small", scenario_scroll_list, 8, 20, 12, 4,
     "fling a 12-row item list up and down: the control for the 200-row one"),
    ("scroll-list-60", scenario_scroll_list, 8, 60, 60, 6,
     "fling a 60-row item list: the middle point, for the shape of the curve"),
    ("turn-3d-heavy", scenario_three_d, 6, 200, 200, 8,
     "turn on the spot inside a 200 ft 3D room holding 200 boxes"),
]


# --------------------------------------------------------------------------- the runner

def write(label, scenario, notes, samples):
    out_dir = os.path.join(PERF, label, "phone-felt")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, scenario + ".json")
    with open(path, "w") as f:
        json.dump({
            "scenario": scenario,
            "platform": "phone-felt",
            "label": label,
            "notes": notes,
            "written": int(time.time() * 1000),
            "samples": [dict(rep=i, **s) for i, s in enumerate(samples)],
        }, f, indent=1)
    print("PERF  %-18s %4d samples -> %s" % (scenario, len(samples), path))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--label", required=True,
                        help="what to call this run: baseline, after-x, ...")
    parser.add_argument("--only", help="run only scenarios whose name contains this")
    parser.add_argument("--no-install", action="store_true",
                        help="use whatever APK is already on the phone")
    args = parser.parse_args()

    phone.UI.check_geometry()
    if not args.no_install:
        phone.install()

    wanted = [s for s in SCENARIOS if not args.only or args.only in s[0]]
    if not wanted:
        sys.exit("no scenario matches %r" % args.only)

    for name, run, reps, room_ft, boxes, colors, notes in wanted:
        # A fresh world and a fresh launch per scenario. The phone is not a test runner: a 3D view
        # left open, a drawer left out or a box moved would be the next scenario's starting state,
        # and the run would measure a different app each time it was ordered differently.
        print("PERF  %-18s seeding %d ft, %d boxes, %d colors" % (name, room_ft, boxes, colors))
        seed(room_ft, boxes, colors)
        launch_measuring()
        phone.adb("logcat", "-c")
        run(reps)
        time.sleep(1.0)
        samples = drain()
        if not samples:
            print("PERF  %-18s NOTHING RECORDED. The app took the flag but printed no samples, "
                  "so either the taps missed or the clock is not running." % name)
        write(args.label, name, notes, samples)

    phone.shell("am force-stop " + phone.PKG)
    print()
    print("Now: python3 tools/perf/perfstat.py report %s --platform phone-felt" % args.label)


if __name__ == "__main__":
    main()
