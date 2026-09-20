package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.threed.CameraPose;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A throwaway probe for M6.7 item 3: <b>the 3D view is laggy on weak graphics.</b>
 *
 * <p>The milestone row says to start by re-running M5.2b's measurement rather than guessing, and
 * that measurement was about the way <em>in</em>: the descent, which {@code TransitionCostProbe}
 * still times. This is the other half, the one item 3 is actually about: what a frame costs once
 * you are standing in the room and turning around.
 *
 * <p>It does not propose a fix. It asks where the time goes, across four things that could each
 * account for it and that call for completely different answers:
 *
 * <ul>
 *   <li><b>How many boxes there are.</b> Each one is its own mesh with its own material, so the
 *       cost per box is a draw call plus a texture bind.
 *   <li><b>How many distinct colors there are.</b> {@code FaceStrip.forColor} builds a fresh
 *       {@code WritableImage} on every call and {@code boxView} calls it once per box, so a room
 *       of 500 boxes holds 500 textures even when they are all the same color. If this is what
 *       costs, the fix is a cache keyed by color and it is cheap.
 *   <li><b>The vignette layer.</b> M6.7 added a transparent quad over every surface, which is
 *       overdraw across the whole window. It measured at about 2% on this machine and a real
 *       integrated chip may weigh blending differently.
 *   <li><b>Plain fill rate.</b> If the same room at twice the pixels costs twice as much, the
 *       answer is about pixels and not about the scene at all.
 * </ul>
 *
 * <p><b>Round robin in short slices, and that is not decoration.</b> Two earlier probes in this
 * milestone reported a different winner on consecutive runs because the machine drifts more than
 * the differences being measured. Arms are interleaved and pooled.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=ThreeDFrameProbe}.
 */
class ThreeDFrameProbe extends ApplicationTest {

    private static final int SLICES = 5;

    /** How many times each slice draws the room. */
    private static final int RENDERS_PER_SLICE = 12;

    private Scene scene;

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        scene = new Scene(root, 1200, 800);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    /** One thing being compared, and the frames and build times it has accumulated. */
    private record Arm(String label, int boxes, int colors, int width, int height,
            boolean vignette, List<Long> frames, List<Long> builds) {
        Arm(String label, int boxes, int colors, int width, int height) {
            this(label, boxes, colors, width, height, true, new ArrayList<>(), new ArrayList<>());
        }

        Arm withoutVignette() {
            return new Arm(label + ", no vignette", boxes, colors, width, height, false,
                    new ArrayList<>(), new ArrayList<>());
        }
    }

    @Test
    void whereDoesAFrameGo() {
        List<Arm> arms = List.of(
                new Arm("empty room", 0, 1, 1200, 800),
                new Arm("12 boxes", 12, 12, 1200, 800),
                new Arm("100 boxes, 100 colors", 100, 100, 1200, 800),
                new Arm("100 boxes, 1 color", 100, 1, 1200, 800),
                new Arm("500 boxes, 500 colors", 500, 500, 1200, 800),
                new Arm("500 boxes, 1 color", 500, 1, 1200, 800),
                new Arm("12 boxes, quarter the pixels", 12, 12, 600, 400),
                new Arm("empty room", 0, 1, 600, 400),
                new Arm("12 boxes", 12, 12, 1200, 800).withoutVignette(),
                new Arm("500 boxes, 500 colors", 500, 500, 1200, 800).withoutVignette());

        for (int slice = 0; slice < SLICES; slice++) {
            for (Arm arm : arms) {
                measure(arm);
            }
        }

        System.out.println("PROBE ---- what it costs to draw the room once ----");
        for (Arm arm : arms) {
            report(arm);
        }
    }

