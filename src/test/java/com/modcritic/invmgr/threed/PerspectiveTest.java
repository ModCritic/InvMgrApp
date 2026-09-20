package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.persist.Fixtures;
import com.modcritic.invmgr.persist.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the camera's lens and the height it starts the descent from against the original's own
 * arithmetic.
 *
 * <p>This one is worth a differential rather than hand-written expectations because the formula
 * has four chances to be subtly wrong and every one of them still produces a view of a room: the
 * 6% margin, which dimension wins the {@code max}, the ceiling clearance that only matters for a
 * tall narrow room, and the 300 px floor under the perspective distance. A wrong answer here is
 * an entry animation that starts too close or too far, which looks like a taste decision rather
 * than a bug.
 *
 * <p>Expectations come from {@code tools/golden/original-3d.js}, over 49 cases: seven rooms
 * (including the legal extremes and a 4×4×20 closet where the ceiling clearance decides) crossed
 * with seven viewports (including the reference 2560×1440, the reference phone in portrait, an
 * odd height where {@code normalP}'s rounding actually does something, and two short enough to
 * sit under the 300 px floor).
 */
class PerspectiveTest {

    /**
     * Both sides do the same arithmetic on the same doubles, so they should agree to the last
     * bit. This is here to report a real disagreement, not to absorb one.
     */
    private static final double TOLERANCE = 1e-12;

    @Test
    @DisplayName("all 49 overhead heights agree with the original app's own overhead()")
    void matchesTheOriginalOverhead() throws IOException {
        List<?> cases = (List<?>) Json.parse(Fixtures.read("golden/3d-overhead.expected.json"));
        assertTrue(cases.size() >= 49, "expected at least 49 overhead cases");

        List<String> mismatches = new ArrayList<>();
        for (Object entry : cases) {
            Map<String, Object> row = asMap(entry);
            Map<String, Object> roomJson = asMap(row.get("room"));
            Room room = new Room();
            room.w = number(roomJson.get("w"));
            room.l = number(roomJson.get("l"));
            room.h = number(roomJson.get("h"));
            double vw = number(row.get("vw"));
            double vh = number(row.get("vh"));

            double wantP = number(row.get("normalP"));
            double gotP = Perspective.perspectiveDistancePx(vh);
            if (Math.abs(wantP - gotP) > TOLERANCE) {
                mismatches.add(vw + "x" + vh + " perspective: want " + wantP + " got " + gotP);
            }

            double wantY = number(row.get("y"));
            double gotY = Perspective.overheadHeightFt(room, vw, vh);
            if (Math.abs(wantY - gotY) > TOLERANCE) {
                mismatches.add("room " + room.w + "x" + room.l + "x" + room.h + " at "
                        + vw + "x" + vh + ": want y " + wantY + " got " + gotY);
            }
        }

        assertTrue(mismatches.isEmpty(),
                () -> mismatches.size() + " of " + cases.size() * 2
                        + " values disagree with the original:\n"
                        + String.join("\n", mismatches.subList(0, Math.min(20, mismatches.size()))));
    }

    @Test
    @DisplayName("the field of view is 2·atan(2/3), not the 67 the spike rounded it to")
    void theFieldOfViewIsTheOriginalsExactly() {
        // The original never wrote an angle down; it set a CSS perspective distance of
        // 0.75 x viewport height. That makes tan(fov/2) exactly 2/3 for any window taller than
        // 400 px, so the vertical field of view is 2*atan(2/3) = 67.3801...°, not 67.
        double exact = Math.toDegrees(2 * Math.atan(2.0 / 3.0));
        assertEquals(exact, Perspective.verticalFovDegrees(1440), 1e-9);
        assertEquals(exact, Perspective.verticalFovDegrees(1080), 1e-9);
        assertEquals(exact, Perspective.verticalFovDegrees(800), 1e-9);

        // Bounded on both sides, so "close enough to 67" cannot pass: it must be above 67.3 and
        // below 67.4, which excludes both the spec's rounded 67 and any accidental 68.
        assertTrue(exact > 67.3 && exact < 67.4, "expected 67.38°, got " + exact);
        assertTrue(Math.abs(Perspective.verticalFovDegrees(1440) - 67.0) > 0.3,
                "a flat 67 would be wrong by more than a third of a degree");
    }

