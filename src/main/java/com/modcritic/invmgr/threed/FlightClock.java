package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.Verbose;
import java.util.Arrays;

/**
 * Times the frames of one flight into or out of the 3D view, and says how it went.
 *
 * <p><b>Why the app measures itself.</b> M6.7 item 3 ran out of road from this end: every number in
 * it came from a machine with no graphics card, and the report was about a virtual machine running
 * Mesa's software OpenGL. Asking that machine for its own numbers is the only way past that, and a
 * bug report that arrives with a line of these in it is worth several rounds of asking.
 *
 * <p>Printed under {@code -Dinvmgr.verbose=3d}; see {@link Verbose}.
 *
 * <h2>What it counts, and why those things</h2>
 *
 * <p><b>The median frame, not the mean.</b> The first frame of a descent carries every texture
 * reaching the graphics card at once and has been measured at 150 ms against a median of 16; a mean
 * would be dragged around by it and would say the flight was worse than it felt.
 *
 * <p><b>Frames a second, worked out from that median, and ⚠ NOT a count of frames over a fixed
 * budget.</b> The first version of this counted frames longer than 16.7 ms, which looks obviously
 * right and is a false-alarm generator. Three machines reported in on 2026-09-15 and the middle one
 * settled it: a ThinkPad T470p on a 60 Hz screen came back with a median of 16.7 ms, <b>which is
 * its refresh interval exactly</b>, and 51% of frames "over budget". It was running perfectly. A
 * machine sitting on its vsync has half its frames land a hair above the interval and half a hair
 * below, so the count says 50% whatever the hardware does, and the adaptive resolution this feeds
 * would have made a healthy laptop blurry.
 *
 * <p>The frames-a-second figure reads correctly on all three: 164 on a 3090 Ti against a 165 Hz
 * screen, 60 on the T470p against 60 Hz, and 10 on a four-thread virtual machine that is genuinely
 * struggling. <b>A machine keeping up reports its refresh rate; a machine that is not reports
 * something far below any refresh rate there is.</b>
 *
 * <p><b>The worst frame and where it landed.</b> A hitch at the start is the room reaching the card;
 * one in the middle is something else entirely, and the two call for completely different answers.
 * Knowing which without asking saved a wrong fix once already.
 *
 * <h2>It runs whether or not anyone is listening</h2>
 *
 * <p>One subtraction and one array write per frame, on a flight that lasts about two seconds. That
 * is deliberate rather than careless: the adaptive resolution this is meant to inform has to read
 * these numbers at run time on every machine, not only on one being debugged, so measuring only
 * under a flag would mean writing it twice. {@link #report} is the part that costs nothing when the
 * topic is off.
 */
public final class FlightClock {

    /**
     * Room for every frame of the longest flight, plus slack.
     *
     * <p>The descent is 2,230 ms, so at 60 a second that is 134 frames; at 240 on a fast screen it
     * is 535. A thousand covers both with room to spare, and a flight that somehow ran longer stops
     * recording rather than growing: this is a diagnostic, and a diagnostic that can allocate
     * without limit is a bug waiting for a slow machine.
     */
    private static final int ROOM = 1000;

    private final long[] gapsNs = new long[ROOM];
    private int count;
    private long previousNs;

    /** Call once per frame, with the {@code AnimationTimer}'s own clock. */
    public void frame(long nowNs) {
        if (previousNs != 0 && count < ROOM) {
            gapsNs[count++] = nowNs - previousNs;
        }
        previousNs = nowNs;
    }

    /**
     * Prints how the flight went, if anyone asked for it.
     *
     * @param what which flight this was, for the line to name
     */
    public void report(String what) {
        Verbose.log(Verbose.Topic.THREE_D, () -> line(what));
    }

    /**
     * The line itself, built from the frames recorded.
     *
     * <p>Separate from {@link #report} so it can be tested without capturing what the app prints,
     * and package-visible for that reason alone.
     */
    String line(String what) {
        if (count == 0) {
            return what + ": no frames, which means it was skipped or never started";
        }
        long[] sorted = Arrays.copyOf(gapsNs, count);
        Arrays.sort(sorted);

        int worstAt = 0;
        for (int i = 0; i < count; i++) {
            if (gapsNs[i] > gapsNs[worstAt]) {
                worstAt = i;
            }
        }
        double medianMs = sorted[count / 2] / 1e6;

        return String.format(
                "%s: %d frames, about %.0f a second (median %.1f ms), 90th %.1f ms, "
                        + "worst %.1f ms at frame %d of %d",
                what, count, 1000 / medianMs, medianMs,
                sorted[(int) (count * 0.9)] / 1e6,
                gapsNs[worstAt] / 1e6, worstAt + 1, count);
    }

    /** How many frames were recorded. Read by the tests, and by whatever reads these next. */
    public int frames() {
        return count;
    }

    /**
     * The middle frame in milliseconds, or 0 if there were none.
     *
     * <p>Here because the adaptive resolution that follows this needs a number rather than a line
     * of text, and it should read the same number the log prints rather than a second one worked
     * out somewhere else.
     */
    public double medianMs() {
        if (count == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(gapsNs, count);
        Arrays.sort(sorted);
        return sorted[count / 2] / 1e6;
    }
}
