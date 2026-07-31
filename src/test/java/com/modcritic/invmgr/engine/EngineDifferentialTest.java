package com.modcritic.invmgr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.persist.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the collision, stacking, placement and layering code against results produced by the
 * original app's own JavaScript.
 *
 * <p>These algorithms are the ones where a subtle error looks almost right: a stacking pass
 * with the wrong sort order still produces plausible heights, and a slide that uses a stale
 * coordinate works most of the time and occasionally lets a box through a corner. Tests
 * written from my reading of the original would bake my misreadings in as expectations.
 *
 * <p>So the expectations come from elsewhere: {@code tools/golden/original-engine.js} holds a
 * verbatim copy of the shipped algorithms, and its output over 200 scenarios — 8 hand-picked
 * edge cases plus 192 randomly generated ones, half with Layer Collision on — is committed as
 * the golden file. <b>If Java and the golden disagree, Java is wrong.</b>
 *
 * <p>Regenerate with {@code node tools/golden/original-engine.js --gen 200 && … --run}.
 */
class EngineDifferentialTest {

    /**
     * Positions and heights are the result of identical arithmetic on both sides, so they
     * should match exactly. The tolerance is here to report a real disagreement rather than
     * to paper over one — anything above this is a genuine difference in behaviour.
     */
    private static final double TOLERANCE = 1e-9;

    @Test
    @DisplayName("all 200 scenarios agree with the original app's own algorithms")
    void matchesTheOriginalEngine() throws IOException {
        List<?> scenarios = (List<?>) Json.parse(resource("fixtures/engine-scenarios.json"));
        List<?> expected = (List<?>) Json.parse(resource("golden/engine-scenarios.expected.json"));
        assertEquals(scenarios.size(), expected.size(), "fixture and golden are out of sync");
        assertTrue(scenarios.size() >= 200, "expected at least 200 scenarios");

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            Map<String, Object> scenario = asMap(scenarios.get(i));
            Map<String, Object> want = asMap(expected.get(i));
            try {
                check(i, scenario, want, mismatches);
            } catch (RuntimeException e) {
                mismatches.add("scenario " + i + " (" + scenario.get("note") + ") threw "
                        + e);
            }
        }

