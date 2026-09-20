#!/usr/bin/env node
//
// Differential test harness for M5's 3D view.
//
// WHY THIS EXISTS
//
// The same reason as original-engine.js and original-loadstate.js: for anything where being
// subtly wrong still looks plausible, a test written from my reading of the original only
// proves the Java agrees with my reading.
//
// The 3D view has one place where that risk is unusually sharp. The per-face shading
// multiplies the item's HSL *lightness*, rounds to a whole percent, then clamps; the
// OD-1 spike used JavaFX's Color.hsb instead, which multiplies a different quantity. Both
// produce a plausible-looking box. Only a diff against the original's own arithmetic tells
// them apart, and it can demand exact equality because the intermediate value is an integer.
//
// So the block marked VERBATIM below is copied unchanged out of
// docs/original/InvMgr_V1.3.0.html (itemShades 2853-2857, and the sizing arithmetic out of
// drawSurfaceCanvas 2830-2841). The browser-only parts (the canvas element, the 2D context,
// the actual stroking and the gradient fill) are dropped, because node has no canvas; what
// is kept is every number that decides what those calls would have drawn. The Java side's
// pixels are then checked against those numbers by sampling the image it generates.
//
// USAGE
//   node tools/golden/original-3d.js --run     # regenerate the expected results
//
// The inputs are fixed lists written out below rather than a seeded generator, because the
// interesting cases here are boundaries (a lightness that clamps at 4, one that clamps at 92,
// a color string the original's regex rejects) and those are worth naming rather than
// hoping a random spread lands on them. Do not hand-edit the golden file.

const fs = require('fs');
const path = require('path');

const GOLDEN_DIR = path.join(__dirname, '..', '..', 'src', 'test', 'resources', 'golden');
const SHADES = path.join(GOLDEN_DIR, '3d-shades.expected.json');
const SURFACES = path.join(GOLDEN_DIR, '3d-surfaces.expected.json');
const OVERHEAD = path.join(GOLDEN_DIR, '3d-overhead.expected.json');

const M_PER_FT = 0.3048;

// ═══════════════════════ VERBATIM from InvMgr_V1.3.0.html ═══════════════════════

/* Per-face shades derived from the item's flat hsl color: top lightest,
   N/S mid, E/W darker, bottom darkest; 'edge' for borders/edge lines. */
function itemShades(color) {
  const m = /^hsl\((\d+),\s*(\d+)%,\s*(\d+)%\)$/.exec(color || '');
  const H = m ? +m[1] : 0, S = m ? +m[2] : 55, L = m ? +m[3] : 42;
  const hsl = l => 'hsl(' + H + ',' + S + '%,' + Math.max(4, Math.min(92, Math.round(l))) + '%)';
  return { top: hsl(L * 1.22), ns: hsl(L), ew: hsl(L * 0.8), bottom: hsl(L * 0.55), edge: hsl(L * 0.45) };
}

/* The sizing half of drawSurfaceCanvas. Everything here decides what the grid and the
   vignette get drawn at; the drawing calls themselves need a canvas and are checked on the
   Java side by sampling the image. */
function surfaceMetrics(wFt, hFt, metric) {
  const s = Math.min(96, 2048 / Math.max(wFt, hFt)); // px per ft, capped
  const w = Math.max(2, Math.round(wFt * s)), h = Math.max(2, Math.round(hFt * s));
  const lineWidth = Math.max(1, 2 * s / 96);
  const step = metric ? s / M_PER_FT : s;   // 1 m or 1 ft grid
  // Grid lines run from one step in to just short of the far edge; the room's own boundary
  // never carries a line.
  const verticals = [], horizontals = [];
  for (let x = step; x < w - 1; x += step) verticals.push(x);
  for (let y = step; y < h - 1; y += step) horizontals.push(y);
  // createRadialGradient(w/2, h/2, 0, w/2, h/2, 0.7 * max(w,h)), transparent to
  // rgba(17,17,17,0.7). Note the radius is 0.7 of the LONGER SIDE, not of the half-diagonal.
  const vignetteRadius = 0.7 * Math.max(w, h);
  return { s, w, h, lineWidth, step, verticals, horizontals, vignetteRadius };
}

/* The CSS3D adapter's overhead camera height, and the perspective distance it is written in
   terms of. `room` stands in for the original's global; `vp.clientWidth/Height` become
   arguments. Note overhead() reads `normalP`, which resize() set from the viewport height,
   so the two are reproduced together or not at all. */
function overheadHeight(room, vw, vh) {
  const normalP = Math.round(Math.max(300, vh * 0.75)); // ≈ 67° vertical FOV
  const y = Math.max(room.l * normalP / vh, room.w * normalP / vw) * 1.06;
  return { normalP, y: Math.max(y, room.h + 2) };
}

// ═══════════════════════ end VERBATIM ═══════════════════════

