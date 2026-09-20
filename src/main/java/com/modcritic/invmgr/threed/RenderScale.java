package com.modcritic.invmgr.threed;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * How many pixels the 3D room should actually be drawn at, as a fraction of the window.
 *
 * <p>Arithmetic only, with no JavaFX in it, so every rule here is testable without a window. The
 * part that does the drawing is {@code threed.jfx.ScaledSurface}.
 *
 * <h2>What this is for</h2>
 *
 * <p>M6.7 item 3 measured the 3D room and found it short of fill rate and nothing else: five
 * hundred boxes, the save format's own cap, cost under a millisecond to draw. Drawing the room
 * smaller than the window and letting it stretch back up is the standard answer to that, and the
 * user asked for it after a virtual machine running Mesa's software OpenGL came back at ten frames
 * a second.
 *
 * <pre>
 *   java -jar InvMgr.jar                              auto, which is the default
 *   java -Dinvmgr.renderscale=off -jar InvMgr.jar     never, whatever the machine does
 *   java -Dinvmgr.renderscale=0.5 -jar InvMgr.jar     pinned to half, for measuring
 * </pre>
 *
 * <h2>What it is worth, measured on both ends</h2>
 *
 * <p>It shipped off by default on 2026-09-15, because every number behind it came from a machine
 * with no graphics card where the saving was small: 8% at half and 16% at a quarter, because the
 * snapshot's own fixed cost of about 2.9 ms a frame ate most of the reduction on an 11 ms frame.
 * <b>The user then ran it on the virtual machine it was built for</b>, which draws the room in 95
 * ms, and the same fixed cost is a much smaller share there:
 *
 * <table border="1">
 *   <caption>A 200 ft room on a four-thread VM running Mesa's software OpenGL, 2026-09-16</caption>
 *   <tr><th>setting</th><th>descent</th><th>against off</th><th>ascent</th><th>against off</th></tr>
 *   <tr><td>off</td><td>95.5 ms</td><td></td><td>117.1 ms</td><td></td></tr>
 *   <tr><td>0.5</td><td>77.5 ms</td><td><b>19% faster</b></td><td>100.7 ms</td><td>14%</td></tr>
 *   <tr><td>0.25</td><td>68.8 ms</td><td><b>28% faster</b></td><td>92.8 ms</td><td>21%</td></tr>
 * </table>
 *
 * <p><b>⚠ Note how far that is from what a quarter of the pixels ought to buy.</b> If drawing were
 * the whole cost, a sixteenth of the pixels would be worth far more than 28%. The upscale back to
 * the window is itself a full-window operation done by the same software rasterizer, and it does
 * not shrink with the factor, so there is a floor under this. Turning the upscale's smoothing off
 * was measured and made no reliable difference: 9.58 ms against 11.61 at half and 10.02 against
 * 9.21 at a quarter, which contradict each other and are therefore noise.
 *
 * <p>The default became {@code auto} on the strength of the VM numbers and the user's decision. It
 * never engages on a machine keeping up, so the two healthy machines that have reported in never
 * see it. {@code SnapshotScalingProbe} holds the rest of the measurements and the controls behind
 * them.
 *
 * <h2>Why the trigger is measured frames and not a driver name</h2>
 *
 * <p>The obvious trigger would be "when Prism falls back to software rendering", which is how the
 * user described it. <b>The app cannot see that.</b> Once {@link com.modcritic.invmgr.Launcher}
 * sets {@code prism.forceGPU}, {@code SCENE3D} reports true on Mesa's llvmpipe exactly as it does
 * on a graphics card, because from Prism's point of view llvmpipe <em>is</em> an OpenGL driver.
 * There is no supported way to ask which one.
 *
 * <p>What the app can see is how long its own frames took, which {@link FlightClock} already
 * measures for every flight into and out of the room, and which three different machines confirmed
 * reads correctly. So {@code auto} watches that instead. It is the better signal anyway: it
 * responds to a machine that is struggling for any reason, not only for the one reason that was
 * guessed at in advance.
 *
 * <h2>⚠ Auto only ever reduces, never restores</h2>
 *
 * <p>Left to itself it would oscillate, and visibly. Scaling makes the next flight faster, a faster
 * flight reads as healthy, healthy turns scaling back off, and the room is slow again: the picture
 * would change sharpness every time you entered or left. {@link #factorFor} takes the factor in use
 * and never returns a larger one, so within a session the room can get coarser and never gets
 * sharper. Starting the app again starts the decision again.
 */
public final class RenderScale {

    /** The property that sets this: {@code off}, {@code auto}, or a fraction such as {@code 0.5}. */
    public static final String PROPERTY = "invmgr.renderscale";

    /** The word for "draw every pixel, whatever the machine is doing". */
    public static final String OFF = "off";

    /**
     * The word for "decide from how the last flight went", <b>which is what happens when nobody
     * says otherwise</b>.
     */
    public static final String AUTO = "auto";

    /**
     * The coarsest the room is allowed to get.
     *
     * <p>A quarter in each direction is a sixteenth of the pixels, and at that point the far end of
     * a 200 ft room has lost its grid entirely. Anything below it trades away more than it buys.
     */
    public static final double SMALLEST = 0.25;

    /** Drawing every pixel. */
    public static final double FULL = 1.0;

    /**
     * Slower than this and {@code auto} halves the room: 33.3 ms, which is 30 frames a second.
     *
     * <p>Chosen with room to spare on both sides, from the three machines that reported in on
     * 2026-09-15: a 3090 Ti came back at 6.1 ms and a ThinkPad on a 60 Hz screen at 16.7 ms, both
     * comfortably faster; the virtual machine came back at 98.8 ms, three times slower than even
     * the second threshold. Nothing real sits near either line.
     */
    static final double HALVE_ABOVE_MS = 1000.0 / 30;

    /** Slower than this, 66.7 ms or 15 frames a second, and it goes to a quarter. */
    static final double QUARTER_ABOVE_MS = 1000.0 / 15;

    /**
     * How many slow flights in a row it takes before {@code auto} acts.
     *
     * <p><b>⚠ One is not enough, and the reason is that the decision never comes back.</b> A
     * machine that stalls once, because something else on it wanted the processor for two seconds,
     * would drop a factor it can never climb out of and stay soft until the app is restarted. Two
     * in a row costs one extra flight on a machine that really is slow, where every flight is slow,
     * and rules out the passing stall, where they are not consecutive.
     *
     * <p>Found by running the test suite with {@code auto} as the default: this machine draws a
     * 2560 x 1440 window through Mesa's software rasterizer and reported flights at 34 to 46 ms,
     * which is correctly slow and would have been acted on from a single reading.
     */
    static final int SLOW_FLIGHTS_NEEDED = 2;

    /**
     * What the command line asked for, worked out once.
     *
     * <p>Read at class-load rather than per use, for the reason {@link com.modcritic.invmgr.Verbose}
     * gives: the property cannot change while the program runs. <b>The startup report reads this,
     * and that is what makes a mistyped value report itself</b> rather than waiting for somebody to
     * open the 3D view.
     */
    private static final RenderScale ASKED_FOR = from(System.getProperty(PROPERTY),
            RenderScale::complain);

    private final boolean auto;
    private final double pinned;

    /**
     * How many flights in a row have asked for a smaller factor than the one in use.
     *
     * <p>The one piece of state on this class, and it is here rather than in {@code View3D} so
     * that {@link #SLOW_FLIGHTS_NEEDED} can be tested without a window like every other rule.
     */
    private int slowFlightsInARow;

    private RenderScale(boolean auto, double pinned) {
        this.auto = auto;
        this.pinned = pinned;
    }

    /**
     * Reads the setting off the command line.
     *
     * <p>Takes its input and its complaints as arguments so both halves can be tested without a
     * command line and without capturing {@code System.out}, the same shape as
     * {@link com.modcritic.invmgr.Verbose#parse}.
     *
     * <p><b>⚠ A value that means nothing is reported rather than ignored</b>, for the reason
     * {@code Verbose} gives at length: somebody who mistypes it and sees no change will conclude
     * the setting does not exist.
     *
     * @param value what the property said, or null
     * @param complain told what was wrong, once, if anything was
     */
    public static RenderScale from(String value, Consumer<String> complain) {
        if (value == null) {
            return new RenderScale(true, FULL);
        }
        String word = value.trim().toLowerCase(Locale.ROOT);
        if (word.isEmpty() || AUTO.equals(word)) {
            // An empty value is somebody who typed the flag and no setting, which is closer to
            // "do the usual thing" than to "never do it".
            return new RenderScale(true, FULL);
        }
        if (OFF.equals(word)) {
            return new RenderScale(false, FULL);
        }
        double asked;
        try {
            asked = Double.parseDouble(word);
        } catch (NumberFormatException notANumber) {
            complain.accept("\"" + value + "\" is not " + OFF + ", " + AUTO
                    + ", or a fraction between " + SMALLEST + " and " + FULL);
            return new RenderScale(true, FULL);
        }
        if (asked < SMALLEST || asked > FULL) {
            complain.accept(asked + " is outside " + SMALLEST + " to " + FULL);
            return new RenderScale(true, FULL);
        }
        return new RenderScale(false, asked);
    }

    /** The setting this run is using. */
    public static RenderScale current() {
        return ASKED_FOR;
    }

    /** Whether the factor is decided from measured frames rather than pinned. */
    public boolean isAuto() {
        return auto;
    }

    /** The factor to start at: full for auto, since nothing has been measured yet. */
    public double startingFactor() {
        return auto ? FULL : pinned;
    }

    /**
     * The factor to use after a flight that took this long per frame.
     *
     * @param medianMs the middle frame of the flight just finished, from {@link FlightClock}
     * @param inUse the factor being drawn at now, which this will never exceed
     */
    public double factorFor(double medianMs, double inUse) {
        if (!auto) {
            return pinned;
        }
        double wanted = forMedianMs(medianMs);
        if (wanted >= inUse) {
            // Fast enough, or no worse than what is already in use. Either way the run of slow
            // flights is broken, which is the whole point of counting them.
            slowFlightsInARow = 0;
            return inUse;
        }
        slowFlightsInARow++;
        if (slowFlightsInARow < SLOW_FLIGHTS_NEEDED) {
            return inUse;
        }
        slowFlightsInARow = 0;
        return wanted;
    }

    /**
     * The factor a median frame time asks for, before the never-increase rule is applied.
     *
     * <p>Package-visible because it is the table the tests check, and it is worth checking
     * separately from the rule that clamps it.
     */
    static double forMedianMs(double medianMs) {
        if (medianMs > QUARTER_ABOVE_MS) {
            return SMALLEST;
        }
        if (medianMs > HALVE_ABOVE_MS) {
            return 0.5;
        }
        return FULL;
    }

    /** What to print in the startup report, in words rather than numbers where there are words. */
    public String describe() {
        if (auto) {
            return AUTO;
        }
        return pinned == FULL ? OFF : String.format(Locale.ROOT, "%.4g", pinned);
    }

    private static void complain(String what) {
        System.out.println("InvMgr: ⚠ -D" + PROPERTY + " ignored: " + what + ".");
    }
}
