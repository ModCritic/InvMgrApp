"""Checks InvMgr's features after it is installed, driving it like a person and reading its own
autosave after each step.

    feature_test.py x  OUTDIR LAUNCH [ARG...]    on an X display with no window manager (drive_x.py)
    feature_test.py vm OUTDIR LAUNCH [ARG...]    inside the test VM that VM names (drive_vm.py)
    feature_test.py win OUTDIR LAUNCH [ARG...]   inside the tiny10 Windows VM (drive_win.py)

LAUNCH is how the installed app starts: InvMgr/bin/InvMgr from the .tar.gz, the AppImage file, or
/opt/invmgr/bin/InvMgr from a package. In a VM it is a path inside the VM, or menu:InvMgr to open
the app from the desktop's app search. The app must not be running, and its data folder must not
exist yet, so the test sees the app create it. In a VM, OUTDIR has to be inside the shared folder,
because the app's output is written there from inside the VM.

Prints PASS or FAIL for each check, saves a screenshot per check in OUTDIR, and exits 1 if anything
failed. Positions are the app's at 2560x1440 with its window filling the screen, measured on 1.0.2.
"""
import importlib, json, os, re, sys, time
from PIL import ImageChops

drv = importlib.import_module("drive_" + sys.argv[1])
click, double_click, drag, hold, key, look, move, pause, type_text = (
    drv.click, drv.double_click, drv.drag, drv.hold, drv.key, drv.look, drv.move, drv.pause, drv.type_text)
OUT = os.path.abspath(sys.argv[2])
LAUNCH = sys.argv[3:]
DATA = drv.DATA
AUTOSAVE = DATA + "/autosave.json"

ORIGIN = (80, 65)
W_FIELD, L_FIELD = (76, 20), (196, 20)
SET_ROOM, LAYER_COLLISION, SAVE, LOAD, THREE_D, ADD, UNDO, FIT, PLAN, MENU = (
    (420, 20), (542, 20), (650, 20), (714, 20), (766, 20), (806, 20), (858, 20), (918, 20),
    (978, 20), (1030, 20))
MENU_IN_METRIC = (1006, 20)
NAME, CONFIRM, CANCEL = (1375, 670), (1437, 825), (1348, 825)
SLOT = [(1263, 615), (1297, 615), (1331, 615)]
PRESET_NAME, PRESET_SAVE = (1400, 662), (1448, 819)
METRIC, ABOUT, MENU_CLOSE = (1280, 634), (1280, 748), (1414, 586)
EDIT_OK = (1456, 818)
SEARCH, SEARCH_CLEAR, EXPORT = (2460, 94), (2540, 94), (2540, 58)
SLIDER_X, SLIDER_TOP, SLIDER_BOTTOM = 44, 76, 1400

results = []
os.makedirs(OUT, exist_ok=True)


