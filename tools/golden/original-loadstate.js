#!/usr/bin/env node
//
// Generates the golden files that src/test/java/.../SaveFormatTest.java checks against.
//
// WHY THIS EXISTS
//
// M1's job is to read every room_inventory.json the HTML app has ever written, and the
// HTML app's loader is 80 lines of very specific defensive behaviour: out-of-range values
// are *replaced* with defaults rather than pinned to the bound, a bad item ID silently
// drops the whole item, dragOrder falls back to serial, and so on. Testing the Java port
// against my own reading of that code would only prove the Java agrees with my reading.
//
// So instead: the validation block below is COPIED VERBATIM out of
// docs/original/InvMgr_V1.3.0.html (safeNum/safeId at lines 2271-2284, the loadState
// validation at 2286-2367), with the DOM calls that follow "All validation passed"
// left out because they touch the browser and not the data. Run it on a fixture and it
// emits what the original app would actually hold in memory after loading that file.
// The Java tests then assert byte-for-byte agreement with that output.
//
// The one intentional deviation is randColor: the original picks a random hue, which
// can't be asserted against. It is replaced with a fixed sentinel here, and the Java
// loader takes a colour supplier so its tests can inject the same sentinel.
//
// USAGE
//   node tools/golden/original-loadstate.js <fixture.json>       # prints validated state
//   node tools/golden/original-loadstate.js --all                # regenerates all goldens
//
// Regenerate the goldens only when a fixture changes. If output changes without a
// fixture change, something is wrong — this file is a frozen copy of shipped behaviour.

const fs = require('fs');
const path = require('path');

const FIXTURE_DIR = path.join(__dirname, '..', '..', 'src', 'test', 'resources', 'fixtures');
const GOLDEN_DIR = path.join(__dirname, '..', '..', 'src', 'test', 'resources', 'golden');

// Stand-in for the original's randColor() — see header. The original is:
//   `hsl(${Math.floor(Math.random() * 360)},55%,42%)`
const SENTINEL_COLOR = 'hsl(0,55%,42%)';
function randColor() { return SENTINEL_COLOR; }

// ---- verbatim from InvMgr_V1.3.0.html lines 2271-2284 ----------------------

// Sanitize a number from untrusted input: must be finite, within [min,max], fallback to def.
function safeNum(val, min, max, def) {
  const n = Number(val);
  if (!isFinite(n) || n < min || n > max) return def;
  return n;
}

const SAFE_ID_RE = /^(i\d+_\d+|item-id-[0-9a-f-]{36})$/;
function safeId(val) {
  if (typeof val !== 'string') return null;
  return SAFE_ID_RE.test(val) ? val : null;
}

// ---- verbatim from loadState, lines 2286-2367, data portion only ----------

