#!/usr/bin/env python3
"""Drive the Windows test VM through libvirt, without touching the host's mouse or keyboard.

  vmctl.py shot [out.png]        screenshot of the VM's screen, printed as its path and size
  vmctl.py key KEY [KEY ...]     press keys together, by Linux name without KEY_ (ENTER, LEFTALT TAB)
  vmctl.py keys KEY [KEY ...]    press keys one after another
  vmctl.py type TEXT             type text, one key at a time
  vmctl.py click X Y [right|double]
                                 click at screen pixel X,Y (the size from the last screenshot)
  vmctl.py hold KEY MS           hold one key down for MS milliseconds (walking in a 3D view)
  vmctl.py drag X1 Y1 X2 Y2      press at X1,Y1, move to X2,Y2, release
  vmctl.py wait [TIMEOUT]        wait until the screen holds still for 3 s, then screenshot
  vmctl.py ga COMMAND [ARGS ...] run a command in the guest through the QEMU guest agent

Set VM to pick another domain (default tiny10). It always talks to qemu:///session. A screenshot
with no file named, and the files it keeps between calls, go to a vmctl-<VM> folder in the
system's temporary folder.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time

VM = os.environ.get("VM", "tiny10")
URI = "qemu:///session"
WORK = os.path.join(tempfile.gettempdir(), f"vmctl-{VM}")
os.makedirs(WORK, exist_ok=True)
SIZE_FILE = os.path.join(WORK, "last-screen-size")
ABS_MAX = 0x7FFF

SHIFTED = {
    "!": "1", "@": "2", "#": "3", "$": "4", "%": "5", "^": "6", "&": "7", "*": "8",
    "(": "9", ")": "0", "_": "MINUS", "+": "EQUAL", "{": "LEFTBRACE", "}": "RIGHTBRACE",
    "|": "BACKSLASH", ":": "SEMICOLON", '"': "APOSTROPHE", "<": "COMMA", ">": "DOT",
    "?": "SLASH", "~": "GRAVE",
}
PLAIN = {
    " ": "SPACE", "-": "MINUS", "=": "EQUAL", "[": "LEFTBRACE", "]": "RIGHTBRACE",
    "\\": "BACKSLASH", ";": "SEMICOLON", "'": "APOSTROPHE", ",": "COMMA", ".": "DOT",
    "/": "SLASH", "`": "GRAVE", "\n": "ENTER", "\t": "TAB",
}


def virsh(*args, capture=True):
    result = subprocess.run(["virsh", "-c", URI, *args], capture_output=capture, text=True)
    if result.returncode != 0:
        sys.exit(f"virsh {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def qmp(command, arguments=None):
    message = {"execute": command}
    if arguments is not None:
        message["arguments"] = arguments
    return json.loads(virsh("qemu-monitor-command", VM, json.dumps(message)))


def send_keys(names, hold_ms=60):
    virsh("send-key", VM, "--codeset", "linux", "--holdtime", str(hold_ms),
          *[n if n.startswith("KEY_") else "KEY_" + n.upper() for n in names])


def keys_for(ch):
    if ch.isalpha() and ch.isascii():
        return (["LEFTSHIFT"] if ch.isupper() else []) + [ch.upper()]
    if ch.isdigit():
        return [ch]
    if ch in PLAIN:
        return [PLAIN[ch]]
    if ch in SHIFTED:
        return ["LEFTSHIFT", SHIFTED[ch]]
    sys.exit(f"vmctl: no key for {ch!r}")


def shot(out):
    ppm = out + ".ppm"
    virsh("screenshot", VM, ppm)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", ppm, out], check=True)
    os.remove(ppm)
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height", "-of", "csv=p=0", out], capture_output=True, text=True, check=True)
    width, height = probe.stdout.strip().split(",")
    with open(SIZE_FILE, "w") as f:
        f.write(f"{width} {height}\n")
    print(out, f"{width}x{height}")


def screen_sample():
    """The screen shrunk to 160x100 grayscale: enough to see a page change, and too coarse
    for a blinking text caret to count as one. virsh hands back a PNG whatever the file is
    called, so ffmpeg decodes it."""
    image = os.path.join(WORK, "poll.img")
    virsh("screenshot", VM, image)
    result = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", image, "-vf", "scale=160:100", "-f", "rawvideo",
         "-pix_fmt", "gray", "-"], capture_output=True, check=True)
    os.remove(image)
    return result.stdout


def differs(a, b):
    changed = sum(1 for x, y in zip(a, b) if abs(x - y) > 8)
    return len(a) != len(b) or changed > len(a) // 500


def wait_for_still(timeout):
    """Return once the screen has not changed for 3 s. A clicked page has often already
    changed by the first look, so waiting for a change from the first look can miss it."""
    deadline = time.time() + timeout
    last, steady_since = screen_sample(), time.time()
    while time.time() < deadline:
        time.sleep(1)
        now = screen_sample()
        if differs(now, last):
            last, steady_since = now, time.time()
        elif time.time() - steady_since >= 3:
            return True
    return False


def click(x, y, mode="left"):
    with open(SIZE_FILE) as f:
        width, height = (int(v) for v in f.read().split())
    ax = round(int(x) * ABS_MAX / (width - 1))
    ay = round(int(y) * ABS_MAX / (height - 1))
    move = [{"type": "abs", "data": {"axis": "x", "value": ax}},
            {"type": "abs", "data": {"axis": "y", "value": ay}}]
    qmp("input-send-event", {"events": move})
    time.sleep(0.05)
    button = "right" if mode == "right" else "left"
    for _ in range(2 if mode == "double" else 1):
        for down in (True, False):
            qmp("input-send-event",
                {"events": [{"type": "btn", "data": {"down": down, "button": button}}]})
            time.sleep(0.05)


def move_to(x, y):
    with open(SIZE_FILE) as f:
        width, height = (int(v) for v in f.read().split())
    qmp("input-send-event", {"events": [
        {"type": "abs", "data": {"axis": "x", "value": round(x * ABS_MAX / (width - 1))}},
        {"type": "abs", "data": {"axis": "y", "value": round(y * ABS_MAX / (height - 1))}}]})


def drag(x1, y1, x2, y2, steps=20):
    """Press at the first point, move to the second in small steps, release."""
    move_to(x1, y1)
    time.sleep(0.1)
    qmp("input-send-event", {"events": [{"type": "btn", "data": {"down": True, "button": "left"}}]})
    for i in range(1, steps + 1):
        time.sleep(0.03)
        move_to(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
    time.sleep(0.1)
    qmp("input-send-event", {"events": [{"type": "btn", "data": {"down": False, "button": "left"}}]})


def guest_agent(argv):
    """Run a program in the guest and wait for it, printing its exit code and output."""
    request = {"execute": "guest-exec",
               "arguments": {"path": argv[0], "arg": argv[1:], "capture-output": True}}
    pid = json.loads(virsh("qemu-agent-command", VM, json.dumps(request)))["return"]["pid"]
    while True:
        status = json.loads(virsh("qemu-agent-command", VM, json.dumps(
            {"execute": "guest-exec-status", "arguments": {"pid": pid}})))["return"]
        if status.get("exited"):
            break
        time.sleep(0.5)
    for stream in ("out-data", "err-data"):
        if stream in status:
            sys.stdout.write(base64.b64decode(status[stream]).decode("utf-8", "replace"))
    print(f"[exit {status.get('exitcode')}]")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "shot":
        shot(args[0] if args else os.path.join(WORK, "screen.png"))
    elif cmd == "key":
        send_keys(args)
    elif cmd == "keys":
        for name in args:
            send_keys([name])
            time.sleep(0.08)
    elif cmd == "type":
        for ch in " ".join(args):
            send_keys(keys_for(ch), hold_ms=30)
            time.sleep(0.03)
    elif cmd == "click":
        click(args[0], args[1], args[2] if len(args) > 2 else "left")
    elif cmd == "hold":
        send_keys([args[0]], hold_ms=int(args[1]))
    elif cmd == "drag":
        drag(*(int(v) for v in args[:4]))
    elif cmd == "wait":
        if not wait_for_still(float(args[0]) if args else 60):
            print("vmctl: screen was still changing at the timeout")
        shot(os.path.join(WORK, "screen.png"))
    elif cmd == "ga":
        guest_agent(args)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
