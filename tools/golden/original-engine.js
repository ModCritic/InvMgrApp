#!/usr/bin/env node
//
// Differential test harness for M2's geometry: collision, sliding, stacking, placement and
// layering.
//
// WHY THIS EXISTS
//
// These are the algorithms where being subtly wrong looks almost right. A stacking pass with
// the wrong sort order produces plausible heights; a slide that uses a stale coordinate
// mostly works and occasionally lets a box pass through a corner. Unit tests written from my
// reading of the original would encode my misreadings as expectations.
//
// So, exactly as tools/golden/original-loadstate.js does for the save format: the block
// marked VERBATIM below is copied unchanged out of docs/original/InvMgr_V1.3.0.html
// (itemRect/rectsOverlap 981-988, recomputeAllBaseHeights 992-1015, settleAllBaseHeights
// 1027-1041, zIndexFor/isVisible 1043-1052, the dim rule 1067-1070, slideAxis 1179-1197,
// clampItem 1198-1234, findOpenSpot 1417-1450). The browser-only parts — DOM updates,
// status messages — are dropped; the arithmetic is untouched. The module-level `room`,
// `items` and `layerCollision` variables stand in for the original's globals and are set
// per scenario.
//
// USAGE
//   node tools/golden/original-engine.js --gen 200   # regenerate the scenario fixture
//   node tools/golden/original-engine.js --run       # regenerate the expected results
//
// The scenarios are produced by a SEEDED generator, so regenerating gives identical output.
// Do not hand-edit either file.

const fs = require('fs');
const path = require('path');

const FIXTURE = path.join(__dirname, '..', '..', 'src', 'test', 'resources', 'fixtures',
    'engine-scenarios.json');
const GOLDEN = path.join(__dirname, '..', '..', 'src', 'test', 'resources', 'golden',
    'engine-scenarios.expected.json');

// Stand-ins for the original's globals.
let room = { w: 12, l: 10, h: 8 };
let items = [];
let layerCollision = false;

// ═══════════════════════ VERBATIM from InvMgr_V1.3.0.html ═══════════════════════

const PX_PER_INCH = 8, PX_PER_FOOT = 96;
const inchesToPx = i => i * PX_PER_INCH;
const feetToPx = f => f * PX_PER_FOOT;

function itemRect(item) {
  return { x: item.x_px, y: item.y_px,
           x2: item.x_px + inchesToPx(item.w_in), y2: item.y_px + inchesToPx(item.l_in) };
}
function rectsOverlap(a, b) {
  return a.x < b.x2 && a.x2 > b.x && a.y < b.y2 && a.y2 > b.y;
}

function recomputeAllBaseHeights() {
  if (layerCollision) return;
  const sorted = items.filter(it => !it.planned).sort((a, b) => a.dragOrder - b.dragOrder);
  sorted.forEach(it => { it.baseHeight_in = 0; });
  for (let i = 0; i < sorted.length; i++) {
    const cur = sorted[i], cr = itemRect(cur);
    let maxTop = 0;
    for (let j = 0; j < i; j++) {
      const prev = sorted[j];
      if (rectsOverlap(cr, itemRect(prev))) {
        const top = prev.baseHeight_in + prev.h_in;
        if (top > maxTop) maxTop = top;
      }
    }
    cur.baseHeight_in = maxTop;
  }
}

function settleAllBaseHeights() {
  const sorted = items.filter(it => !it.planned).sort((a, b) => a.baseHeight_in - b.baseHeight_in);
  for (let i = 0; i < sorted.length; i++) {
    const cur = sorted[i], cr = itemRect(cur);
    let maxTop = 0;
    for (let j = 0; j < i; j++) {
      const prev = sorted[j];
      if (rectsOverlap(cr, itemRect(prev))) {
        const top = prev.baseHeight_in + prev.h_in;
        if (top > maxTop) maxTop = top;
      }
    }
    cur.baseHeight_in = maxTop;
  }
}

function zIndexFor(item) {
  if (layerCollision) return 100 + Math.round(item.baseHeight_in * 10);
  return 100 + item.dragOrder;
}
function isVisible(item, layerFeet) { return item.baseHeight_in < layerFeet * 12; }

function shouldDim(item, selItem) {
  const selDragOrder = selItem ? selItem.dragOrder : -1;
  return !!(selItem && !item.planned &&
    (layerCollision ? item.baseHeight_in > selItem.baseHeight_in : item.dragOrder > selDragOrder) &&
    rectsOverlap(itemRect(item), itemRect(selItem)));
}

