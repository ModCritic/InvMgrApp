package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code -Dinvmgr.renderscale} means, checked without a window.
 *
 * <p>Every rule in {@link RenderScale} is arithmetic on a string and a frame time, which is the
 * reason the decision lives apart from the drawing: the drawing needs a graphics pipeline and these
 * do not.
 */
class RenderScaleTest {

    /** Parses and collects the complaints, so both halves can be asserted on. */
    private static RenderScale parse(String value, List<String> complaints) {
        return RenderScale.from(value, complaints::add);
    }

    @Test
    @DisplayName("nothing said means auto, because a flag nobody knows about helps nobody")
    void theDefaultIsAuto() {
        // Changed from off to auto on 2026-09-16, after the user ran the three settings on the
        // virtual machine this exists for: 95.5 ms a frame off, 77.5 at half, 68.8 at a quarter.
        // It shipped off for one day because the only numbers then came from a machine where the
        // saving was 8%, and the whole point of the user's original request was that a command
        // option must not be a requirement.
        List<String> complaints = new ArrayList<>();
        for (String quiet : new String[] {null, "", "   ", "auto", "AUTO", " Auto "}) {
            RenderScale scale = parse(quiet, complaints);
            assertTrue(scale.isAuto(), "\"" + quiet + "\" should be auto");
            assertEquals(RenderScale.FULL, scale.startingFactor(), 1e-9,
                    "\"" + quiet + "\" should still START at full, having measured nothing yet");
        }
        assertTrue(complaints.isEmpty(), "none of those is a mistake: " + complaints);
    }

    @Test
    @DisplayName("off means off, and stays off however badly the machine does")
    void offIsStillAvailableAndStillMeansOff() {
        List<String> complaints = new ArrayList<>();
        for (String never : new String[] {"off", "OFF", " Off "}) {
            RenderScale scale = parse(never, complaints);
            assertFalse(scale.isAuto(), "\"" + never + "\" must not be auto");
            assertEquals(RenderScale.FULL, scale.startingFactor(), 1e-9);
            // The machine that reads as ten frames a second must still be left alone.
            assertEquals(RenderScale.FULL, scale.factorFor(98.8, RenderScale.FULL), 1e-9,
                    "off has to survive a flight that would otherwise trigger auto");
            assertEquals(RenderScale.FULL, scale.factorFor(98.8, RenderScale.FULL), 1e-9,
                    "and a second one, which is what auto would act on");
        }
        assertTrue(complaints.isEmpty(), complaints.toString());
    }

    @Test
    @DisplayName("a fraction is taken literally and used from the first frame")
    void aPinnedFractionIsUsedStraightAway() {
        List<String> complaints = new ArrayList<>();
        RenderScale half = parse("0.5", complaints);

        assertFalse(half.isAuto());
        assertEquals(0.5, half.startingFactor(), 1e-9);
        // Pinned means pinned: no flight time changes it, however good or bad, and no number of
        // them either.
        assertEquals(0.5, half.factorFor(4, 0.5), 1e-9);
        assertEquals(0.5, half.factorFor(400, 0.5), 1e-9);
        assertEquals(0.5, half.factorFor(400, 0.5), 1e-9);
        assertTrue(complaints.isEmpty(), complaints.toString());
    }

    @Test
    @DisplayName("⚠ a value that means nothing complains and then draws everything")
    void nonsenseIsReportedRatherThanIgnored() {
        // Silence is indistinguishable from "there was nothing to do", which is how somebody
        // decides the setting does not exist and files the report without it. Verbose.parse makes
        // the same argument at length.
        for (String wrong : new String[] {"yes", "half", "0.5x", "--", "1/2"}) {
            List<String> complaints = new ArrayList<>();
            RenderScale scale = parse(wrong, complaints);
            assertEquals(1, complaints.size(), "\"" + wrong + "\" should complain once");
            assertTrue(scale.isAuto(), "and fall back to the default, which is auto");
            assertEquals(RenderScale.FULL, scale.startingFactor(), 1e-9);
        }
    }

    @Test
    @DisplayName("a number outside the range complains rather than being quietly clamped")
    void outOfRangeIsReported() {
        for (String outside : new String[] {"0", "0.1", "0.24", "1.5", "-0.5"}) {
            List<String> complaints = new ArrayList<>();
            RenderScale scale = parse(outside, complaints);
            assertEquals(1, complaints.size(), "\"" + outside + "\" should complain");
            assertTrue(scale.isAuto(), "and fall back to the default, which is auto");
            assertEquals(RenderScale.FULL, scale.startingFactor(), 1e-9);
        }
        // And the two ends of the range are inside it.
        List<String> complaints = new ArrayList<>();
        assertEquals(RenderScale.SMALLEST, parse("0.25", complaints).startingFactor(), 1e-9);
        assertEquals(RenderScale.FULL, parse("1.0", complaints).startingFactor(), 1e-9);
        assertTrue(complaints.isEmpty(), complaints.toString());
    }

    @Test
    @DisplayName("auto starts at full, because nothing has been measured yet")
    void autoStartsAtFull() {
        // Which means the FIRST descent of a session is always drawn at full resolution, and the
        // reduction reaches the flight after it. Deliberate: the alternative is changing
        // resolution part way through a transition, which is a visible pop in the middle of the
        // one movement this app takes most care over.
        List<String> complaints = new ArrayList<>();
        RenderScale auto = parse("auto", complaints);

        assertTrue(auto.isAuto());
        assertEquals(RenderScale.FULL, auto.startingFactor(), 1e-9);
        assertTrue(complaints.isEmpty(), complaints.toString());
    }

