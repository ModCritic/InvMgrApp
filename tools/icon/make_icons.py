#!/usr/bin/env python3
"""Makes InvMgr's launcher icons and its Play Store icon from the user's artwork.

    python3 tools/icon/make_icons.py                # writes the icons into the project
    python3 tools/icon/make_icons.py --out DIR      # writes them under DIR instead
    python3 tools/icon/make_icons.py --preview P    # also a sheet of every shape, as P

The artwork, assets/icons/icon-source.png, is a 1146 px square with rounded corners drawn in:
a dark gray gradient with a gloss band, a grid bent by a slight fish-eye, and the colored "I".
Play wants a full square, and Android cuts a launcher icon to each phone's own shape from a
larger layer, so both need picture where the artwork has none: its corners, and a border
around it.

Nothing is painted by hand. The artwork is measured (where its grid lines run, how they bend,
how the grays change, where the gloss ends, how dark the lines are), the background is redrawn
from those measurements, and the artwork's own pixels are laid back on top wherever they
exist. The redrawn part shows only in the corners and the border. The functions below hold
each step's measurements, and the script prints the fish-eye model it fits.

Writes:
    assets/icons/icon-play-512.png                     the Play Store icon, a full square
    assets/icons/InvMgr.ico                            the Windows icon, the artwork as drawn, 16 to 256 px
    assets/icons/icon-linux-512.png                    the Linux icon, the artwork as drawn, 512 px
    src/android/res/mipmap-*/ic_launcher_background.png  the launcher icon's layer, Android 8+
    src/android/res/mipmap-*/ic_launcher.png           the artwork as drawn, Android 5 to 7
    src/android/res/mipmap-anydpi-v26/ic_launcher.xml  the file that tells Android 8+ to use it

Needs Pillow and NumPy. The steps and their order are exactly those of the approved round two,
quirks included (a model image rounded down to whole levels before one measurement, for
instance), so rerunning this reproduces the committed files pixel for pixel.
"""

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOURCE = os.path.join(REPO, "assets", "icons", "icon-source.png")
N = 1146            # the artwork's size
C = 572.5           # its center, in pixel coordinates (pixel centers are whole numbers)
R = 573.0           # half its size: distances from the center are measured in this unit
DENSITIES = (("ldpi", 0.75), ("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3),
             ("xxxhdpi", 4))
ARTWORK_DP = 69.0   # the artwork's size inside Android's 108 dp layer; see adaptive_layer
LAYER_PX = 1296     # the layer is drawn at 12 px per dp, then scaled for each density


def smoothstep(t):
    return t * t * (3 - 2 * t)


def basis(x, y, deg=5):
    """Every x^i y^j with i + j <= deg: the terms of a smooth surface of that degree."""
    return np.stack([x ** i * y ** j for i in range(deg + 1) for j in range(deg + 1 - i)], -1)


# ------------------------------------------------------------------ measuring the artwork

def dips(profile, ok, depth=6, reach=4):
    """Sub-pixel centers of thin dark lines crossing one row or column of pixels: a pixel
    darker than both neighbors, and `depth` levels darker than the pixels `reach` away."""
    out = []
    for i in range(reach, len(profile) - reach):
        if not (ok[i - reach] and ok[i] and ok[i + reach]):
            continue
        if profile[i] > profile[i - 1] or profile[i] > profile[i + 1]:
            continue
        if min(profile[i - reach], profile[i + reach]) - profile[i] >= depth:
            l, c, r = profile[i - 1], profile[i], profile[i + 1]
            den = l - 2 * c + r
            out.append(i + (0.5 * (l - r) / den if den else 0))
    return out


def find_line_points(lum, gray):
    """Points on the grid's vertical lines (found across rows) and horizontal lines (found
    down columns), every third row and column, in the gray background only."""
    vert = [(x, y) for y in range(0, N, 3) for x in dips(lum[y], gray[y])]
    horiz = [(x, y) for x in range(0, N, 3) for y in dips(lum[:, x], gray[:, x])]
    return np.array(vert), np.array(horiz)


def fit_fisheye(vert, horiz):
    """The grid's bend: a straight, evenly spaced grid, pulled toward the center more the
    further out it is. From picture to flat: d * (1 + a1 r^2 + a2 r^4), r in units of R.
    Fitted to every line point by least squares; round one found it within 0.35 px."""
    def undistort(pts, p):
        cx, cy, s, a1, a2 = p
        dx = pts[:, 0] - cx
        dy = pts[:, 1] - cy
        r2 = (dx * dx + dy * dy) / (R * R)
        f = 1 + a1 * r2 + a2 * r2 * r2
        return dx * f, dy * f

    def residuals(p, assign=None):
        s = p[2]
        ux, _ = undistort(vert, p)
        _, uy = undistort(horiz, p)
        kx = np.round(ux / s - 0.5) if assign is None else assign[0]
        ky = np.round(uy / s - 0.5) if assign is None else assign[1]
        return np.concatenate([ux - (kx + 0.5) * s, uy - (ky + 0.5) * s]), (kx, ky)

    p = np.array([572.5, 572.5, 167.0, 0.0, 0.0])
    for _ in range(30):
        r, assign = residuals(p)
        jac = np.zeros((len(r), len(p)))
        for j in range(len(p)):
            dp = np.zeros(len(p))
            dp[j] = 1e-4 if j >= 3 else 1e-2
            jac[:, j] = (residuals(p + dp, assign)[0] - r) / dp[j]
        step = np.linalg.lstsq(jac, -r, rcond=None)[0]
        p = p + step
        if np.abs(step).max() < 1e-7:
            break
    return dict(zip(["cx", "cy", "s", "a1", "a2"], map(float, p)))


class Grid:
    """The fitted bend, used both ways round."""

    def __init__(self, g):
        self.g = g

    def flat_plain(self, x, y):
        """Picture pixels to the flat grid's units, the formula as fitted."""
        g = self.g
        dx = x - g["cx"]
        dy = y - g["cy"]
        r2 = (dx * dx + dy * dy) / (R * R)
        f = 1 + g["a1"] * r2 + g["a2"] * r2 * r2
        return dx * f / g["s"], dy * f / g["s"]

    def flat(self, x, y):
        """The same, except that past the artwork's corners (r > sqrt 2) the bend carries on at
        the rate it has there. The fitted formula would fold the grid back on itself further out."""
        g = self.g
        dx = x - g["cx"]
        dy = y - g["cy"]
        r = np.sqrt(dx * dx + dy * dy) / R
        r0 = np.sqrt(2.0)
        u = lambda t: t * (1 + g["a1"] * t * t + g["a2"] * t ** 4)
        du = lambda t: 1 + 3 * g["a1"] * t * t + 5 * g["a2"] * t ** 4
        ur = np.where(r <= r0, u(r), u(r0) + du(r0) * (r - r0))
        f = np.where(r > 0, ur / np.maximum(r, 1e-9), 1.0)
        return dx * f / g["s"], dy * f / g["s"]


def gloss_edge_points(lum, bg):
    """Where the gloss band ends, column by column: the steepest drop in brightness between
    y = 380 and 700, if it is a real one (over 8 levels across 6 px)."""
    edge = []
    for x in range(0, N, 8):
        col = lum[:, x]
        ok = bg[:, x]
        best = None
        for y in range(380, 700):
            if ok[y - 3] and ok[y + 3]:
                d = col[y - 3] - col[y + 3]
                if best is None or d > best[0]:
                    best = (d, y)
        if best and best[0] > 8:
            edge.append((x, best[1], best[0]))
    return np.array(edge)


def fit_gloss(edge):
    """The gloss band's lower edge as the bottom of an ellipse centered on the artwork:
    y = yc + b * sqrt(1 - ((x - C) / A)^2), searched over A and solved for yc and b."""
    ex, ey = edge[:, 0] - C, edge[:, 1]
    best = None
    for a in np.arange(560, 1400, 10):
        t = np.sqrt(np.clip(1 - (ex / a) ** 2, 0, None))
        m = np.stack([np.ones_like(t), t], 1)
        coef = np.linalg.lstsq(m, ey, rcond=None)[0]
        err = np.sqrt(np.mean((m @ coef - ey) ** 2))
        if best is None or err < best[0]:
            best = (err, a, coef)
    return {"A": float(best[1]), "yc": float(best[2][0]), "b": float(best[2][1])}


def gloss_edge_y(gloss, x):
    return gloss["yc"] + gloss["b"] * np.sqrt(np.clip(1 - ((x - C) / gloss["A"]) ** 2, 0, None))


def blend_surfaces(l_in, l_out, yy, ey):
    """Above the gloss edge, the gloss's surface; below, the other; a 6 px ramp between."""
    w = 3.0
    t = smoothstep(np.clip((yy - (ey - w)) / (2 * w), 0, 1))
    return l_in * (1 - t) + l_out * t


def line_distance(fx, fy, step, gx=None, gy=None):
    """Distance in picture pixels from each pixel to the nearest grid line. The flat grid's
    rate of change per pixel converts flat units to pixels; it can be given, as when the line
    positions have been corrected but the rate should stay the smooth bend's."""
    if gx is None:
        gx = np.gradient(fx, axis=1) / step
        gy = np.gradient(fy, axis=0) / step
    return np.minimum(np.abs(fx - np.round(fx - 0.5) - 0.5) / np.abs(gx),
                      np.abs(fy - np.round(fy - 0.5) - 0.5) / np.abs(gy))


# ------------------------------------------------------------------------------- the model

class Model:
    """Everything measured, and the background drawn from it."""

    def __init__(self, art):
        rgba = art.astype(float)
        self.rgb = rgba[..., :3]
        self.alpha = rgba[..., 3]
        self.lum = self.rgb.mean(axis=2)
        self.sat = self.rgb.max(axis=2) - self.rgb.min(axis=2)
        yy, xx = np.mgrid[0:N, 0:N].astype(float)

        gray = (self.sat < 8) & (self.alpha == 255)
        vert, horiz = find_line_points(self.lum, gray)
        self.grid = Grid(fit_fisheye(vert, horiz))

        fx, fy = self.grid.flat(xx, yy)
        s = self.grid.g["s"]
        line_dist = np.minimum(np.abs(fx - np.round(fx - 0.5) - 0.5),
                               np.abs(fy - np.round(fy - 0.5) - 0.5)) * s
        bg = gray & (line_dist > 3)
        self.gloss = fit_gloss(gloss_edge_points(self.lum, bg))

        # brightness above and below the gloss edge: a degree-5 surface each
        xn, yn = (xx - C) / R, (yy - C) / R
        ey = gloss_edge_y(self.gloss, xx)
        self.poly = {}
        for name, m in (("in", bg & (yy < ey - 5)), ("out", bg & (yy > ey + 5))):
            self.poly[name] = np.linalg.lstsq(basis(xn[m], yn[m]), self.lum[m], rcond=None)[0]
        b = basis(xn, yn)
        surf = blend_surfaces(b @ self.poly["in"], b @ self.poly["out"], yy, ey)

        # a grid line's cross-section: the artwork's brightness over the surface, by distance
        d = line_distance(fx, fy, 1.0)
        ok = (self.sat < 8) & (self.alpha == 255) & (np.abs(yy - ey) > 8)
        ratio = self.lum / surf
        profile = []
        for k in range(16):
            m = ok & (d >= k * 0.5) & (d < (k + 1) * 0.5)
            profile.append(float(np.median(ratio[m])) if m.sum() > 50 else 1.0)
        self.profile = np.array(profile)

        # how the lines fade toward the edges: the artwork's line darkness over the model's, by
        # radius. The model image is rounded down to whole levels first, as it was in round one.
        self.fade = None
        self.ends = None
        model0 = np.clip(self.background(0, 0, N, N), 0, 255).astype(np.uint8).astype(float)
        self.fade = self._fit_fade(vert, horiz, surf, model0)

        # the line ends: each line's last 80 px of real pixels decide where it continues
        self.ends = self._fit_ends(vert, horiz)

    def _fit_fade(self, vert, horiz, surf, model0):
        def depth(img, x, y, axis):
            xi, yi = int(round(x)), int(round(y))
            if axis == 0:
                v = (img[yi, xi] + min(img[yi, xi - 1], img[yi, xi + 1])) / 2
            else:
                v = (img[yi, xi] + min(img[yi - 1, xi], img[yi + 1, xi])) / 2
            return 1 - v / surf[yi, xi]
        pts = [(x, y, 0) for x, y in vert] + [(x, y, 1) for x, y in horiz]
        real = np.array([depth(self.lum, x, y, ax) for x, y, ax in pts])
        drawn = np.array([depth(model0, x, y, ax) for x, y, ax in pts])
        xy = np.array([(x, y) for x, y, _ in pts])
        r = np.hypot(xy[:, 0] - C, xy[:, 1] - C) / R
        ratio = real / np.maximum(drawn, 1e-3)
        ok = (drawn > 0.05) & (ratio < 3)
        return np.polyfit(r[ok], ratio[ok], 2)

    def _fit_ends(self, vert, horiz):
        s = self.grid.g["s"]
        win = 80 / s
        ends = {}
        for axis, pts in ((0, vert), (1, horiz)):
            fx, fy = self.grid.flat_plain(pts[:, 0], pts[:, 1])
            across = fx if axis == 0 else fy
            along = fy if axis == 0 else fx
            k = np.round(across - 0.5) + 0.5
            for line in np.unique(k):
                m = k == line
                a = along[m]
                res = across[m] - line
                fits = []
                for side, sel in (("low", a < a.min() + win), ("high", a > a.max() - win)):
                    fits.append({"side": side, "c": np.polyfit(a[sel], res[sel], 1),
                                 "center": float(np.mean(a[sel])),
                                 "edge": float(a.min() if side == "low" else a.max())})
                ends[(axis, float(line))] = fits
        return ends

    def end_correction(self, across, along, axis):
        """How far each line really sits from the smooth bend near its ends, in flat units.
        Each end's straight fit counts fully from its window's middle outward and fades to
        nothing over the window going inward, so neither is stretched across the line."""
        k = np.round(across - 0.5) + 0.5
        out = np.zeros_like(across)
        for (ax, line), fits in self.ends.items():
            if ax != axis:
                continue
            m = k == line
            if not m.any():
                continue
            lo, hi = fits
            a = along[m]
            span = abs(lo["edge"] - lo["center"]) * 2
            w_lo = smoothstep(np.clip((lo["center"] + span - a) / span, 0, 1))
            w_hi = smoothstep(np.clip((a - (hi["center"] - span)) / span, 0, 1))
            out[m] = w_lo * np.polyval(lo["c"], a) + w_hi * np.polyval(hi["c"], a)
        return out

    def shade(self, xx, yy, step):
        """How much the grid darkens each pixel: 1 away from lines, less on them."""
        fx, fy = self.grid.flat(xx, yy)
        gx = np.gradient(fx, axis=1) / step
        gy = np.gradient(fy, axis=0) / step
        if self.ends is not None:
            fx, fy = fx - self.end_correction(fx, fy, 0), fy - self.end_correction(fy, fx, 1)
        d = line_distance(fx, fy, step, gx, gy)
        sh = np.interp(d, np.arange(len(self.profile)) * 0.5 + 0.25, self.profile,
                       left=self.profile[0], right=1.0)
        if self.fade is not None:
            rr = np.hypot(xx - C, yy - C) / R
            sh = 1 - (1 - sh) * np.maximum(np.polyval(self.fade, rr), 0.3)
        return sh

    def background(self, x0, y0, size, out):
        """The redrawn background over the square [x0, x0 + size) of the artwork's own pixel
        coordinates, drawn `out` pixels across. Inside the artwork only."""
        step = size / out
        yy, xx = np.mgrid[0:out, 0:out].astype(float)
        xx = x0 + (xx + 0.5) * step - 0.5
        yy = y0 + (yy + 0.5) * step - 0.5
        b = basis((xx - C) / R, (yy - C) / R)
        surf = blend_surfaces(b @ self.poly["in"], b @ self.poly["out"], yy,
                              gloss_edge_y(self.gloss, xx))
        return np.clip(surf * self.shade(xx, yy, step), 0, 255)

    def background_beyond(self, x0, size, out):
        """The same, for a region reaching past the artwork's edges. Past them the brightness
        carries on along the edge's own slope and levels off over 40 px, so the surfaces are
        never asked about places they never saw; and the gloss edge carries on at the height it
        reaches at the artwork's sides."""
        step = size / out
        yy, xx = np.mgrid[0:out, 0:out].astype(float)
        xx = x0 + (xx + 0.5) * step - 0.5
        yy = x0 + (yy + 0.5) * step - 0.5
        cx = np.clip(xx, 0, N - 1)
        cy = np.clip(yy, 0, N - 1)
        tau, h = 40.0, 1.0
        ex, ey_ = xx - cx, yy - cy
        dist = np.hypot(ex, ey_)
        ux = np.where(dist > 0, ex / np.maximum(dist, 1e-9), 0)
        uy = np.where(dist > 0, ey_ / np.maximum(dist, 1e-9), 0)
        ramp = tau * (1 - np.exp(-dist / tau))

        def carried(p):
            base = basis((cx - C) / R, (cy - C) / R) @ p
            gxl = (basis((cx + h - C) / R, (cy - C) / R) @ p
                   - basis((cx - h - C) / R, (cy - C) / R) @ p) / (2 * h)
            gyl = (basis((cx - C) / R, (cy + h - C) / R) @ p
                   - basis((cx - C) / R, (cy - h - C) / R) @ p) / (2 * h)
            return base + (gxl * ux + gyl * uy) * ramp
        surf = blend_surfaces(carried(self.poly["in"]), carried(self.poly["out"]), yy,
                              gloss_edge_y(self.gloss, cx))
        return np.clip(surf * self.shade(xx, yy, step), 0, 255)


# ------------------------------------------------------------ joining the artwork and model

def keep_weight(alpha):
    """How much of each pixel comes from the artwork: none in its soft edge pixels (lighter than
    the picture under them, an export artifact along its whole outline, rounded corners and
    straight sides alike), then all of it from 4 px inside, blended in between."""
    inside = alpha == 255
    dist = np.zeros(alpha.shape)
    cur = inside.copy()
    for k in range(1, 16):
        p = np.pad(cur, 1, constant_values=False)
        er = cur.copy()
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                er &= p[1 + dy:1 + dy + N, 1 + dx:1 + dx + N]
        dist[cur & ~er] = k
        cur = er
    dist[cur] = 99
    yy, xx = np.mgrid[0:N, 0:N]
    dist = np.minimum(dist, np.minimum(np.minimum(xx, yy), np.minimum(N - 1 - xx, N - 1 - yy)) + 1)
    return smoothstep(np.clip((dist - 1) / 3.0, 0, 1))


def box_blur(v, m, r):
    """The average of v over a (2r+1) square, counting only where m is true."""
    def csum(a):
        c = np.cumsum(np.cumsum(np.pad(a, ((1, 0), (1, 0))), 0), 1)
        y0 = np.clip(np.arange(N) - r, 0, N)
        y1 = np.clip(np.arange(N) + r + 1, 0, N)
        return c[y1][:, y1] - c[y0][:, y1] - c[y1][:, y0] + c[y0][:, y0]
    return csum(v * m) / np.maximum(csum(m.astype(float)), 1e-9)


def full_square(model):
    """The artwork with its corners and rim filled in. Where the redrawn background takes over,
    the smoothed difference between the artwork and the model along the seam is spread across
    the filled part (the smoothest surface agreeing with it at the seam), so the tone carries
    straight over and no arc shows."""
    w = keep_weight(model.alpha)
    drawn = model.background(0, 0, N, N)
    diff = model.lum - drawn
    known = (w >= 1) & (model.sat < 8) & (np.abs(diff) < 4)
    d = box_blur(diff, known, 8)
    unknown = w < 1
    d[unknown] = 0.0
    fill = d.copy()
    for _ in range(4000):
        p = np.pad(fill, 1, mode="edge")
        fill = np.where(unknown, (p[:-2, 1:-1] + p[2:, 1:-1] + p[1:-1, :-2] + p[1:-1, 2:]) / 4, d)
    corrected = drawn + np.where(unknown, fill, 0)
    full = model.rgb * w[..., None] + corrected[..., None] * (1 - w[..., None])
    return np.clip(np.round(full), 0, 255).astype(np.uint8)


def adaptive_layer(model, square):
    """Android's 108 dp layer, LAYER_PX across, with the full square in its middle at
    ARTWORK_DP. 69 dp puts the far corners of the "I" (0.952 of the artwork's half-width out)
    on the 66 dp circle every launcher shape keeps, so no phone clips the "I". The border around
    it is redrawn; along each of the square's edges, the difference over its last 3 px (smoothed
    along the edge) is added outside, fading over 60 px, so the tone carries over there too."""
    size_px = N * 108.0 / ARTWORK_DP
    x0 = C - size_px / 2 + 0.5
    canvas = model.background_beyond(x0, size_px, LAYER_PX)
    ow = int(round(N * LAYER_PX / size_px))
    off = (LAYER_PX - ow) // 2
    sq = np.asarray(Image.fromarray(square).resize((ow, ow), Image.LANCZOS)).astype(float)
    inside = np.zeros((LAYER_PX, LAYER_PX), bool)
    inside[off:off + ow, off:off + ow] = True
    diff = sq.mean(axis=2) - canvas[off:off + ow, off:off + ow]

    def edge_profile(d):
        v = d.mean(axis=0)
        good = np.abs(v) < 4
        ker = np.ones(31)
        num = np.convolve(np.where(good, v, 0), ker, "same")
        den = np.convolve(good.astype(float), ker, "same")
        return num / np.maximum(den, 1e-9)
    edges = {"top": edge_profile(diff[0:3]), "bottom": edge_profile(diff[-3:][::-1]),
             "left": edge_profile(diff[:, 0:3].T), "right": edge_profile(diff[:, -3:][:, ::-1].T)}
    yy, xx = np.mgrid[0:LAYER_PX, 0:LAYER_PX]
    qx = np.clip(xx - off, 0, ow - 1)
    qy = np.clip(yy - off, 0, ow - 1)
    dxo = np.where(xx < off, off - xx, np.where(xx >= off + ow, xx - (off + ow - 1), 0))
    dyo = np.where(yy < off, off - yy, np.where(yy >= off + ow, yy - (off + ow - 1), 0))
    val = np.zeros((LAYER_PX, LAYER_PX))
    cnt = np.zeros((LAYER_PX, LAYER_PX))
    for name, m, t in (("top", yy < off, qx), ("bottom", yy >= off + ow, qx),
                       ("left", xx < off, qy), ("right", xx >= off + ow, qy)):
        val += np.where(m, edges[name][t], 0)
        cnt += m
    corr = (val / np.maximum(cnt, 1)) * np.exp(-np.hypot(dxo, dyo) / 60.0)
    canvas = canvas + np.where(inside, 0.0, corr)
    canvas = canvas[..., None].repeat(3, axis=2)
    canvas[off:off + ow, off:off + ow] = sq
    return np.clip(np.round(canvas), 0, 255).astype(np.uint8)


# ------------------------------------------------------------------------------- the files

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- The launcher icon from Android 8 on: one layer, the whole picture, which each
     phone cuts to its own shape. Android 5 to 7 use mipmap-*/ic_launcher.png instead.
     Made by tools/icon/make_icons.py. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@android:color/transparent"/>
</adaptive-icon>
"""


ICO_SIZES = (16, 20, 24, 32, 40, 48, 64, 96, 128, 256)


def write(out, art, square, layer):
    icons = os.path.join(out, "assets", "icons")
    res = os.path.join(out, "src", "android", "res")
    os.makedirs(icons, exist_ok=True)
    Image.fromarray(square).convert("RGBA").resize((512, 512), Image.LANCZOS).save(
        os.path.join(icons, "icon-play-512.png"), optimize=True)
    layer_img = Image.fromarray(layer)
    art_img = Image.fromarray(art)
    for name, scale in DENSITIES:
        d = os.path.join(res, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        layer_img.resize((round(108 * scale),) * 2, Image.LANCZOS).save(
            os.path.join(d, "ic_launcher_background.png"), optimize=True)
        art_img.resize((round(48 * scale),) * 2, Image.LANCZOS).save(
            os.path.join(d, "ic_launcher.png"), optimize=True)
    d = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "ic_launcher.xml"), "w") as f:
        f.write(ADAPTIVE_XML)
    # Windows draws an icon's own transparency, so it gets the artwork with its rounded corners,
    # at every size Windows asks for: the taskbar, the Start menu, Explorer's views.
    frames = [art_img.resize((size, size), Image.LANCZOS) for size in ICO_SIZES]
    frames[-1].save(os.path.join(icons, "InvMgr.ico"), format="ICO",
                    sizes=[(size, size) for size in ICO_SIZES], append_images=frames[:-1])
    # Linux desktops draw an icon's transparency as well, so the Linux downloads get the same
    # rounded artwork, as one 512 px picture that the desktop scales for the menu and the dock.
    art_img.resize((512, 512), Image.LANCZOS).save(os.path.join(icons, "icon-linux-512.png"), optimize=True)


def preview(path, art, square, layer):
    """A sheet of every shape: the four common launcher masks cut from the layer's middle
    72 dp, Play's own rounding of the full square, Android 5 to 7's icon as drawn, and the whole
    layer with its 72 dp window (red) and 66 dp safe circle (blue)."""
    v = LAYER_PX * 72 // 108
    off = (LAYER_PX - v) // 2
    view = Image.fromarray(layer).crop((off, off, off + v, off + v))
    ss = 3

    def mask(kind, n=v):
        n3 = n * ss
        yy, xx = np.mgrid[0:n3, 0:n3].astype(float)
        u = (xx + 0.5) / n3 * 2 - 1
        w = (yy + 0.5) / n3 * 2 - 1
        if kind == "circle":
            m = u * u + w * w <= 1
        elif kind == "squircle":
            m = np.abs(u) ** 4 + np.abs(w) ** 4 <= 1
        elif kind in ("rounded square", "play"):
            r = 0.16 if kind == "rounded square" else 0.4
            ax = np.maximum(np.abs(u) - (1 - r), 0)
            ay = np.maximum(np.abs(w) - (1 - r), 0)
            m = ax * ax + ay * ay <= r * r
        else:  # teardrop: three corners half-round, the bottom right 15%
            r = 0.3
            ax = np.maximum(u - (1 - r), 0)
            ay = np.maximum(w - (1 - r), 0)
            m = np.where((u > 0) & (w > 0), ax * ax + ay * ay <= r * r, np.hypot(u, w) <= 1)
        return Image.fromarray((m * 255).astype(np.uint8)).resize((n, n), Image.LANCZOS)

    t = 300
    tiles = []

    def tile(img, m, label):
        out = Image.new("RGB", (t, t + 28), (214, 214, 220))
        out.paste(img.resize((t - 40, t - 40), Image.LANCZOS), (20, 20),
                  m.resize((t - 40, t - 40), Image.LANCZOS))
        ImageDraw.Draw(out).text((20, t + 4), label, fill=(20, 20, 20))
        return out
    for kind in ("circle", "squircle", "rounded square", "teardrop"):
        tiles.append(tile(view, mask(kind), kind + " (Android)"))
    tiles.append(tile(Image.fromarray(square).resize((v, v), Image.LANCZOS), mask("play"),
                      "Play Store (full square)"))
    art_img = Image.fromarray(art).resize((v, v), Image.LANCZOS)
    tiles.append(tile(art_img.convert("RGB"), art_img.getchannel("A"), "Android 5-7 (as drawn)"))
    whole = Image.fromarray(layer).resize((t - 40, t - 40), Image.LANCZOS)
    k = (t - 40) / 108
    d = ImageDraw.Draw(whole)
    d.rectangle([18 * k, 18 * k, 90 * k, 90 * k], outline=(255, 80, 80), width=2)
    d.ellipse([21 * k, 21 * k, 87 * k, 87 * k], outline=(80, 200, 255), width=2)
    last = Image.new("RGB", (t, t + 28), (214, 214, 220))
    last.paste(whole, (20, 20))
    ImageDraw.Draw(last).text((20, t + 4), "whole layer: red 72 dp, blue safe 66 dp", fill=(20, 20, 20))
    tiles.append(last)
    sheet = Image.new("RGB", (4 * t, 2 * (t + 28)), (214, 214, 220))
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % 4) * t, (i // 4) * (t + 28)))
    sheet.save(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=REPO, help="where to write (default: the project)")
    ap.add_argument("--preview", help="also save a sheet of every shape here")
    args = ap.parse_args()
    art = np.asarray(Image.open(SOURCE).convert("RGBA"))
    if art.shape[:2] != (N, N):
        sys.exit("expected a %d px square at %s, found %s" % (N, SOURCE, art.shape[:2]))
    model = Model(art)
    square = full_square(model)
    layer = adaptive_layer(model, square)
    write(args.out, art, square, layer)
    if args.preview:
        preview(args.preview, art, square, layer)
    print("fish-eye:", json.dumps({k: round(v, 5) for k, v in model.grid.g.items()}))
    print("written under", args.out)


if __name__ == "__main__":
    main()