        // All mismatches are reported together rather than failing on the first, so a
        // systematic error shows its shape instead of arriving one scenario at a time.
        assertTrue(mismatches.isEmpty(),
                () -> mismatches.size() + " of " + scenarios.size()
                        + " scenarios disagree with the original:\n" + String.join("\n",
                        mismatches.subList(0, Math.min(20, mismatches.size()))));
    }

    private void check(int index, Map<String, Object> scenario, Map<String, Object> want,
            List<String> mismatches) {
        AppState state = buildState(scenario);
        Map<String, Object> op = asMap(scenario.get("op"));
        String kind = (String) op.get("op");
        String label = "scenario " + index + " [" + kind + "] (" + scenario.get("note") + ")";

        switch (kind) {
            case "clampItem": {
                Item item = state.items.get((int) num(op.get("itemIndex")));
                Collision.Point got = Collision.clampItem(state, item,
                        num(op.get("nx")), num(op.get("ny")));
                compare(label + " x", num(want.get("x")), got.x(), mismatches);
                compare(label + " y", num(want.get("y")), got.y(), mismatches);
                break;
            }
            case "recompute": {
                Stacking.recomputeAllBaseHeights(state);
                compareHeights(label, want, state, mismatches);
                break;
            }
            case "settle": {
                Stacking.settleAllBaseHeights(state);
                compareHeights(label, want, state, mismatches);
                break;
            }
            case "findOpenSpot": {
                double w = num(op.get("w_in"));
                double l = num(op.get("l_in"));
                Collision.Point got = op.containsKey("x")
                        ? Placement.findOpenSpot(state, w, l, num(op.get("x")), num(op.get("y")))
                        : Placement.findOpenSpot(state, w, l);
                compare(label + " x", num(want.get("x")), got.x(), mismatches);
                compare(label + " y", num(want.get("y")), got.y(), mismatches);
                break;
            }
            case "layers": {
                int selectedIndex = (int) num(scenario.get("selected"));
                Item selected = selectedIndex >= 0 ? state.items.get(selectedIndex) : null;
                List<?> wantZ = (List<?>) want.get("z");
                List<?> wantVisible = (List<?>) want.get("visible");
                List<?> wantDim = (List<?>) want.get("dim");

                // Paint order and dimming are DIVERGENCE D-5, so the golden values cannot simply
                // be asserted: the golden is the original's behaviour by construction, and D-5
                // exists because that behaviour is wrong. Deleting the check would be the easy
                // way out and would drop 200 scenarios of coverage, so it is split instead:
                //
                //  - where the two rules MUST agree, still compare against the golden. D-5 only
                //    changes what happens when two items sit at different heights, so a scenario
                //    whose items all share one base height must match the original exactly. That
                //    is the ordinary flat-room case and by far the most common one.
                //  - where they deliberately differ, assert the INVARIANT D-5 buys instead: no
                //    item may paint above an item it is physically underneath. That is a property,
                //    not a restatement of the rule, and it is the exact thing the user reported.
                boolean heightsAllTie = state.items.stream()
                        .mapToDouble(item -> item.baseHeight_in).distinct().count() <= 1;

                for (int i = 0; i < state.items.size(); i++) {
                    Item item = state.items.get(i);
                    compareBoolean(label + " visible[" + i + "]", (Boolean) wantVisible.get(i),
                            Layers.isVisible(state, item), mismatches);
                    if (heightsAllTie) {
                        compareBoolean(label + " dim[" + i + "]", (Boolean) wantDim.get(i),
                                Layers.shouldDim(item, selected), mismatches);
                    }
                }

                if (heightsAllTie) {
                    compareOrdering(label, wantZ, state, mismatches);
                }
                assertNothingPaintsAboveWhatItIsUnder(label, state, mismatches);
                break;
            }
            default:
                mismatches.add(label + ": unknown op in fixture");
        }
    }

    private void compareHeights(String label, Map<String, Object> want, AppState state,
            List<String> mismatches) {
        List<?> wantHeights = (List<?>) want.get("baseHeights");
        for (int i = 0; i < state.items.size(); i++) {
            compare(label + " baseHeight[" + i + "]", num(wantHeights.get(i)),
                    state.items.get(i).baseHeight_in, mismatches);
        }
    }

    /**
     * Checks our front-to-back order agrees with the original's, by order rather than by value.
     *
     * <p>The original's z-index is a number it computes from drag order or from height, so its
     * absolute values mean nothing once D-5 changes the rule. What has to survive is the
     * <em>sequence</em>: which box ends up in front of which.
     */
    private void compareOrdering(String label, List<?> wantZ, AppState state,
            List<String> mismatches) {
        List<Integer> byGolden = new ArrayList<>();
        List<Integer> byOurs = new ArrayList<>();
        for (int i = 0; i < state.items.size(); i++) {
            byGolden.add(i);
            byOurs.add(i);
        }
        byGolden.sort((a, b) -> Double.compare(num(wantZ.get(a)), num(wantZ.get(b))));
        byOurs.sort((a, b) -> Layers.comparePaint(state.items.get(a), state.items.get(b)));

        if (!byGolden.equals(byOurs)) {
            mismatches.add(label + " paint order: original " + byGolden + " but got " + byOurs);
        }
    }

    /**
     * The invariant divergence D-5 exists to guarantee, checked over every scenario.
     *
     * <p>If two boxes overlap on the floor and one is physically lower than the other, the lower
     * one must be drawn behind. The original breaks this whenever Layer Collision is off, which
     * is the bug the user hit: drag a box at base 0 in under a box at base 12 in and the box you
     * just moved paints over the one above it.
     *
     * <p>Deliberately expressed as a property, not by recomputing the rule — a test that
     * reimplements the thing it is testing passes no matter how wrong both copies are.
     */
    private void assertNothingPaintsAboveWhatItIsUnder(String label, AppState state,
            List<String> mismatches) {
        for (Item lower : state.items) {
            for (Item higher : state.items) {
                if (lower == higher || lower.planned || higher.planned) {
                    continue;
                }
                if (lower.baseHeight_in >= higher.baseHeight_in) {
                    continue;
                }
                if (!Rect.of(lower).overlaps(Rect.of(higher))) {
                    continue;   // not stacked over each other, so order cannot look wrong
                }
                if (Layers.comparePaint(lower, higher) >= 0) {
                    mismatches.add(label + " paint order: " + lower.id + " at base "
                            + lower.baseHeight_in + " paints at or above " + higher.id
                            + " at base " + higher.baseHeight_in + ", which it is underneath");
                }
            }
        }
    }

    private void compare(String label, double want, double got, List<String> mismatches) {
        if (Math.abs(want - got) > TOLERANCE) {
            mismatches.add(label + ": original=" + want + " java=" + got);
        }
    }

    private void compareBoolean(String label, boolean want, boolean got, List<String> mismatches) {
        if (want != got) {
            mismatches.add(label + ": original=" + want + " java=" + got);
        }
    }

    /** Rebuilds the scenario's state. Each scenario gets a fresh one, because ops mutate it. */
    private static AppState buildState(Map<String, Object> scenario) {
        AppState state = new AppState();
        Map<String, Object> room = asMap(scenario.get("room"));
        state.room = new Room(num(room.get("w")), num(room.get("l")), num(room.get("h")));
        state.layerCollision = Boolean.TRUE.equals(scenario.get("layerCollision"));
        state.layerFeet = num(scenario.get("layerFeet"));

        state.items = new ArrayList<>();
        for (Object raw : (List<?>) scenario.get("items")) {
            Map<String, Object> source = asMap(raw);
            Item item = new Item();
            item.id = (String) source.get("id");
            item.dragOrder = num(source.get("dragOrder"));
            item.w_in = num(source.get("w_in"));
            item.l_in = num(source.get("l_in"));
            item.h_in = num(source.get("h_in"));
            item.x_px = num(source.get("x_px"));
            item.y_px = num(source.get("y_px"));
            item.baseHeight_in = num(source.get("baseHeight_in"));
            item.planned = Boolean.TRUE.equals(source.get("planned"));
            item.name = "";
            item.customId = "";
            item.color = "hsl(0,55%,42%)";
            state.items.add(item);
        }
        return state;
    }

    private static double num(Object value) {
        return ((Double) value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in =
                EngineDifferentialTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("test resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