function slideAxis(from, to, size, crossFrom, crossSize, obstacles, axis) {
  if (to === from) return from;
  const cMin = axis === 'x' ? 'y' : 'x', cMax = axis === 'x' ? 'y2' : 'x2';
  const min = axis === 'x' ? 'x' : 'y', max = axis === 'x' ? 'x2' : 'y2';
  let limit = to;
  if (to > from) {
    for (const o of obstacles) {
      if (!(crossFrom < o[cMax] && crossFrom + crossSize > o[cMin])) continue;
      if (o[min] >= from + size) limit = Math.min(limit, o[min] - size);
    }
    return Math.max(from, limit);
  } else {
    for (const o of obstacles) {
      if (!(crossFrom < o[cMax] && crossFrom + crossSize > o[cMin])) continue;
      if (o[max] <= from) limit = Math.max(limit, o[max]);
    }
    return Math.min(from, limit);
  }
}

function clampItem(item, nx, ny) {
  const cx = Math.round(Math.max(0, Math.min(feetToPx(room.w) - inchesToPx(item.w_in), nx)));
  const cy = Math.round(Math.max(0, Math.min(feetToPx(room.l) - inchesToPx(item.l_in), ny)));

  if (!layerCollision) return { nx: cx, ny: cy };

  const EPS = 1e-3;
  const wPx = inchesToPx(item.w_in), lPx = inchesToPx(item.l_in);
  const itemTop = item.baseHeight_in + item.h_in;
  const obstacles = items
    .filter(it => it.id !== item.id && !it.planned)
    .filter(it => it.baseHeight_in <= itemTop && it.baseHeight_in + it.h_in >= item.baseHeight_in)
    .filter(it => Math.abs((it.baseHeight_in + it.h_in) - item.baseHeight_in) > EPS &&
                  Math.abs((item.baseHeight_in + item.h_in) - it.baseHeight_in) > EPS)
    .map(itemRect);

  if (obstacles.some(o => rectsOverlap(itemRect(item), o))) return { nx: cx, ny: cy };

  const rx = slideAxis(item.x_px, cx, wPx, item.y_px, lPx, obstacles, 'x');
  const ry = slideAxis(item.y_px, cy, lPx, rx, wPx, obstacles, 'y');
  return { nx: Math.round(rx), ny: Math.round(ry) };
}

function findOpenSpot(w_in, l_in, preferredX, preferredY) {
  const wPx = inchesToPx(w_in), lPx = inchesToPx(l_in);
  const roomWPx = feetToPx(room.w), roomLPx = feetToPx(room.l);
  const clampX = x => Math.round(Math.max(0, Math.min(roomWPx - wPx, x)));
  const clampY = y => Math.round(Math.max(0, Math.min(roomLPx - lPx, y)));
  const defX = clampX(preferredX !== undefined && preferredX !== null ? preferredX : (roomWPx - wPx) / 2);
  const defY = clampY(preferredY !== undefined && preferredY !== null ? preferredY : (roomLPx - lPx) / 2);

  const obstacles = items.filter(it => !it.planned).map(itemRect);
  const rectAt = (x, y) => ({ x, y, x2: x + wPx, y2: y + lPx });
  const free = (x, y) => !obstacles.some(o => rectsOverlap(rectAt(x, y), o));

  if (free(defX, defY)) return { x: defX, y: defY };

  const step = Math.max(16, Math.min(wPx, lPx));
  const MAX_CHECKS = 4000;
  let checks = 0;
  for (let ring = 1; ring <= 300; ring++) {
    const cands = [];
    for (let dx = -ring; dx <= ring; dx++) {
      for (let dy = -ring; dy <= ring; dy++) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) !== ring) continue;
        cands.push({ x: clampX(defX + dx * step), y: clampY(defY + dy * step) });
      }
    }
    cands.forEach(c => { c.d = (c.x - defX) ** 2 + (c.y - defY) ** 2; });
    cands.sort((a, b) => a.d - b.d);
    for (const c of cands) {
      if (++checks > MAX_CHECKS) return { x: defX, y: defY };
      if (free(c.x, c.y)) return { x: c.x, y: c.y };
    }
  }
  return { x: defX, y: defY };
}

// ═════════════════════════ end verbatim ═════════════════════════