    @Test
    @DisplayName("a window under 400 px tall gets a narrower view, as the original's floor gives it")
    void theShortWindowFloorNarrowsTheView() {
        // normalP stops shrinking at 300 px, so a shorter window sees LESS, not more. No real
        // window is this short; the branch is ported because porting most of an expression and
        // explaining the rest is worse than porting all of it.
        double tall = Perspective.verticalFovDegrees(1440);
        double short_ = Perspective.verticalFovDegrees(200);
        assertTrue(short_ < tall,
                "a 200 px window should see less than a 1440 px one, got " + short_ + " vs " + tall);
        assertEquals(300, Perspective.perspectiveDistancePx(200));
        assertEquals(300, Perspective.perspectiveDistancePx(400));
        assertEquals(303, Perspective.perspectiveDistancePx(404));
    }

    @Test
    @DisplayName("a tall closet is framed by its ceiling, not by its floor")
    void aTallRoomIsHeldOffItsOwnCeiling() {
        // A 4x4 ft floor needs almost no distance to fit on screen, so without the clearance the
        // camera would start below the 20 ft ceiling, inside the room's own walls, looking at
        // the inside of a wall rather than down at the floor.
        Room closet = new Room();
        closet.w = 4;
        closet.l = 4;
        closet.h = 20;
        double y = Perspective.overheadHeightFt(closet, 2560, 1440);
        assertEquals(closet.h + Perspective.OVERHEAD_CEILING_CLEARANCE_FT, y, TOLERANCE);
        assertTrue(y > closet.h, "the camera must end up above the ceiling, not inside the room");
    }

    @Test
    @DisplayName("whichever room dimension needs more distance is the one that decides")
    void theLongerDimensionWins() {
        // Same floor area, rotated. A room that is long north-south is framed by its length; the
        // same room turned sideways is framed by its width. Both must clear their own bound, and
        // on a wide screen the north-south one has to back off further.
        Room longRoom = new Room();
        longRoom.w = 8;
        longRoom.l = 40;
        longRoom.h = 8;
        Room wideRoom = new Room();
        wideRoom.w = 40;
        wideRoom.l = 8;
        wideRoom.h = 8;

        double alongZ = Perspective.overheadHeightFt(longRoom, 2560, 1440);
        double alongX = Perspective.overheadHeightFt(wideRoom, 2560, 1440);
        assertTrue(alongZ > alongX,
                "on a 16:9 screen the long-and-narrow room needs more height: "
                        + alongZ + " vs " + alongX);
        // And bounded below as well as above, so a mutation that drops the max() entirely and
        // always uses length cannot pass by accident.
        assertTrue(alongX > wideRoom.h + Perspective.OVERHEAD_CEILING_CLEARANCE_FT,
                "the wide room should be framed by its width, not by the ceiling clearance");
    }

    @Test
    @DisplayName("the 6% margin is applied, so the room does not touch the edge of the screen")
    void theRoomIsGivenBreathingRoom() {
        Room room = new Room();
        room.w = 20;
        room.l = 20;
        room.h = 8;
        double y = Perspective.overheadHeightFt(room, 2560, 1440);
        double bare = 20 * Perspective.perspectiveDistancePx(1440) / 1440;
        assertEquals(bare * Perspective.OVERHEAD_MARGIN, y, TOLERANCE);
        // Bounded both ways: the margin must be there, and it must not be a bigger one.
        assertTrue(y > bare, "without the margin the room would touch the screen edge");
        assertTrue(y < bare * 1.1, "the margin is 6%, not something larger");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