    private void measure(Arm arm) {
        AppState state = new AppState();
        state.room.w = 24;
        state.room.l = 24;
        state.room.h = 10;
        for (int i = 0; i < arm.boxes(); i++) {
            state.items.add(box(i, arm.colors()));
        }

        JfxRenderer3D renderer = new JfxRenderer3D();
        long[] built = new long[1];
        interact(() -> {
            renderer.init(arm.width(), arm.height());
            long start = System.nanoTime();
            renderer.build(state);
            if (!arm.vignette()) {
                // Prices the layer M6.7 added: one transparent quad over every surface, which is
                // overdraw across the whole window. Removing it is not a proposal, the design
                // system asks for the darkening; this is only how much of the frame it is.
                renderer.node().getRoot().getChildrenUnmodifiable();
                ((javafx.scene.Group) renderer.node().getRoot()).getChildren()
                        .removeIf(n -> n.getId() != null && n.getId().endsWith("-vignette"));
            }
            built[0] = System.nanoTime() - start;
            ((StackPane) scene.getRoot()).getChildren().setAll(renderer.node());
        });
        WaitForAsyncUtils.waitForFxEvents();
        arm.builds().add(built[0]);

        // WHAT IS TIMED HERE, AND WHY IT IS NOT THE GAP BETWEEN FRAMES.
        //
        // The first version of this probe timed an AnimationTimer's intervals, and every arm came
        // back with a median of exactly 16 ms: that is JavaFX's pulse running at 60 a second, not
        // the room being drawn. Under a cap, work below 16.7 ms is invisible, so the arms could
        // only differ in how often they OVERRAN, which is a far noisier thing to measure and is
        // not what a fix has to move.
        //
        // snapshot() renders the 3D scene synchronously and hands back when it is done, so this is
        // the work itself with no pulse in the way. It is not identical to what the pulse does
        // (it renders to an image rather than to the screen) but it walks the same scene and
        // rasterizes the same pixels, and it is the only way to see a difference the cap hides.
        List<Long> renders = new ArrayList<>();
        for (int i = 0; i < RENDERS_PER_SLICE; i++) {
            final double angle = i * 3.0;
            interact(() -> renderer.render(new CameraPose(state.room.w / 2.0, 6,
                    state.room.l / 2.0, Math.toRadians(angle), Math.toRadians(-8))));
            long each = WaitForAsyncUtils.waitForAsyncFx(10000, () -> {
                long t = System.nanoTime();
                renderer.node().snapshot(null, null);
                return System.nanoTime() - t;
            });
            renders.add(each);
        }
        interact(renderer::dispose);
        WaitForAsyncUtils.waitForFxEvents();

        // The first is dropped: it carries the cost of uploading every texture to the card, which
        // a real frame pays once when the view opens and never again.
        arm.frames().addAll(renders.subList(1, renders.size()));
    }

    /** A box at a repeatable spot, in one of {@code colors} distinct hues. */
    private static Item box(int i, int colors) {
        Item item = new Item();
        item.id = "b" + i;
        item.serial = i + 1;
        item.dragOrder = i + 1;
        item.x_px = (1 + (i % 22)) * 96;
        item.y_px = (1 + (i / 22 % 22)) * 96;
        item.w_in = 12;
        item.l_in = 12;
        item.h_in = 12;
        item.color = "hsl(" + (i % colors) * 360 / Math.max(1, colors) + ",55%,42%)";
        return item;
    }

    private static void report(Arm arm) {
        List<Long> frames = new ArrayList<>(arm.frames());
        frames.sort(null);
        List<Long> builds = new ArrayList<>(arm.builds());
        builds.sort(null);
        if (frames.isEmpty()) {
            System.out.println("PROBE " + arm.label() + ": no frames");
            return;
        }
        System.out.printf("PROBE   %-36s build %6.1f ms, draw the room %5.2f ms "
                        + "(median of %d), 90th %5.2f ms%n",
                arm.label(), builds.get(builds.size() / 2) / 1e6,
                frames.get(frames.size() / 2) / 1e6, frames.size(),
                frames.get((int) (frames.size() * 0.9)) / 1e6);
    }

}