/** Deterministic PRNG (mulberry32) so a regenerated fixture is identical. */
function rng(seed) {
  return function () {
    seed |= 0; seed = seed + 0x6D2B79F5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

function generate(count) {
  const rand = rng(20260727);
  const pick = arr => arr[Math.floor(rand() * arr.length)];
  const between = (lo, hi) => lo + rand() * (hi - lo);

  const scenarios = [];

  // Hand-written edge cases first — the situations most likely to be got wrong, which
  // random generation would only hit by luck.
  scenarios.push({
    note: 'exactly touching footprints must not count as overlapping',
    room: { w: 12, l: 10, h: 8 }, layerCollision: true, layerFeet: 8, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 12, l_in: 12, h_in: 12, x_px: 0, y_px: 0, baseHeight_in: 0, planned: false },
      { id: 'b', dragOrder: 2, w_in: 12, l_in: 12, h_in: 12, x_px: 96, y_px: 0, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 1, nx: 96, ny: 0 }
  });
  scenarios.push({
    note: 'zero-gap stack: dragging the lower box while one rests exactly on top',
    room: { w: 20, l: 20, h: 10 }, layerCollision: true, layerFeet: 10, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 24, l_in: 24, h_in: 18, x_px: 100, y_px: 100, baseHeight_in: 0, planned: false },
      { id: 'b', dragOrder: 2, w_in: 24, l_in: 24, h_in: 12, x_px: 100, y_px: 100, baseHeight_in: 18, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 0, nx: 400, ny: 100 }
  });
  scenarios.push({
    note: 'already overlapping when Layer Collision switches on: room bounds only',
    room: { w: 20, l: 20, h: 10 }, layerCollision: true, layerFeet: 10, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 24, l_in: 24, h_in: 18, x_px: 100, y_px: 100, baseHeight_in: 0, planned: false },
      { id: 'b', dragOrder: 2, w_in: 24, l_in: 24, h_in: 18, x_px: 110, y_px: 110, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 1, nx: 300, ny: 300 }
  });
  scenarios.push({
    note: 'a big jump in one frame must stop at the true contact edge',
    room: { w: 30, l: 10, h: 8 }, layerCollision: true, layerFeet: 8, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 12, l_in: 12, h_in: 12, x_px: 0, y_px: 0, baseHeight_in: 0, planned: false },
      { id: 'b', dragOrder: 2, w_in: 12, l_in: 12, h_in: 12, x_px: 1000, y_px: 0, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 0, nx: 5000, ny: 0 }
  });
  scenarios.push({
    note: 'item wider than the room pins to the west wall rather than going negative',
    room: { w: 2, l: 2, h: 8 }, layerCollision: false, layerFeet: 8, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 100, l_in: 100, h_in: 12, x_px: 0, y_px: 0, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 0, nx: 500, ny: 500 }
  });
  scenarios.push({
    note: 'a three-high tower settles in one pass after the middle box shrinks',
    room: { w: 20, l: 20, h: 12 }, layerCollision: false, layerFeet: 12, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 24, l_in: 24, h_in: 12, x_px: 96, y_px: 96, baseHeight_in: 0, planned: false },
      { id: 'b', dragOrder: 2, w_in: 24, l_in: 24, h_in: 6, x_px: 96, y_px: 96, baseHeight_in: 12, planned: false },
      { id: 'c', dragOrder: 3, w_in: 24, l_in: 24, h_in: 12, x_px: 96, y_px: 96, baseHeight_in: 36, planned: false }
    ],
    op: { op: 'settle' }
  });
  scenarios.push({
    note: 'planned items are never obstacles and never get a height',
    room: { w: 20, l: 20, h: 10 }, layerCollision: false, layerFeet: 10, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 24, l_in: 24, h_in: 18, x_px: 96, y_px: 96, baseHeight_in: 0, planned: false },
      { id: 'ghost', dragOrder: 2, w_in: 24, l_in: 24, h_in: 18, x_px: 96, y_px: 96, baseHeight_in: 40, planned: true },
      { id: 'c', dragOrder: 3, w_in: 24, l_in: 24, h_in: 12, x_px: 96, y_px: 96, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'recompute' }
  });
  // ---- exact-adjacency cases ---------------------------------------------------------
  // Added after a mutation sweep: relaxing rectsOverlap's strict `<` to `<=` did NOT fail
  // the suite, because no scenario had two footprints whose edges met EXACTLY. Random
  // placement effectively never produces that. These four do, one per direction, using a
  // stacking op so the difference is observable: touching boxes must both stay on the floor,
  // whereas a `<=` comparison would stack the second on the first.
  for (const [dir, dx, dy] of [['east', 96, 0], ['west', -96, 0], ['south', 0, 96], ['north', 0, -96]]) {
    scenarios.push({
      note: `exact adjacency ${dir}: touching footprints must not stack`,
      room: { w: 12, l: 12, h: 8 }, layerCollision: false, layerFeet: 8, selected: 0,
      items: [
        { id: 'a', dragOrder: 1, w_in: 12, l_in: 12, h_in: 12, x_px: 192, y_px: 192, baseHeight_in: 0, planned: false },
        { id: 'b', dragOrder: 2, w_in: 12, l_in: 12, h_in: 12, x_px: 192 + dx, y_px: 192 + dy, baseHeight_in: 0, planned: false }
      ],
      op: { op: 'recompute' }
    });
    // The same geometry through the dim rule, which also calls rectsOverlap.
    scenarios.push({
      note: `exact adjacency ${dir}: touching footprints must not dim each other`,
      room: { w: 12, l: 12, h: 8 }, layerCollision: false, layerFeet: 8, selected: 0,
      items: [
        { id: 'a', dragOrder: 1, w_in: 12, l_in: 12, h_in: 12, x_px: 192, y_px: 192, baseHeight_in: 0, planned: false },
        { id: 'b', dragOrder: 2, w_in: 12, l_in: 12, h_in: 12, x_px: 192 + dx, y_px: 192 + dy, baseHeight_in: 0, planned: false }
      ],
      op: { op: 'layers' }
    });
  }

  // ---- the stale-X cases -------------------------------------------------------------
  // These exist because of a failed experiment: with 192 random scenarios plus the edge
  // cases above, deliberately reintroducing the known "Y pass uses the pre-slide X" bug did
  // NOT fail the differential test. Random configurations almost never line up so that the
  // difference is observable. Each scenario below is constructed so that it is: the X slide
  // is blocked partway, and an obstacle exists that the item's ORIGINAL x misses but its
  // POST-SLIDE x overlaps. With the bug, the item slides around that obstacle's corner.
  scenarios.push({
    note: 'stale-X bug: forward on both axes, second obstacle only reachable after the X slide',
    room: { w: 30, l: 30, h: 10 }, layerCollision: true, layerFeet: 10, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 12, l_in: 12, h_in: 12, x_px: 0, y_px: 0, baseHeight_in: 0, planned: false },
      { id: 'blockX', dragOrder: 2, w_in: 12, l_in: 12, h_in: 12, x_px: 200, y_px: 0, baseHeight_in: 0, planned: false },
      { id: 'blockY', dragOrder: 3, w_in: 12, l_in: 12, h_in: 12, x_px: 150, y_px: 300, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 0, nx: 1000, ny: 1000 }
  });
  scenarios.push({
    note: 'stale-X bug: backward on X, forward on Y',
    room: { w: 30, l: 30, h: 10 }, layerCollision: true, layerFeet: 10, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 12, l_in: 12, h_in: 12, x_px: 600, y_px: 0, baseHeight_in: 0, planned: false },
      { id: 'blockX', dragOrder: 2, w_in: 12, l_in: 12, h_in: 12, x_px: 400, y_px: 0, baseHeight_in: 0, planned: false },
      { id: 'blockY', dragOrder: 3, w_in: 24, l_in: 12, h_in: 12, x_px: 448, y_px: 300, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 0, nx: 0, ny: 1000 }
  });
  scenarios.push({
    note: 'stale-X bug: backward on both axes',
    room: { w: 30, l: 30, h: 10 }, layerCollision: true, layerFeet: 10, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 12, l_in: 12, h_in: 12, x_px: 600, y_px: 600, baseHeight_in: 0, planned: false },
      { id: 'blockX', dragOrder: 2, w_in: 12, l_in: 12, h_in: 12, x_px: 400, y_px: 600, baseHeight_in: 0, planned: false },
      { id: 'blockY', dragOrder: 3, w_in: 24, l_in: 12, h_in: 12, x_px: 448, y_px: 300, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'clampItem', itemIndex: 0, nx: 0, ny: 0 }
  });
  scenarios.push({
    note: 'findOpenSpot must step around a box sitting on the room centre',
    room: { w: 12, l: 10, h: 8 }, layerCollision: false, layerFeet: 8, selected: -1,
    items: [
      { id: 'a', dragOrder: 1, w_in: 24, l_in: 24, h_in: 12, x_px: 528, y_px: 384, baseHeight_in: 0, planned: false }
    ],
    op: { op: 'findOpenSpot', w_in: 24, l_in: 24 }
  });

  const OPS = ['clampItem', 'recompute', 'settle', 'findOpenSpot', 'layers'];

  while (scenarios.length < count) {
    const roomW = Math.round(between(4, 30));
    const roomL = Math.round(between(4, 30));
    const itemCount = 1 + Math.floor(rand() * 7);
    const its = [];
    for (let i = 0; i < itemCount; i++) {
      const w = Math.round(between(6, 60));
      const l = Math.round(between(6, 60));
      // Positions deliberately include values that push past the walls, and heights
      // deliberately include exact multiples so zero-gap stacks occur by construction.
      const baseChoices = [0, 0, 0, 12, 18, 24, 36, Math.round(between(0, 60))];
      its.push({
        id: 'i' + i,
        dragOrder: i + 1,
        w_in: w, l_in: l,
        h_in: pick([6, 12, 18, 24, 30, Math.round(between(4, 40))]),
        x_px: Math.round(between(-20, feetToPx(roomW))),
        y_px: Math.round(between(-20, feetToPx(roomL))),
        baseHeight_in: pick(baseChoices),
        planned: rand() < 0.15
      });
    }
    // Half the random scenarios are made "collision-prone": positions snapped to half-foot
    // multiples, and every item sharing a row or a column with the first. Purely uniform
    // random placement almost never lines items up, so blocking rarely happens and whole
    // branches of slideAxis go untested — the same coverage gap the stale-X cases exposed.
    const alignment = rand();
    if (alignment < 0.5 && its.length > 1) {
      const shareRow = alignment < 0.25;
      for (const it of its) {
        it.x_px = Math.round(it.x_px / 48) * 48;
        it.y_px = Math.round(it.y_px / 48) * 48;
        if (shareRow) {
          it.y_px = its[0].y_px;
        } else {
          it.x_px = its[0].x_px;
        }
      }
    }

    const op = pick(OPS);
    const scenario = {
      note: 'random',
      room: { w: roomW, l: roomL, h: Math.round(between(6, 14)) },
      layerCollision: rand() < 0.5,
      layerFeet: Math.round(between(0, 16) * 2) / 2,
      selected: rand() < 0.7 ? Math.floor(rand() * itemCount) : -1,
      items: its,
      op: null
    };
    if (op === 'clampItem') {
      scenario.op = {
        op: 'clampItem',
        itemIndex: Math.floor(rand() * itemCount),
        nx: Math.round(between(-200, feetToPx(roomW) + 200)),
        ny: Math.round(between(-200, feetToPx(roomL) + 200))
      };
    } else if (op === 'findOpenSpot') {
      scenario.op = rand() < 0.5
        ? { op: 'findOpenSpot', w_in: Math.round(between(6, 40)), l_in: Math.round(between(6, 40)) }
        : { op: 'findOpenSpot', w_in: Math.round(between(6, 40)), l_in: Math.round(between(6, 40)),
            x: Math.round(between(0, feetToPx(roomW))), y: Math.round(between(0, feetToPx(roomL))) };
    } else {
      scenario.op = { op };
    }
    scenarios.push(scenario);
  }
  fs.writeFileSync(FIXTURE, JSON.stringify(scenarios, null, 2));
  console.log(`wrote ${scenarios.length} scenarios -> ${path.basename(FIXTURE)}`);
}

