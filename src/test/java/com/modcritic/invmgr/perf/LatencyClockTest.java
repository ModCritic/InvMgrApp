package com.modcritic.invmgr.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.LatencyClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Proves the M6.7b clock can see a delay, before any number it reports is believed.
 *
 * <p><b>Why this exists as a test and not as a note.</b> CLAUDE.md §2 says a green suite proves the
 * tests pass, not that they would catch a regression, and a measuring tool has its own version of
 * that failure: one that reports plausible numbers while measuring nothing would look exactly like
 * a fast app. So the delay is put in on purpose here and the clock has to find it. The
 * {@code LatencyBench} numbers, and every percentage in the M6.7b tables, rest on this passing.
 *
 * <p>It runs in the ordinary suite because it is quick and because a clock that silently stops
 * working should break the build rather than wait to be noticed.
 */
class LatencyClockTest extends ApplicationTest {

    /**
     * The delay put into the handler on purpose.
     *
     * <p>Comfortably more than one 60 Hz frame, so the reading has to move whether or not the
     * toolkit is capped. A delay smaller than a frame would be invisible in the capped arm and the
     * test would fail for a reason that is not a broken clock.
     */
    private static final long STALL_MS = 40;

    /** How many clicks each half takes. Enough for a median to mean something. */
    private static final int CLICKS = 12;

    private final List<LatencyClock.Sample> samples = Collections.synchronizedList(new ArrayList<>());
    private Button button;
    private volatile boolean stallingInTheHandler;
    private volatile boolean stallingInTheFrame;

    @Override
    public void start(Stage stage) {
        button = new Button("press me");
        button.setPrefSize(300, 200);
        // The stall goes in a handler on the button, which is where a slow app's work would be.
        // The clock's own filter is on the scene and runs first, so it stamps arrival before this.
        button.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (stallingInTheHandler) {
                sleep();
            }
        });
        Scene scene = new Scene(new StackPane(button), 480, 320);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();
        LatencyClock.installFor(scene, samples::add);

        // The other place a delay can hide. An AnimationTimer runs at the start of a pulse, so a
        // stall here lands between the clock's two pulse marks and therefore in `render`, where a
        // slow frame's cost really does sit. The handler stall above lands in `queue`. Both halves
        // need their own test or half the clock can break unnoticed; see the second test below.
        new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (stallingInTheFrame) {
                    sleep();
                }
            }
        }.start();
    }

    private static void sleep() {
        try {
            Thread.sleep(STALL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a 40 ms stall in a handler shows up, and shows up as waiting")
    void theClockSeesADelayBeforeTheFrame() {
        double[] quick = medianOfClicks(false, false);
        double[] slow = medianOfClicks(true, false);

        assertRoughlyTheStall("total", quick[0], slow[0]);
        assertRoughlyTheStall("queue", quick[1], slow[1]);
    }

    @Test
    @DisplayName("a 40 ms stall inside the frame shows up, and shows up as drawing")
    void theClockSeesADelayInTheFrame() {
        double[] quick = medianOfClicks(false, false);
        double[] slow = medianOfClicks(false, true);

        // Split out from the test above on purpose. A stall in an event handler lands entirely in
        // `queue`, so a clock whose `render` half had stopped working would sail through that one
        // reporting the right total. This is the half that catches it.
        assertRoughlyTheStall("total", quick[0], slow[0]);
        assertRoughlyTheStall("render", quick[2], slow[2]);
    }

    private static void assertRoughlyTheStall(String which, double quick, double slow) {
        assertTrue(slow - quick > STALL_MS * 0.6,
                "the clock has to see a delay that was put in on purpose, or nothing it reports "
                        + "means anything. A " + STALL_MS + " ms stall moved the median " + which
                        + " from " + String.format("%.1f", quick) + " ms to "
                        + String.format("%.1f", slow) + " ms, a rise of only "
                        + String.format("%.1f", slow - quick));
        assertTrue(slow - quick < STALL_MS * 2.5,
                "and it must not report far MORE than the delay that was added, which would mean "
                        + which + " is measuring something else: "
                        + String.format("%.1f", slow - quick) + " ms for a " + STALL_MS
                        + " ms stall");
    }

    @Test
    @DisplayName("the three numbers on a sample add up")
    void queuePlusRenderIsTheTotal() {
        medianOfClicks(false, false);
        assertTrue(samples.size() >= CLICKS,
                "expected at least " + CLICKS + " samples, got " + samples.size());
        for (LatencyClock.Sample sample : samples) {
            assertEquals(sample.totalMs(), sample.queueMs() + sample.renderMs(), 0.002,
                    "queue plus render is the total, by construction; " + sample.line());
            assertTrue(sample.queueMs() >= 0 && sample.renderMs() >= 0,
                    "neither half can be negative: " + sample.line());
        }
    }

    @Test
    @DisplayName("an event type reaches the sample, so the phone's two copies of a finger can be told apart")
    void theEventTypeIsRecorded() {
        medianOfClicks(false, false);
        assertTrue(samples.stream().anyMatch(s -> s.type().equals("mouse-press")),
                "a press should be recorded as mouse-press; got "
                        + samples.stream().map(LatencyClock.Sample::type).distinct().toList());
        assertTrue(samples.stream().anyMatch(s -> s.type().equals("mouse-release")),
                "and a release as mouse-release");
    }

    /**
     * Clicks the button a dozen times.
     *
     * @return the median total, queue and render of the presses, in milliseconds
     */
    private double[] medianOfClicks(boolean stallTheHandler, boolean stallTheFrame) {
        samples.clear();
        stallingInTheHandler = stallTheHandler;
        stallingInTheFrame = stallTheFrame;
        for (int i = 0; i < CLICKS; i++) {
            clickOn(button);
            WaitForAsyncUtils.waitForFxEvents();
            WaitForAsyncUtils.sleep(40, TimeUnit.MILLISECONDS);
        }
        WaitForAsyncUtils.waitForFxEvents();
        stallingInTheHandler = false;
        stallingInTheFrame = false;

        List<Double> totals = new ArrayList<>();
        List<Double> queues = new ArrayList<>();
        List<Double> renders = new ArrayList<>();
        synchronized (samples) {
            for (LatencyClock.Sample sample : samples) {
                if (sample.type().equals("mouse-press")) {
                    totals.add(sample.totalMs());
                    queues.add(sample.queueMs());
                    renders.add(sample.renderMs());
                }
            }
        }
        assertTrue(totals.size() >= CLICKS - 1,
                "expected a sample per click, got " + totals.size() + " of " + CLICKS);
        totals.sort(null);
        queues.sort(null);
        renders.sort(null);
        return new double[] {totals.get(totals.size() / 2), queues.get(queues.size() / 2),
                renders.get(renders.size() / 2)};
    }
}
