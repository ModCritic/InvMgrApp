package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.persist.Fixtures;
import com.modcritic.invmgr.persist.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the per-face shading against results produced by the original app's own JavaScript.
 *
 * <p>This is the one piece of M5's arithmetic where a wrong answer looks entirely plausible. The
 * OD-1 spike shaded with {@code Color.hsb}, which multiplies HSB brightness where the original
 * multiplies HSL lightness: two different quantities that both produce a believable box. A
 * screenshot cannot tell them apart, and neither can a test written from my reading of the
 * original.
 *
 * <p>So the expectations come from {@code tools/golden/original-3d.js}, which holds a verbatim
 * copy of the shipped {@code itemShades} and runs it over 497 colors: every hue in the app's own
 * {@code hsl(H,55%,42%)} family, every lightness from 0 to 100, a saturation sweep, and the
 * boundary cases by name: lightnesses that clamp at 4 and at 92, one that rounds to exactly the
 * floor, and eight strings the original's regex rejects. <b>If Java and the golden disagree, Java
 * is wrong.</b>
 *
 * <p>Comparison is exact. There is no tolerance, because the original rounds to whole percentages
 * and so does {@link Shades}; a tolerance here would be the thing hiding the bug.
 *
 * <p>Regenerate with {@code node tools/golden/original-3d.js --run}.
 */
class ShadesTest {

    /** The shape {@code itemShades} emits, e.g. {@code hsl(207,55%,51%)}. */
    private static final Pattern GOLDEN_COLOR =
            Pattern.compile("^hsl\\((\\d+),(\\d+)%,(\\d+)%\\)$");

    @Test
    @DisplayName("all 497 colors agree with the original app's own itemShades")
    void matchesTheOriginalShading() throws IOException {
        List<?> cases = (List<?>) Json.parse(Fixtures.read("golden/3d-shades.expected.json"));
        assertTrue(cases.size() >= 497, "expected at least 497 shade cases, got " + cases.size());

        List<String> mismatches = new ArrayList<>();
        for (Object entry : cases) {
            Map<String, Object> row = asMap(entry);
            String color = (String) row.get("color");
            Map<String, Object> want = asMap(row.get("shades"));

            Shades got = Shades.of(color);
            compare(color, "top", want, got.hue, got.saturation, got.top, mismatches);
            compare(color, "ns", want, got.hue, got.saturation, got.northSouth, mismatches);
            compare(color, "ew", want, got.hue, got.saturation, got.eastWest, mismatches);
            compare(color, "bottom", want, got.hue, got.saturation, got.bottom, mismatches);
            compare(color, "edge", want, got.hue, got.saturation, got.edge, mismatches);
        }

        // Reported together rather than failing on the first, so a systematic error (a wrong
        // color space, say) shows its shape instead of arriving one color at a time.
        assertTrue(mismatches.isEmpty(),
                () -> mismatches.size() + " of " + cases.size() * 5
                        + " face shades disagree with the original:\n"
                        + String.join("\n", mismatches.subList(0, Math.min(20, mismatches.size()))));
    }

    @Test
    @DisplayName("a color string the original rejects falls back to red, not to gray")
    void aRejectedColorFallsBackToRed() {
        // Tokens.parseHsl answers Color.GRAY for a string it cannot read. itemShades answers
        // hue 0 at the app's standard 55%/42%, which is red, a different fallback for a
        // different job, and the 3D view must use the original's. A gray box would look like a
        // deliberate color; a red one looks like the bug it is.
        Shades fallback = Shades.of("not a color");
        assertEquals(0, fallback.hue);
        assertEquals(55, fallback.saturation);
        assertEquals(42, fallback.northSouth);

        // And the strictness is the point: these three are legal CSS that the original's regex
        // turns down, so the port has to turn them down too or the differential drifts.
        assertEquals(0, Shades.of("hsl( 200,55%,42%)").hue, "leading space must be rejected");
        assertEquals(0, Shades.of("hsl(200,55.0%,42%)").hue, "a decimal must be rejected");
        assertEquals(200, Shades.of("hsl(200, 55%, 42%)").hue, "spaces after commas are legal");
    }

    @Test
    @DisplayName("the multipliers are the five the spec names")
    void theMultipliersAreTheSpecs() {
        // Pinned as constants as well as through the differential, because the golden proves the
        // outputs agree while this says which number produced them. A sweep that swapped two
        // multipliers of equal effect would slip past a test that only checked results.
        assertEquals(1.22, Shades.TOP);
        assertEquals(1.00, Shades.NORTH_SOUTH);
        assertEquals(0.80, Shades.EAST_WEST);
        assertEquals(0.55, Shades.BOTTOM);
        assertEquals(0.45, Shades.EDGE);
        assertEquals(4, Shades.MIN_LIGHTNESS);
        assertEquals(92, Shades.MAX_LIGHTNESS);
    }

    @Test
    @DisplayName("the faces stay in order darkest to lightest for every color the app makes")
    void theFacesKeepTheirOrder() {
        // The differential pins the numbers; this pins the *relationship*, which is what the look
        // actually depends on. It holds for every hue because saturation and lightness are fixed
        // at item creation (hsl(random 0-359, 55%, 42%)), so 42 is the only lightness the app
        // itself ever produces. Bounded on both sides: the top face must be lighter than the
        // sides, and no face may reach the clamps, or the shading would flatten out.
        for (int hue = 0; hue < 360; hue++) {
            Shades s = Shades.of("hsl(" + hue + ",55%,42%)");
            assertTrue(s.edge < s.bottom, "edge must be darker than the bottom at hue " + hue);
            assertTrue(s.bottom < s.eastWest, "bottom must be darkest of the faces at " + hue);
            assertTrue(s.eastWest < s.northSouth, "east/west darker than north/south at " + hue);
            assertTrue(s.northSouth < s.top, "top must be the lightest face at hue " + hue);
            assertTrue(s.edge > Shades.MIN_LIGHTNESS,
                    "no face of a normal item should reach the floor, at hue " + hue);
            assertTrue(s.top < Shades.MAX_LIGHTNESS,
                    "no face of a normal item should reach the ceiling, at hue " + hue);
        }
    }

    @Test
    @DisplayName("a black item's faces all collapse onto the lightness floor")
    void aBlackItemClampsUp() {
        // The one place the clamp does real work. Every multiplier applied to 0 gives 0, which
        // is below the floor, so all five faces come back at exactly 4 and the box loses its
        // shading entirely. That is the original's behavior and the reason the floor is 4 and
        // not 0: at 0 the edge line would be invisible against the faces.
        Shades black = Shades.of("hsl(0,0%,0%)");
        assertEquals(Shades.MIN_LIGHTNESS, black.top);
        assertEquals(Shades.MIN_LIGHTNESS, black.edge);
    }

    private void compare(String color, String face, Map<String, Object> want,
            int gotHue, int gotSaturation, int gotLightness, List<String> mismatches) {
        Matcher m = GOLDEN_COLOR.matcher((String) want.get(face));
        assertTrue(m.matches(), "golden color is not the expected shape: " + want.get(face));
        int wantHue = Integer.parseInt(m.group(1));
        int wantSaturation = Integer.parseInt(m.group(2));
        int wantLightness = Integer.parseInt(m.group(3));

        if (wantHue != gotHue || wantSaturation != gotSaturation || wantLightness != gotLightness) {
            mismatches.add(color + " " + face + ": want hsl(" + wantHue + "," + wantSaturation
                    + "%," + wantLightness + "%) got hsl(" + gotHue + "," + gotSaturation + "%,"
                    + gotLightness + "%)");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
