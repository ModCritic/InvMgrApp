package com.modcritic.invmgr;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.RotateEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.ZoomEvent;

/**
 * Times how long the app takes to put a frame on the screen after an input event.
 *
 * <p><b>What this is for.</b> M6.7b needs one number that means the same thing on a desktop, on a
 * virtual machine and on the phone, because the phone is where the app actually feels slow and
 * nothing in {@code src/test} can reach it. TestFX can drive the two desktops; nothing can drive
 * the phone from inside. So the app measures itself, the same way {@link
 * com.modcritic.invmgr.threed.FlightClock} already does for a flight into the 3D view, and the
 * harness in {@code tools/perf} reads the lines back off stdout or logcat.
 *
 * <h2>The three numbers, and what each one is</h2>
 *
 * <pre>
 *   queue    the event arrived, then the app waited for the next pulse to start
 *   render   that pulse ran: layout, then the frame was drawn and handed over
 *   total    queue + render, which is what a hand feels
 * </pre>
 *
 * <p><b>The frame is counted as out at the start of the pulse AFTER the one that drew it</b>, and
 * that is a choice rather than an obvious truth. JavaFX offers no "this frame is on the glass"
 * callback. So it was checked against a camera: {@code PulseVsPixelsProbe} grabs a rectangle of the
 * real X display about 1,600 times a second while the app changes one box, and the pixels land a
 * <b>median of 1.75 ms after the second pulse</b> (14 readings, worst +10.6, best -0.7). Under one
 * frame either way, and slightly optimistic. The same probe measured the other end: an OS key press
 * reaches an event filter in <b>0.65 ms</b>, so stamping the arrival in a filter loses almost
 * nothing.
 *
 * <h2>⚠ It is off unless asked for, and asking for it changes the app</h2>
 *
 * <p>Nothing here is installed unless {@code -Dinvmgr.verbose=latency} is set. That is stricter
 * than {@code FlightClock}, which measures always and only prints under a flag, and the reason is
 * the {@link AnimationTimer} below: a running timer makes JavaFX pulse every frame whether or not
 * anything is dirty. On an idle app that is work it would not otherwise do. Measuring changes what
 * is measured here, so the measurement does not exist until somebody switches it on, and every
 * number the suite reports is taken with it on at both ends of a comparison.
 *
 * <h2>⚠ It must not print what the user made</h2>
 *
 * <p>Same rule as {@link Verbose}: these lines are for pasting into a bug report. An event type, a
 * count and three durations are diagnostics. What was clicked on is not, and is not recorded.
 *
 * <h2>Where the samples go</h2>
 *
 * <p>{@link #installIfAsked} prints them, which is the only thing the shipped app does and the only
 * way the phone can report anything. {@link #installFor} hands each one to a {@link Consumer}
 * instead, which is how the two desktop lanes collect them: <b>the same measuring code, read two
 * ways</b>, rather than a second implementation in the test tree that could drift from this one.
 * Same argument as {@code Verbose.parse} taking its complaints as a parameter.
 */
public final class LatencyClock {

    /**
     * How many events may be waiting for a frame at once.
     *
     * <p>A drag delivers a handful of events between two pulses on a busy machine, never dozens.
     * The cap is a backstop: a diagnostic that can grow without limit is a bug waiting for a slow
     * machine, the same reasoning {@code FlightClock.ROOM} is built on.
     */
    private static final int WAITING_ROOM = 64;

    /**
     * One measurement: an event, and how long the frame showing its result took.
     *
     * <p>All three durations are in milliseconds. {@code wallMs} is the clock time the frame went
     * out, which is what lets a harness on another machine line samples up with what it sent.
     */
    public record Sample(long seq, String type, double queueMs, double renderMs, double totalMs,
            long wallMs) {

        /** The one-line form, which is what the app prints and what {@code tools/perf} parses. */
        public String line() {
            return String.format("sample seq=%d type=%s q=%.3f r=%.3f t=%.3f ms=%d",
                    seq, type, queueMs, renderMs, totalMs, wallMs);
        }
    }

    /** An event that has arrived and is still waiting to be drawn. */
    private static final class Pending {
        final String type;
        final long arrivedNs;
        long pulse1Ns;