    @Test
    @DisplayName("the three machines that reported in all land where they should")
    void theThresholdsAgreeWithRealMachines() {
        // The numbers behind the thresholds, from 2026-09-15. Two healthy machines must not be
        // touched and the struggling one must be.
        assertEquals(RenderScale.FULL, RenderScale.forMedianMs(6.1), 1e-9, "3090 Ti at 165 Hz");
        assertEquals(RenderScale.FULL, RenderScale.forMedianMs(16.7), 1e-9, "T470p at 60 Hz");
        assertEquals(RenderScale.SMALLEST, RenderScale.forMedianMs(98.8), 1e-9, "the 4-thread VM");

        // And the middle band exists rather than jumping straight to the coarsest.
        assertEquals(0.5, RenderScale.forMedianMs(40), 1e-9);
        assertEquals(0.5, RenderScale.forMedianMs(66), 1e-9);
        assertEquals(RenderScale.SMALLEST, RenderScale.forMedianMs(67), 1e-9);
    }

    @Test
    @DisplayName("⚠ auto can only make the room coarser, never sharper again")
    void autoNeverGoesBackUp() {
        // THIS IS THE ONE THAT STOPS IT FLAPPING. Scaling makes the next flight faster; a faster
        // flight reads as healthy; healthy would switch scaling off; the room is slow again. The
        // picture would change sharpness every time you entered or left.
        RenderScale auto = RenderScale.from("auto", ignored -> { });

        auto.factorFor(98.8, RenderScale.FULL);
        double afterASlowDescent = auto.factorFor(98.8, RenderScale.FULL);
        assertEquals(RenderScale.SMALLEST, afterASlowDescent, 1e-9);

        // The ascent that follows is now measured WITH the reduction on, so it looks healthy.
        assertEquals(RenderScale.SMALLEST, auto.factorFor(12, afterASlowDescent), 1e-9,
                "a healthy flight must not undo it");
        assertEquals(RenderScale.SMALLEST, auto.factorFor(6.1, afterASlowDescent), 1e-9);
    }

    @Test
    @DisplayName("auto steps down through the middle band rather than only at the bottom")
    void autoCanStopAtHalf() {
        RenderScale auto = RenderScale.from("auto", ignored -> { });

        auto.factorFor(40, RenderScale.FULL);
        double afterAMiddlingFlight = auto.factorFor(40, RenderScale.FULL);
        assertEquals(0.5, afterAMiddlingFlight, 1e-9);
        // And it can still go further if things get worse from there, on the same two-in-a-row.
        auto.factorFor(98.8, afterAMiddlingFlight);
        assertEquals(RenderScale.SMALLEST, auto.factorFor(98.8, afterAMiddlingFlight), 1e-9);
    }

    @Test
    @DisplayName("⚠ one slow flight is not enough, because the decision never comes back")
    void oneStallDoesNotCostYouTheRestOfTheSession() {
        // THE TEST THE SUITE ITSELF BOUGHT. Running the whole suite with auto as the default
        // showed this machine reporting flights at 34 to 46 ms, which is correctly slow for a
        // 2560 x 1440 window on a software rasterizer, and would have been acted on from a single
        // reading. A person whose machine stalls once, because something else wanted the
        // processor for two seconds, must not be left with a soft room until they restart.
        RenderScale auto = RenderScale.from("auto", ignored -> { });

        assertEquals(RenderScale.FULL, auto.factorFor(98.8, RenderScale.FULL), 1e-9,
                "one slow flight changes nothing");
        assertEquals(RenderScale.FULL, auto.factorFor(6.1, RenderScale.FULL), 1e-9,
                "and a healthy one after it certainly does not");
        // That healthy flight broke the run, so the next slow one is a first again.
        assertEquals(RenderScale.FULL, auto.factorFor(98.8, RenderScale.FULL), 1e-9,
                "the count restarted, so this is the first of a new run, not the second of the old");
        assertEquals(RenderScale.SMALLEST, auto.factorFor(98.8, RenderScale.FULL), 1e-9,
                "two in a row is what it takes");
    }

    @Test
    @DisplayName("a machine that is genuinely slow reaches the reduction on its second flight")
    void aGenuinelySlowMachineGetsThereQuickly() {
        // A descent and the ascent after it, which is one visit to the 3D view.
        RenderScale auto = RenderScale.from("auto", ignored -> { });
        assertEquals(RenderScale.FULL, auto.factorFor(95.5, RenderScale.FULL), 1e-9, "descent");
        assertEquals(RenderScale.SMALLEST, auto.factorFor(117.1, RenderScale.FULL), 1e-9,
                "ascent, and the user's own numbers from the VM");
    }

    @Test
    @DisplayName("the startup report says what is in use, in words where there are words")
    void describeReadsAsSomethingAPersonWrote() {
        assertEquals("auto", RenderScale.from(null, ignored -> { }).describe());
        assertEquals("off", RenderScale.from("off", ignored -> { }).describe());
        assertEquals("auto", RenderScale.from("auto", ignored -> { }).describe());
        assertEquals("0.5000", RenderScale.from("0.5", ignored -> { }).describe());
    }
}