function run() {
  const scenarios = JSON.parse(fs.readFileSync(FIXTURE, 'utf8'));
  const results = scenarios.map(s => {
    room = s.room;
    layerCollision = s.layerCollision;
    items = JSON.parse(JSON.stringify(s.items));      // fresh copy: ops mutate heights
    const selItem = s.selected >= 0 ? items[s.selected] : null;

    switch (s.op.op) {
      case 'clampItem': {
        const r = clampItem(items[s.op.itemIndex], s.op.nx, s.op.ny);
        return { x: r.nx, y: r.ny };
      }
      case 'recompute':
        recomputeAllBaseHeights();
        return { baseHeights: items.map(it => it.baseHeight_in) };
      case 'settle':
        settleAllBaseHeights();
        return { baseHeights: items.map(it => it.baseHeight_in) };
      case 'findOpenSpot': {
        const r = s.op.x !== undefined
          ? findOpenSpot(s.op.w_in, s.op.l_in, s.op.x, s.op.y)
          : findOpenSpot(s.op.w_in, s.op.l_in);
        return { x: r.x, y: r.y };
      }
      case 'layers':
        return {
          z: items.map(zIndexFor),
          visible: items.map(it => isVisible(it, s.layerFeet)),
          dim: items.map(it => shouldDim(it, selItem))
        };
      default:
        throw new Error('unknown op: ' + s.op.op);
    }
  });
  fs.writeFileSync(GOLDEN, JSON.stringify(results, null, 2));
  console.log(`wrote ${results.length} expected results -> ${path.basename(GOLDEN)}`);
}

const args = process.argv.slice(2);
if (args[0] === '--gen') {
  generate(parseInt(args[1] || '200', 10));
} else if (args[0] === '--run') {
  run();
} else {
  console.error('usage: original-engine.js --gen [count] | --run');
  process.exit(2);
}
