"""Asks the guest's window manager about InvMgr's windows, through X11, which is how InvMgr's
windows reach a Wayland desktop (by Xwayland). Run inside the VM with PYTHONPATH=pylib, a folder
beside it holding python-xlib and six, copied in since the VMs have no network to install them.

    wm.py list         every mapped window that has a title, as JSON
    wm.py fullscreen   makes InvMgr's main window fullscreen and gives it the keyboard
    wm.py classes      the X11 class of each window titled InvMgr, one per line
"""
import json, sys
from Xlib import X, display, protocol

d = display.Display()
root = d.screen().root


def titled(window=root, depth=0):
    for child in window.query_tree().children:
        try:
            name = child.get_wm_name()
            mapped = child.get_attributes().map_state == X.IsViewable
        except Exception:
            continue
        if name and mapped:
            yield child, str(name)
        if depth < 2:
            yield from titled(child, depth + 1)


def message(window, kind, data):
    event = protocol.event.ClientMessage(window=window, client_type=d.intern_atom(kind),
                                         data=(32, data + [0] * (5 - len(data))))
    root.send_event(event, event_mask=X.SubstructureRedirectMask | X.SubstructureNotifyMask)
    d.sync()


if sys.argv[1] == "list":
    out = []
    for w, name in titled():
        g = w.get_geometry()
        pos = w.translate_coords(root, 0, 0)
        out.append({"name": name, "x": -pos.x, "y": -pos.y, "w": g.width, "h": g.height})
    print(json.dumps(out))
elif sys.argv[1] == "fullscreen":
    mains = [w for w, name in titled() if name == "InvMgr"]
    if not mains:
        sys.exit("no InvMgr window")
    w = mains[0]
    message(w, "_NET_WM_STATE", [1, d.intern_atom("_NET_WM_STATE_FULLSCREEN"), 0, 1])
    message(w, "_NET_ACTIVE_WINDOW", [2, X.CurrentTime, 0])
    print("fullscreen", w.id)
elif sys.argv[1] == "classes":
    for w, name in titled():
        if name == "InvMgr" and w.get_wm_class():
            print(w.get_wm_class()[1])
