package com.modcritic.invmgr.threed.jfx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * What one box's color strip costs to build, and whether keeping them is worth anything.
 *
 * <p>{@code JfxRenderer3D.boxView} <b>used to call</b> {@link FaceStrip#forColor} once per box,
 * and that method builds a fresh {@code WritableImage} every time. At the save format's cap of
 * 500 items that is 500 pictures, even in a room where every box is the same color.
 *
 * <p><b>Past tense since M6.7b</b>, which is the change this probe argued for: the renderer now
 * builds one {@code FaceStrip.atlas} for the whole room. The probe is kept because it is the
 * before half of that comparison, and it is the only thing left calling {@code forColor}. M6.7c
 * corrected this paragraph, which still described the old renderer.
 *
 * <p><b>This exists because the whole-room measurement could not settle the question.</b> Building
 * a 500-box room came out 19 ms slower with 500 distinct colors than with one on two runs and
 * 1.4 ms FASTER on a third. A room build is doing too many other things for a difference that size
 * to be readable in it, so this times the one method on its own.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=FaceStripCostProbe}.
 */
class FaceStripCostProbe extends ApplicationTest {

    private static final int BOXES = 500;
    private static final int ROUNDS = 9;

    @Override
    public void start(Stage stage) {
        stage.show();
    }

    @Test
    void whatOneColorStripCosts() {
        List<Long> distinct = new ArrayList<>();
        List<Long> same = new ArrayList<>();
        List<Long> cached = new ArrayList<>();

        // Round robin, because the machine drifts more than the thing being measured. Two probes
        // in this milestone already reported a different winner on consecutive runs.
        for (int round = 0; round < ROUNDS; round++) {
            distinct.add(time(true, false));
            same.add(time(false, false));
            cached.add(time(true, true));
        }

        report(BOXES + " boxes, all different colors", distinct);
        report(BOXES + " boxes, all one color", same);
        report(BOXES + " boxes, all different, through a cache", cached);
    }

    /**
     * Builds one room's worth of strips and says how long it took.
     *
     * @param distinctColors give every box its own color rather than sharing one
     * @param cache look the color up in a map first, which is the change being priced
     */
    private long time(boolean distinctColors, boolean cache) {
        return WaitForAsyncUtils.waitForAsyncFx(30000, () -> {
            Map<String, Image> kept = new HashMap<>();
            List<Image> strips = new ArrayList<>(BOXES);
            long start = System.nanoTime();
            for (int i = 0; i < BOXES; i++) {
                String color = "hsl(" + (distinctColors ? i * 360 / BOXES : 200) + ",55%,42%)";
                strips.add(cache
                        ? kept.computeIfAbsent(color, FaceStrip::forColor)
                        : FaceStrip.forColor(color));
            }
            long elapsed = System.nanoTime() - start;
            // Held until after the clock stops so nothing is collected mid-measurement.
            return strips.isEmpty() ? elapsed : elapsed;
        });
    }

    private static void report(String what, List<Long> rounds) {
        List<Long> sorted = new ArrayList<>(rounds);
        sorted.sort(null);
        System.out.printf("PROBE %-46s %6.2f ms (median of %d), best %5.2f%n",
                what, sorted.get(sorted.size() / 2) / 1e6, sorted.size(), sorted.get(0) / 1e6);
    }
}