// Every color the app itself can produce is hsl(H,55%,42%); saturation and lightness are
// fixed at creation and only the hue is random. That family is the main sweep. The rest are
// boundaries: lightnesses where a multiplier crosses one of the clamps, and strings the
// original's regex rejects.
function shadeInputs() {
  const inputs = [];
  for (let h = 0; h < 360; h++) inputs.push('hsl(' + h + ',55%,42%)');
  for (let l = 0; l <= 100; l++) inputs.push('hsl(200,55%,' + l + '%)');
  for (let s = 0; s <= 100; s += 5) inputs.push('hsl(120,' + s + '%,60%)');
  inputs.push(
      'hsl(0,0%,0%)',            // every multiplier clamps up to the 4% floor
      'hsl(0,100%,100%)',        // top and N/S clamp down to the 92% ceiling
      'hsl(359,55%,76%)',        // 76 * 1.22 = 92.72 -> rounds to 93 -> clamps to 92
      'hsl(200,55%,75%)',        // 75 * 1.22 = 91.5  -> rounds to 92, no clamp needed
      'hsl(200,55%,9%)',         // 9 * 0.45 = 4.05  -> rounds to 4, exactly the floor
      'hsl(200,55%,8%)',         // 8 * 0.45 = 3.6   -> rounds to 4 as well, by rounding
      'hsl(200,55%,7%)',         // 7 * 0.45 = 3.15  -> rounds to 3 -> clamped up to 4
      'hsl( 200,55%,42%)',       // leading space: REJECTED, falls back to red
      'hsl(200, 55%, 42%)',      // spaces after the commas: accepted by the regex
      'hsl(200,55.0%,42%)',      // decimal: REJECTED
      'hsl(200,55%,42.5%)',      // decimal: REJECTED
      'rgb(10,20,30)',           // wrong function: REJECTED
      '#3a3a3a',                 // hex: REJECTED
      '',                        // empty: REJECTED
      null);                     // absent: REJECTED
  return inputs;
}

// Room sizes that exercise the resolution cap from both sides. Room limits are 1-200 ft for
// width and length and 1-50 ft for height (Room.MIN_W..MAX_H), so a wall can be 200 x 50.
function surfaceInputs() {
  return [
    { w: 12, h: 10 },    // the default room's floor
    { w: 12, h: 8 },     // its north wall
    { w: 1, h: 1 },      // the smallest legal room
    { w: 21, h: 21 },    // s = 96 exactly at the cap boundary (2048 / 21.33)
    { w: 22, h: 22 },    // just past it, so s drops below 96 and lineWidth starts shrinking
    { w: 200, h: 200 },  // the largest legal floor: s = 10.24, lineWidth clamps to 1
    { w: 200, h: 50 },   // the largest legal wall, non-square
    { w: 40, h: 3 },     // a wide, short wall
  ];
}

// Rooms crossed with viewports. The rooms cover a wide floor, a long floor, a square one, the
// legal extremes, and a tall narrow closet whose ceiling clearance decides the answer instead
// of its floor area. The viewports include the reference resolution, a couple of ordinary
// window shapes, a portrait phone, and two short enough to sit under the 300 px floor.
function overheadInputs() {
  const rooms = [
    { w: 12, l: 10, h: 8 },     // the default room
    { w: 40, l: 8, h: 8 },      // wide and shallow: width decides
    { w: 8, l: 40, h: 8 },      // long and narrow: length decides
    { w: 20, l: 20, h: 8 },     // square
    { w: 1, l: 1, h: 1 },       // smallest legal
    { w: 200, l: 200, h: 50 },  // largest legal
    { w: 4, l: 4, h: 20 },      // a tall closet: the ceiling clearance decides
  ];
  const viewports = [
    { vw: 2560, vh: 1440 },   // the reference resolution
    { vw: 1920, vh: 1080 },
    { vw: 1280, vh: 800 },
    { vw: 1080, vh: 2220 },   // the reference phone, portrait
    { vw: 1441, vh: 1441 },   // an odd height, so normalP's rounding actually does something
    { vw: 800, vh: 399 },     // one pixel under the 300 px floor's threshold
    { vw: 800, vh: 200 },     // well under it
  ];
  const cases = [];
  for (const room of rooms) {
    for (const vp of viewports) {
      cases.push({ room, ...vp, ...overheadHeight(room, vp.vw, vp.vh) });
    }
  }
  return cases;
}

function run() {
  fs.mkdirSync(GOLDEN_DIR, { recursive: true });

  const shades = shadeInputs().map(color => ({ color, shades: itemShades(color) }));
  fs.writeFileSync(SHADES, JSON.stringify(shades, null, 2) + '\n');
  console.log('wrote ' + shades.length + ' shade cases to ' + SHADES);

  const surfaces = [];
  for (const { w, h } of surfaceInputs()) {
    for (const metric of [false, true]) {
      surfaces.push({ wFt: w, hFt: h, metric, metrics: surfaceMetrics(w, h, metric) });
    }
  }
  fs.writeFileSync(SURFACES, JSON.stringify(surfaces, null, 2) + '\n');
  console.log('wrote ' + surfaces.length + ' surface cases to ' + SURFACES);

  const overheads = overheadInputs();
  fs.writeFileSync(OVERHEAD, JSON.stringify(overheads, null, 2) + '\n');
  console.log('wrote ' + overheads.length + ' overhead cases to ' + OVERHEAD);
}

if (process.argv.includes('--run')) {
  run();
} else {
  console.error('usage: node tools/golden/original-3d.js --run');
  process.exit(2);
}