function validate(state) {
  if (!state || typeof state !== 'object') {
    return { error: 'Load error: invalid file.' };
  }

  const rawRoom = state.room || {};
  const loadedRoom = {
    w: safeNum(rawRoom.w, 1, 200, 12),
    l: safeNum(rawRoom.l, 1, 200, 10),
    h: safeNum(rawRoom.h, 1, 50,   8),
  };

  const loadedItemCounter      = safeNum(state.itemCounter,      0, 1e9, 0);
  const loadedDragOrderCounter = safeNum(state.dragOrderCounter, 0, 1e9, 0);
  const loadedLayerFeet        = safeNum(state.layerFeet,        0, 1e6, loadedRoom.h);

  const rawItems = Array.isArray(state.items) ? state.items : [];
  if (rawItems.length > 500) {
    return { error: 'Load error: too many items (max 500).' };
  }

  const validatedItems = [];
  for (const it of rawItems) {
    if (!it || typeof it !== 'object') continue;
    const id = safeId(it.id);
    if (!id) continue; // reject bad IDs
    // Every expression below is verbatim from the original. The KEY ORDER, however, is
    // deliberately the creation order from addItem() (line 1469-1473: ...x_px, y_px,
    // color, name, customId, baseHeight_in, planned) rather than the order loadState
    // itself uses (...x_px, y_px, baseHeight_in, name, customId, color, planned).
    //
    // That difference is real, and it is a genuine inconsistency in the original app:
    // a freshly added item and a loaded item serialize their keys in different orders,
    // so the HTML app's own output is not self-consistent. See MANUAL.md's M1 notes.
    // We normalise to the creation order here because it is the order the spec
    // documents as canonical and the order the Java writer produces, which lets the
    // Java tests compare this file as text. Reordering is safe: `validatedItems.length`
    // is fixed while the literal is evaluated, and randColor() depends on nothing.
    validatedItems.push({
      id,
      serial:        safeNum(it.serial,        1, 1e9, validatedItems.length + 1),
      dragOrder:     safeNum(it.dragOrder !== undefined ? it.dragOrder : it.serial, 0, 1e9, validatedItems.length),
      w_in:          safeNum(it.w_in,           1, 1000, 12),
      l_in:          safeNum(it.l_in,           1, 1000, 12),
      h_in:          safeNum(it.h_in,           1, 1000, 12),
      x_px:          safeNum(it.x_px,           0, 1e6,  10),
      y_px:          safeNum(it.y_px,           0, 1e6,  10),
      color:         (typeof it.color === 'string' && /^hsl\(\d+,\d+%,\d+%\)$/.test(it.color))
                       ? it.color : randColor(),
      name:          (typeof it.name === 'string' ? it.name : '').slice(0, 200),
      customId:      (typeof it.customId === 'string' ? it.customId : '').slice(0, 60),
      baseHeight_in: safeNum(it.baseHeight_in,  0, 1e6,   0),
      planned:       it.planned === true,
    });
  }

  const loadedPlanMode = typeof state.planMode === 'boolean' ? state.planMode : false;
  const loadedMetricMode = typeof state.metricMode === 'boolean' ? state.metricMode : false;
  const loadedLayerCollision = state.layerCollision === true || state.floorCollision === true;

  const rawPresets = Array.isArray(state.presets) ? state.presets.slice(0, 50) : [];
  let presets = rawPresets.map(p => {
    if (p === null) return null;
    if (!p || typeof p !== 'object' || typeof p.name !== 'string') return null;
    return {
      name: p.name.slice(0, 2),
      w_in: safeNum(p.w_in, 1, 1000, 12),
      l_in: safeNum(p.l_in, 1, 1000, 12),
      h_in: safeNum(p.h_in, 1, 1000, 12),
    };
  });
  if (!presets.length) presets = [null, null, null];

  // Emitted in buildSavePayload's key order (line 2247) so the golden file is directly
  // comparable to what a save would produce.
  return {
    room: loadedRoom,
    items: validatedItems,
    itemCounter: loadedItemCounter,
    dragOrderCounter: loadedDragOrderCounter,
    layerFeet: loadedLayerFeet,
    planMode: loadedPlanMode,
    metricMode: loadedMetricMode,
    layerCollision: loadedLayerCollision,
    presets,
  };
}

// ---- driver ---------------------------------------------------------------

function runOne(fixturePath) {
  const text = fs.readFileSync(fixturePath, 'utf8');
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (err) {
    // The original reports the parse failure rather than throwing: 'Load error: ' + msg
    return { error: 'Load error: ' + err.message };
  }
  return validate(parsed);
}

const args = process.argv.slice(2);

if (args[0] === '--numbers') {
  // Emits what JavaScript's Number() returns for each value in
  // fixtures/js-number-cases.json, so JsonTest can check Json.jsNumber against the real
  // thing instead of against my reading of the ECMAScript rules. NaN and the infinities
  // are written as strings because JSON cannot hold them.
  const cases = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, 'js-number-cases.json'), 'utf8'));
  const results = cases.map(v => {
    const n = Number(v);
    return Number.isNaN(n) ? 'NaN' : (isFinite(n) ? n : String(n));
  });
  const out = path.join(GOLDEN_DIR, 'js-number-cases.expected.json');
  fs.writeFileSync(out, JSON.stringify(results, null, 2));
  console.log(`js-number-cases.json -> ${path.basename(out)}  (${results.length} values)`);
} else if (args[0] === '--all') {
  fs.mkdirSync(GOLDEN_DIR, { recursive: true });
  // js-number-cases.json is not a save file — it is handled by --numbers.
  const fixtures = fs.readdirSync(FIXTURE_DIR)
    .filter(f => f.endsWith('.json') && f !== 'js-number-cases.json')
    .sort();
  for (const f of fixtures) {
    const result = runOne(path.join(FIXTURE_DIR, f));
    const out = path.join(GOLDEN_DIR, f.replace(/\.json$/, '.expected.json'));
    // No trailing newline: the original's buildSavePayload returns JSON.stringify()
    // output verbatim into a Blob, so a byte-comparable file must not have one either.
    fs.writeFileSync(out, JSON.stringify(result, null, 2));
    const summary = result.error
      ? `rejected: ${result.error}`
      : `${result.items.length} item(s) kept`;
    console.log(`${f} -> ${path.basename(out)}  (${summary})`);
  }
} else if (args.length === 1) {
  console.log(JSON.stringify(runOne(args[0]), null, 2));
} else {
  console.error('usage: original-loadstate.js <fixture.json> | --all');
  process.exit(2);
}
