package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The line a flight prints about itself, checked against frames handed in by hand.
 *
 * <p>Fed nanosecond stamps directly rather than run against a real transition, which is the only
 * way to assert what it says about a 150 ms hitch without arranging for one.
 */
class FlightClockTest {

    /** Turns a list of frame lengths in milliseconds into the clock stamps that produce them. */
    private static FlightClock flownWith(double... frameMs) {
        FlightClock clock = new FlightClock();
        long now = 1_000_000_000L;
        clock.frame(now);
        for (double ms : frameMs) {
            now += Math.round(ms * 1_000_000);
            clock.frame(now);
        }
        return clock;
    }

    @Test
    @DisplayName("the first stamp is not a frame, because nothing came before it")
    void theFirstStampIsNotAGap() {
        // A gap needs two stamps. Counting the first as a frame of its own would put one
        // meaningless number into every median, and on a short flight that is a real distortion.
        FlightClock clock = new FlightClock();
        clock.frame(1_000_000_000L);
        assertEquals(0, clock.frames());
        assertTrue(clock.line("descent").contains("no frames"),
                "and it should say so rather than dividing by zero: " + clock.line("descent"));
    }

    @Test
    @DisplayName("a smooth flight reports the rate its median implies")
    void aSmoothFlight() {
        FlightClock clock = flownWith(16, 16, 16, 16, 16);
        String line = clock.line("descent");

        assertEquals(5, clock.frames());
        assertEquals(16, clock.medianMs(), 0.05);
        assertTrue(line.contains("about 63 a second"), line);
    }

    @Test
    @DisplayName("⚠ a machine sitting exactly on its vsync reads as healthy, not as half broken")
    void vsyncIsNotAFailure() {
        // THIS IS THE TEST THE USER'S HARDWARE BOUGHT. The first version of this class counted
        // frames longer than 16.7 ms, and a ThinkPad T470p on a 60 Hz screen came back with a
        // median of 16.7 and "51% over budget" while running perfectly: a machine on its vsync
        // lands half its frames a hair above the interval and half a hair below, so that count
        // reads about 50% whatever the hardware does. The resolution scaling this feeds would
        // have made a healthy laptop blurry.
        //
        // The real numbers from all three machines, each one line:
        //   3090 Ti on 165 Hz    median  6.1 ms -> 164 a second, and its screen does 165
        //   T470p on 60 Hz       median 16.7 ms ->  60 a second, and its screen does 60
        //   4-thread VM          median 98.8 ms ->  10 a second, and no screen does 10
        assertTrue(flownWith(16.7, 16.6, 16.7, 16.6, 16.7).line("descent")
                .contains("about 60 a second"), "a 60 Hz machine should read as 60");
        assertTrue(flownWith(6.1, 6.1, 6.0, 6.2, 6.1).line("descent")
                .contains("about 164 a second"), "a 165 Hz machine should read as its refresh");
        assertTrue(flownWith(98.8, 98.8, 99, 98, 98.8).line("descent")
                .contains("about 10 a second"), "and a struggling one should read as struggling");
    }

    @Test
    @DisplayName("no count of frames over a fixed budget, because that is what was wrong")
    void thereIsNoFixedBudgetAnyMore() {
        // Pinned so the obvious-looking metric cannot come back. It is obvious-looking: it took
        // three machines reporting in to show it was measuring the screen rather than the app.
        String line = flownWith(16.7, 16.6, 16.7).line("descent");
        assertFalse(line.contains("budget"), line);
        assertFalse(line.contains("%"), "a percentage here would be that count returning: " + line);
    }

    @Test
    @DisplayName("the median ignores one enormous frame, and the line still names it")
    void theHitchIsNamedButDoesNotMoveTheMiddle() {
        // The real shape of a descent: one frame paying for every texture at once, then a smooth
        // flight. A mean would report this as a disaster; the median reports it as it felt, and
        // the worst-frame half of the line is what says the hitch was there at all.
        FlightClock clock = flownWith(150, 16, 16, 16, 16, 16, 16);
        String line = clock.line("descent");

        assertEquals(16, clock.medianMs(), 0.05, "one hitch must not drag the middle");
        assertTrue(line.contains("worst 150.0 ms at frame 1 of 7"),
                "the hitch and where it landed should both be in the line: " + line);
    }

    @Test
    @DisplayName("where the worst frame landed is in the line, because it changes the answer")
    void whereTheWorstFrameLandedIsReported() {
        // A hitch at the start is the room reaching the graphics card. One in the middle is
        // something else. Telling them apart without asking is the point of printing the position.
        String atTheStart = flownWith(150, 16, 16, 16, 16).line("descent");
        String halfWay = flownWith(16, 16, 150, 16, 16).line("descent");

        assertTrue(atTheStart.contains("at frame 1 of 5"), atTheStart);
        assertTrue(halfWay.contains("at frame 3 of 5"), halfWay);
    }

    @Test
    @DisplayName("a flight longer than the clock has room for stops recording rather than growing")
    void itWillNotAllocateWithoutLimit() {
        // A diagnostic that can grow without limit is a bug waiting for a slow machine, which is
        // exactly the sort of machine this exists to measure.
        FlightClock clock = new FlightClock();
        long now = 0;
        for (int i = 0; i < 5000; i++) {
            clock.frame(now);
            now += 1_000_000;
        }
        assertTrue(clock.frames() <= 1000, "recorded " + clock.frames());
        assertTrue(clock.frames() > 0, "and it must still have recorded something");
    }

    @Test
    @DisplayName("the line names the flight it is talking about")
    void theLineSaysWhichFlight() {
        // Both flights print, one after the other, so a log with only numbers in it could not be
        // read. The name is passed in rather than guessed from the duration, which would break
        // silently the day the two transitions are the same length.
        assertTrue(flownWith(16, 16).line("descent").startsWith("descent:"));
        assertTrue(flownWith(16, 16).line("ascent").startsWith("ascent:"));
    }
}