        Pending(String type, long arrivedNs) {
            this.type = type;
            this.arrivedNs = arrivedNs;
        }
    }

    private final Deque<Pending> waiting = new ArrayDeque<>();
    private final Consumer<Sample> sink;
    private long sequence;

    private LatencyClock(Consumer<Sample> sink) {
        this.sink = sink;
    }

    /**
     * Starts measuring, if anybody asked for it.
     *
     * <p>Called once from {@code App.start} with the scene already built. Does nothing at all when
     * the topic is off, which is the normal case and costs one set lookup.
     *
     * @param scene the app's scene, which is where the event filters go
     */
    public static void installIfAsked(Scene scene) {
        if (!Verbose.on(Verbose.Topic.LATENCY)) {
            return;
        }
        installFor(scene, sample -> Verbose.log(Verbose.Topic.LATENCY, sample::line));
        Verbose.log(Verbose.Topic.LATENCY, () -> "measuring input to frame, a line per event, "
                + "times in milliseconds. ⚠ While this is on the app draws a frame every tick "
                + "whether or not anything moved, so it is busier than it would otherwise be.");
    }

    /**
     * Starts measuring and hands every sample to {@code sink}, whatever the verbose flag says.
     *
     * <p>This is the door the desktop harness comes in by. It takes the samples as objects rather
     * than reading them back off stdout, and it does not consult {@link Verbose}, so a bench run
     * does not depend on a system property having been read before this class was first touched.
     *
     * @param scene the scene to watch
     * @param sink told about each event once the frame showing its result has gone out
     * @return the clock, so a caller can keep it alive and identify it
     */
    public static LatencyClock installFor(Scene scene, Consumer<Sample> sink) {
        LatencyClock clock = new LatencyClock(sink);
        clock.install(scene);
        return clock;
    }

    private void install(Scene scene) {
        // MOUSE_MOVED and TOUCH_STATIONARY are left out deliberately. They arrive constantly and
        // mostly change nothing, and a log full of events that drew no frame would bury the ones
        // that did. Everything here is an event a scenario in tools/perf actually sends.
        //
        // ⚠ A finger on the phone arrives TWICE, as a touch event and as a synthesized mouse
        // event (CLAUDE.md §5.8 item 5). Both are recorded and the type is on every line, so the
        // harness can count one of them rather than both. Filtering here would be guessing which
        // one the caller wanted.
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> arrived("mouse-press"));
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> arrived("mouse-drag"));
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> arrived("mouse-release"));
        scene.addEventFilter(TouchEvent.TOUCH_PRESSED, e -> arrived("touch-press"));
        scene.addEventFilter(TouchEvent.TOUCH_MOVED, e -> arrived("touch-move"));
        scene.addEventFilter(TouchEvent.TOUCH_RELEASED, e -> arrived("touch-release"));
        scene.addEventFilter(ScrollEvent.SCROLL, e -> arrived("scroll"));
        scene.addEventFilter(ZoomEvent.ZOOM, e -> arrived("zoom"));
        scene.addEventFilter(RotateEvent.ROTATE, e -> arrived("rotate"));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> arrived("key"));

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                pulse();
            }
        }.start();
    }

    /** One input event, stamped the moment the scene saw it. */
    private void arrived(String type) {
        if (waiting.size() < WAITING_ROOM) {
            waiting.addLast(new Pending(type, System.nanoTime()));
        }
    }

    /**
     * One pulse. Events with no pulse yet get this one; events that already had one are done.
     *
     * <p>Walked oldest first and stopped at the first event still waiting for its first pulse,
     * which is safe because the queue is in arrival order and a later event cannot have an earlier
     * pulse.
     */
    private void pulse() {
        long now = System.nanoTime();
        while (!waiting.isEmpty() && waiting.peekFirst().pulse1Ns != 0) {
            emit(waiting.removeFirst(), now);
        }
        for (Pending p : waiting) {
            p.pulse1Ns = now;
        }
    }

    private void emit(Pending p, long frameOutNs) {
        sink.accept(new Sample(sequence++, p.type,
                (p.pulse1Ns - p.arrivedNs) / 1e6,
                (frameOutNs - p.pulse1Ns) / 1e6,
                (frameOutNs - p.arrivedNs) / 1e6,
                System.currentTimeMillis()));
    }
}