def shot(name=None):
    im = drv.shot()
    if name:
        im.save(os.path.join(OUT, name + ".png"))
    return im


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""), flush=True)
    shot(f"{len(results):02d}-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:40])


def state(ready=None, timeout=8.0):
    end = time.time() + timeout
    while True:
        try:
            s = json.loads(drv.read_text(AUTOSAVE) or "")
        except ValueError:
            s = None
        if s and (ready is None or ready(s)):
            return s
        if time.time() > end:
            return s
        time.sleep(0.3)


def item(s, name):
    return next((i for i in (s or {}).get("items", []) if i.get("name") == name), None)


def center(i):
    return (ORIGIN[0] + i["x_px"] + i["w_in"] * 4, ORIGIN[1] + i["y_px"] + i["l_in"] * 4)


def wait_for(test, seconds=15.0):
    end = time.time() + seconds
    while time.time() < end:
        if test():
            return True
        time.sleep(0.3)
    return False


def launch(log_name):
    handle = drv.start(LAUNCH, os.path.join(OUT, log_name))
    wait_for(lambda: "android false" in drv.log_text(handle) and drv.app_titles(), 90)
    pause(1.5)
    shot(log_name.replace(".log", "-as-opened"))
    drv.fill_screen()
    pause(2.0)
    return handle, drv.log_text(handle)


def set_field(point, value):
    click(*point, settle=0.2)
    key("a", 0.1, ("Control_L",))
    type_text(value, 0.03)
    pause(0.3)


def add_item(name, w, l, h):
    click(*ADD, settle=0.8)
    click(*NAME, settle=0.2)
    type_text(name, 0.03)
    for value in (w, l, h):
        key("Tab", 0.15)
        type_text(str(value), 0.03)
    pause(0.3)
    click(*CONFIRM, settle=1.2)


def gtk_save_to(path):
    """Types a whole path into GTK's save dialog, once it is up: a dialog that opens slowly would
    otherwise get only the end of the typing, and the app the start of it."""
    if not wait_for(drv.dialog_titles):
        return False
    drv.into_dialog()
    key("a", 0.1, ("Control_L",))
    type_text(path, 0.02)
    key("Return", 0.5)
    closed = wait_for(lambda: not drv.dialog_titles())
    drv.back_to_app()
    return closed


def gtk_open(path):
    """Ctrl+L in GTK's open dialog gives a box for a typed path."""
    if not wait_for(drv.dialog_titles):
        return False
    drv.into_open_dialog()
    key("l", 0.5, ("Control_L",))
    type_text(path, 0.02)
    # The box completes the name as it is typed, and the first Return can go to that completion.
    key("Return", 0.5)
    closed = wait_for(lambda: not drv.dialog_titles(), 2.0)
    if not closed:
        key("Return", 0.5)
        closed = wait_for(lambda: not drv.dialog_titles())
    drv.back_to_app()
    return closed


# A driver for a desktop whose file dialogs are not GTK's brings its own.
save_to = getattr(drv, "save_to", gtk_save_to)
open_file = getattr(drv, "open_file", gtk_open)


def saturated(im, box=None):
    im = im.crop(box) if box else im
    h, s, v = im.convert("HSV").split()
    mask = ImageChops.multiply(s.point(lambda x: 255 if x > 77 else 0),
                               v.point(lambda x: 255 if x > 30 else 0))
    return sum(1 for p in mask.tobytes() if p)


def list_rows(im):
    """How many item rows the list shows, counted by their color dots."""
    rows, last = 0, -10
    for y in range(110, 1400):
        hit = any(saturated(im, (x, y, x + 1, y + 1)) for x in range(2390, 2400, 2))
        if hit and y - last > 12:
            rows += 1
        if hit:
            last = y
    return rows


def main():
    if drv.listdir(DATA) is not None:
        sys.exit(f"{DATA} exists; this test needs to see the app create it")
    files_dir = drv.files_dir(OUT)
    app, log = launch("app-1.log")
    check("starts, with 3D available", "scene3d true" in log, log.splitlines()[-3] if log else "no log")
    java = next((l for l in log.splitlines() if l.startswith("InvMgr: java ")), "")
    check("runs on the bundled Java", "java 17.0.21," in java, java)
    if LAUNCH[0].startswith("menu:"):
        key("Super_L", 1.5)
        shot("dock-while-running")
        key("Escape", 1.0)
        entry, classes = drv.menu_entry_class()
        check("the dock shows the running app under its menu entry", entry and entry in classes,
              f"entry {entry!r}, windows {classes}")

    set_field(W_FIELD, "20")
    set_field(L_FIELD, "12")
    click(*SET_ROOM, settle=1.5)
    s = state(lambda s: s["room"]["w"] == 20 and s["room"]["l"] == 12)
    check("Set Room, and the autosave lands in " + DATA,
          s and s["room"]["w"] == 20 and s["room"]["l"] == 12, json.dumps(s and s["room"]))

    add_item("Books", 16, 14, 10)
    add_item("Movies", 12, 12, 6)
    s = state(lambda s: item(s, "Books") and item(s, "Movies"))
    b, m = item(s, "Books"), item(s, "Movies")
    check("add two items", b and m and (b["w_in"], b["l_in"], b["h_in"]) == (16, 14, 10),
          str(b and (b["w_in"], b["l_in"], b["h_in"])))

    drag(*center(m), *center(b), seconds=0.8)
    s = state(lambda s: item(s, "Movies")["baseHeight_in"] == 10)
    check("dragging one onto the other stacks it", item(s, "Movies")["baseHeight_in"] == 10,
          "base " + str(item(s, "Movies")["baseHeight_in"]))

    double_click(*center(item(s, "Movies")), settle=1.2)
    shot("edit-dialog")
    click(*NAME, settle=0.2)
    key("a", 0.1, ("Control_L",))
    type_text("Films", 0.03)
    click(*EDIT_OK, settle=1.2)
    s = state(lambda s: item(s, "Films"))
    check("edit an item's name", item(s, "Films"))

    click(*UNDO, settle=1.2)
    s = state(lambda s: item(s, "Movies"))
    check("undo", item(s, "Movies") and not item(s, "Films"))

    count = len(s["items"])
    click(*center(item(s, "Books")), button=3, settle=1.2)
    s = state(lambda s: len(s["items"]) == count - 1)
    check("right-click deletes", len(s["items"]) == count - 1)
    click(*UNDO, settle=1.2)
    s = state(lambda s: len(s["items"]) == count)
    check("undo brings it back", len(s["items"]) == count)

    before = s.get("layerFeet")
    drag(SLIDER_X, SLIDER_TOP + 2, SLIDER_X, (SLIDER_TOP + SLIDER_BOTTOM) // 2, seconds=0.6)
    s = state(lambda s: s.get("layerFeet") != before)
    check("the layer slider", s.get("layerFeet") != before, f"{before} -> {s.get('layerFeet')}")
    drag(SLIDER_X, (SLIDER_TOP + SLIDER_BOTTOM) // 2, SLIDER_X, SLIDER_TOP - 20, seconds=0.6)

    was = s.get("layerCollision")
    click(*LAYER_COLLISION, settle=1.0)
    s = state(lambda s: s.get("layerCollision") != was)
    check("Layer Collision switches", s.get("layerCollision") != was)
    click(*LAYER_COLLISION, settle=1.0)

    click(*PLAN, settle=1.0)
    click(*ADD, settle=0.8)
    click(*NAME, settle=0.2)
    type_text("Ghost", 0.03)
    click(*CONFIRM, settle=1.2)
    s = state(lambda s: item(s, "Ghost"))
    check("Plan mode adds a planned item", s.get("planMode") and item(s, "Ghost") and item(s, "Ghost").get("planned"))
    click(*PLAN, settle=1.0)

    click(*ADD, settle=0.8)
    click(*SLOT[0], settle=0.8)
    click(*PRESET_NAME, settle=0.2)
    type_text("Sm", 0.03)
    for value in (10, 10, 10):
        key("Tab", 0.15)
        type_text(str(value), 0.03)
    click(*PRESET_SAVE, settle=1.0)
    click(*CANCEL, settle=0.8)
    s = state(lambda s: s["presets"] and s["presets"][0] and s["presets"][0].get("name") == "Sm")
    check("save a preset", s["presets"][0] and s["presets"][0].get("name") == "Sm", json.dumps(s["presets"][:1]))

    click(*MENU, settle=0.8)
    click(*METRIC, settle=0.6)
    click(*MENU_CLOSE, settle=0.8)
    s = state(lambda s: s.get("metricMode"))
    check("Metric on, from the Menu", s.get("metricMode"))
    click(*MENU_IN_METRIC, settle=0.8)
    click(*METRIC, settle=0.6)
    click(*MENU_CLOSE, settle=0.8)
    s = state(lambda s: not s.get("metricMode"))
    check("and off again", not s.get("metricMode"))

    click(*SEARCH, settle=0.3)
    type_text("Boo", 0.05)
    pause(1.0)
    rows = list_rows(shot())
    check("search filters the item list", rows == 1, f"{rows} rows for 'Boo'")
    click(*SEARCH_CLEAR, settle=0.8)
    rows = list_rows(shot())
    check("clearing the search shows them all", rows == len(s["items"]), f"{rows} rows, {len(s['items'])} items")

    saved = files_dir + "/saved-room.json"
    click(*SAVE, settle=0.5)
    closed = save_to(saved)
    text = drv.read_text(saved)
    drv.copy_out(saved, os.path.join(OUT, "saved-room.json"))
    check("Save writes the chosen file", closed and text and len(json.loads(text)["items"]) == len(s["items"]),
          f"dialog closed {closed}, file {text is not None}")

    count = len(s["items"])
    click(*center(item(s, "Books")), button=3, settle=1.2)
    state(lambda s: len(s["items"]) == count - 1)
    click(*LOAD, settle=0.5)
    closed = open_file(saved)
    s = state(lambda s: len(s["items"]) == count and item(s, "Books"))
    check("Load brings a saved room back", closed and len(s["items"]) == count and item(s, "Books"))

    exported = files_dir + "/item_list.txt"
    click(*EXPORT, settle=0.5)
    closed = save_to(exported)
    text = drv.read_text(exported)
    drv.copy_out(exported, os.path.join(OUT, "item_list.txt"))
    check("export the item list as text", closed and text and "Books" in text)
    open_dialogs = drv.dialog_titles()
    check("no dialog left open", not open_dialogs, ", ".join(open_dialogs))

    click(*MENU, settle=0.8)
    menu = shot("menu")
    click(*ABOUT, settle=0.8)
    about = shot("about")
    check("About opens from the Menu", ImageChops.difference(menu, about).getbbox() is not None)
    click(*MENU_CLOSE, settle=0.8)

    click(*THREE_D, settle=3.5)
    # The camera comes down to eye height in the middle of the room, looking level, so boxes on
    # the floor near the middle are under it: tilt down first.
    look(0, 450, 0.6, at=(1280, 400))
    pause(0.6)
    frames = [shot("3d-0")]
    for i in range(1, 9):
        look(-1800, 0, 0.6, at=(2200, 720))
        pause(0.6)
        frames.append(shot(f"3d-{i}"))
    boxes = [saturated(f) for f in frames]
    turns = all(ImageChops.difference(a, b).getbbox() for a, b in zip(frames, frames[1:]))
    check("3D draws the room and its boxes, and turns", turns and max(boxes) > 500,
          "box pixels per eighth of a turn " + str(boxes))
    hold("s", 1.2)
    pause(0.8)
    walked = shot("3d-walked")
    check("3D walks", ImageChops.difference(frames[-1], walked).getbbox() is not None)
    move(1280, 720, 0.2)
    key("Escape", 3.0)
    back = shot("3d-left")
    check("Escape leaves 3D", saturated(back, (90, 75, 1990, 1207)) > 500)

    s = state()
    count = len(s["items"])
    pause(3.0)
    check("the window's close button quits", drv.ask_to_close(app))
    files = drv.listdir(DATA) or []
    check("saves in " + DATA, "autosave.json" in files, ", ".join(files))

    app, log = launch("app-2.log")
    s = state(lambda s: len(s["items"]) == count)
    check("the room comes back after a restart", s and len(s["items"]) == count and item(s, "Books"))
    check("and is drawn", saturated(shot(), (90, 75, 1990, 1207)) > 500)
    backups = drv.listdir(DATA + "/backups") or []
    check("the restart kept a backup of the room", len(backups) == 1, ", ".join(backups))
    drv.stop(app)

    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)} of {len(results)} passed" + (": failed " + "; ".join(failed) if failed else ""))
    sys.exit(1 if failed else 0)


main()
