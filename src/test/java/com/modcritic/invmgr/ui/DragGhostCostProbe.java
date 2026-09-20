package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.App;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Scene;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A throwaway measuring probe, in the manner of {@link TransitionCostProbe}: not a test, and it
 * asserts nothing.
 *
 * <p>It exists because the user reported that dragging a planned item stutters on weak integrated
 * graphics, and CLAUDE.md §2 says an optimization needs a number behind it rather than an
 * argument. The milestone row named two suspects and this measures both:
 *
 * <ul>
 *   <li>the card's {@code dropshadow(gaussian, ..., 16, 0, 0, 6)}, a 16 px blur that Prism
 *       re-renders whenever the node is dirty;
 *   <li>{@link javafx.scene.Node#setLayoutX}, which dirties the parent for layout on every
 *       pointer move where {@code setTranslateX} would not.
 * </ul>
 *
 * <p><b>Why a replica and not the real {@link DragGhost}.</b> The comparison needs the swing
 * turning in every configuration, or the translate-only runs would score unfairly well: a card
 * whose rotation has settled is a card Prism can leave alone. {@code DragGhost.moveTo} is the
 * only thing that feeds its pendulum and it also sets the layout position, so there is no way to
 * drive the real object in translate mode. The replica is built from the real card's own style
 * string and size, read off {@code ghost.node()} at run time, so the two cannot drift apart.
 *
 * <p><b>What this machine is a stand-in for.</b> Prism runs here on Mesa's software OpenGL under
 * Xvfb, which has no GPU at all. That exaggerates fill-rate costs like the blur relative to a real
 * integrated chip, so treat the ratios as an upper bound on the shadow's share and the ordering as
 * the finding.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=DragGhostCostProbe}.
 */
class DragGhostCostProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    /** How long one slice of one configuration is carried around for. */
    private static final long SLICE_MS = 700;

    /** How many slices each configuration gets. */
    private static final int SLICES = 6;

    /** A row width in the same range the list actually produces. */
    private static final double CARD_WIDTH = 260;

    private App app;
    private Scene scene;
    private Pane layer;

    private final HBox replica = new HBox(Tokens.LIST_ROW_GAP);
    private final Rotate swing = new Rotate();
    private final TiltPendulum pendulum = new TiltPendulum();

    /** The real card's style string, kept whole so the shadow can be put back between runs. */
    private String cardStyle;

    /** Where the replica sits when it is being moved by translation rather than by layout. */
    private double baseX;
    private double baseY;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    /**
     * One configuration under test, and the frames it has accumulated so far.
     *
     * @param hint the cache hint, or null for no cache at all
     */
    private record Config(String what, boolean translate, boolean shadow, CacheHint hint,
            List<Long> frames) {
        Config(String what, boolean translate, boolean shadow, CacheHint hint) {
            this(what, translate, shadow, hint, new ArrayList<>());
        }
    }

    @Test
    void measureTheCarry() {
        interact(this::buildReplica);
        WaitForAsyncUtils.waitForFxEvents();

        List<Config> configs = List.of(
                new Config("layout + shadow (today)", false, true, null),
                new Config("layout, no shadow", false, false, null),
                new Config("translate + shadow", true, true, null),
                new Config("translate, no shadow", true, false, null),
                new Config("layout + shadow + cache SPEED", false, true, CacheHint.SPEED),
                new Config("layout + shadow + cache ROTATE", false, true, CacheHint.ROTATE),
                new Config("layout + shadow + cache DEFAULT", false, true, CacheHint.DEFAULT),
                new Config("translate + shadow + cache ROTATE", true, true, CacheHint.ROTATE));

        // And the same question asked of the shipped object rather than of the replica, which is
        // the only pair of numbers DragGhost's own documentation is allowed to quote.
        List<Long> ghostPlain = new ArrayList<>();
        List<Long> ghostCached = new ArrayList<>();

        // Round robin in short slices rather than one long run each. A single ordered sweep
        // cannot tell a real difference from drift: the first version of this probe had one
        // configuration score 8.2 ms on the way out and 10.8 ms on the way back, which is
        // larger than several of the differences it was being asked to resolve.
        for (int slice = 0; slice < SLICES; slice++) {
            for (Config c : configs) {
                carry(c, SLICE_MS);
            }
            carryTheRealGhost(ghostPlain, false, SLICE_MS);
            carryTheRealGhost(ghostCached, true, SLICE_MS);
        }

        System.out.println("PROBE ---- frame cost while carrying the card ----");
        System.out.printf("PROBE %d slices of %d ms each, round robin%n", SLICES, SLICE_MS);
        for (Config c : configs) {
            report(c.what(), c.frames());
        }

        report("THE REAL GHOST, cache off", ghostPlain);
        report("THE REAL GHOST, as shipped", ghostCached);

        System.out.println("PROBE ---- the layout pass on its own, render excluded ----");
        interact(this::measureLayoutPass);
        WaitForAsyncUtils.waitForFxEvents();

        System.out.println("PROBE ---- what the cache costs in pixels ----");
        compareLook(0);
        compareLook(9);
        compareLook(16);
    }

    /**
     * Screenshots the card cached and uncached at the same angle and reports the difference.
     *
     * <p>This is the half of the decision the frame timings cannot answer. A cache hint that
     * survives rotation survives it by resampling the bitmap rather than redrawing the card, so
     * the question is how much of the text's crispness that costs at the angles the swing
     * actually reaches, which {@code TiltPendulum.MAX_TILT_DEGREES} caps at 16. The cache is deliberately built at angle 0 and read at {@code degrees},
     * which is the order a real drag produces.
     */
    private void compareLook(double degrees) {
        interact(() -> {
            replica.setStyle(cardStyle);
            replica.setLayoutX(400);
            replica.setLayoutY(300);
            replica.setTranslateX(0);
            replica.setTranslateY(0);
            replica.setCache(false);
            swing.setPivotX(30);
            swing.setPivotY(12);
            swing.setAngle(0);
            if (!layer.getChildren().contains(replica)) {
                layer.getChildren().add(replica);
            }
        });
        settle(200);

        interact(() -> {
            replica.setCache(true);
            replica.setCacheHint(CacheHint.ROTATE);
        });
        settle(200);
        interact(() -> swing.setAngle(degrees));
        settle(200);
        WritableImage cached = shoot("ghost-cached-" + (int) degrees);

        interact(() -> replica.setCache(false));
        settle(200);
        WritableImage plain = shoot("ghost-plain-" + (int) degrees);

        interact(() -> layer.getChildren().remove(replica));
        WaitForAsyncUtils.waitForFxEvents();
        differ(degrees, cached, plain);
    }

    private WritableImage shoot(String name) {
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage image = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
        try {
            File directory = new File("target/screenshots");
            if (directory.isDirectory() || directory.mkdirs()) {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File(directory, name + ".png"));
            }
        } catch (IOException e) {
            System.err.println("could not write screenshot " + name + ": " + e.getMessage());
        }
        return image;
    }

    /** Counts how many pixels of the card's neighborhood the two shots disagree about. */
    private static void differ(double degrees, WritableImage a, WritableImage b) {
        int x0 = 360;
        int y0 = 260;
        int x1 = Math.min((int) a.getWidth(), 760);
        int y1 = Math.min((int) a.getHeight(), 400);
        int differing = 0;
        int worst = 0;
        long totalDelta = 0;
        int counted = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                Color ca = a.getPixelReader().getColor(x, y);
                Color cb = b.getPixelReader().getColor(x, y);
                int delta = Math.max(Math.max(
                        (int) Math.round(Math.abs(ca.getRed() - cb.getRed()) * 255),
                        (int) Math.round(Math.abs(ca.getGreen() - cb.getGreen()) * 255)),
                        (int) Math.round(Math.abs(ca.getBlue() - cb.getBlue()) * 255));
                counted++;
                if (delta > 0) {
                    differing++;
                    totalDelta += delta;
                    worst = Math.max(worst, delta);
                }
            }
        }
        System.out.printf("PROBE at %2.0f deg: %5d of %d pixels differ (%4.1f%%), "
                        + "worst channel %3d/255, mean over the differing ones %4.1f%n",
                degrees, differing, counted, 100.0 * differing / counted, worst,
                differing == 0 ? 0 : (double) totalDelta / differing);
    }

    /**
     * Builds the replica from the real ghost's own card.
     *
     * <p>Lifting the real ghost is what makes its style string and its pref width available;
     * it is dropped again immediately, so nothing but the numbers survives the call.
     */
    private void buildReplica() {
        DragGhost ghost = app.dragGhost();
        ghost.lift("probe [plan]", Color.web("#7aa2c8"), CARD_WIDTH, 40, 120, 60, 130);
        cardStyle = ghost.node().getStyle();
        // The real ghost's parent IS the ghost layer, which saves App an accessor it would
        // otherwise carry for a probe alone.
        layer = (Pane) ghost.node().getParent();
        ghost.drop();

        Circle dot = new Circle(Tokens.LIST_DOT_RADIUS);
        dot.setFill(Color.web("#7aa2c8"));
        Label name = new Label("probe [plan]");
        name.setFont(Font.font(Tokens.FONT_FAMILY, Tokens.FONT_LIST_ROW));
        name.setTextFill(Tokens.TEXT_INPUT);

        replica.setAlignment(Pos.CENTER_LEFT);
        replica.setPadding(new Insets(Tokens.LIST_ROW_PADDING_V, Tokens.LIST_ROW_PADDING_H,
                Tokens.LIST_ROW_PADDING_V, Tokens.LIST_ROW_PADDING_H));
        replica.getChildren().addAll(dot, name);
        replica.setPrefWidth(CARD_WIDTH);
        replica.setMinWidth(CARD_WIDTH);
        replica.getTransforms().add(swing);
        replica.setStyle(cardStyle);

        System.out.println("PROBE card style: " + cardStyle);
    }

    /**
     * Carries the replica around for one slice and adds the frame gaps to the config's pile.
     *
     * <p>The first frame of a slice is dropped: it carries the cost of adding the node and of
     * whatever style change the previous slice left behind, neither of which happens during a
     * real drag.
     */
    private void carry(Config config, long millis) {
        List<Long> frames = new ArrayList<>();
        double[] pointer = {WIDTH * 0.5};
        double[] step = {17};

        interact(() -> {
            replica.setStyle(config.shadow() ? cardStyle : withoutShadow(cardStyle));
            replica.setCache(config.hint() != null);
            replica.setCacheHint(config.hint() == null ? CacheHint.DEFAULT : config.hint());
            swing.setPivotX(30);
            swing.setPivotY(12);
            swing.setAngle(0);
            pendulum.reset(pointer[0]);

            baseX = 40;
            baseY = 300;
            replica.setLayoutX(baseX);
            replica.setLayoutY(baseY);
            replica.setTranslateX(0);
            replica.setTranslateY(0);
            if (!layer.getChildren().contains(replica)) {
                layer.getChildren().add(replica);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();

        AnimationTimer driver = new AnimationTimer() {
            private long previous;

            @Override
            public void handle(long now) {
                if (previous != 0) {
                    frames.add((now - previous) / 1_000_000);
                }
                previous = now;

                // A hand crossing the window and turning round at each edge, which is what
                // makes the swing keep working instead of settling to straight.
                pointer[0] += step[0];
                if (pointer[0] > WIDTH - CARD_WIDTH - 20 || pointer[0] < 20) {
                    step[0] = -step[0];
                    pointer[0] += step[0];
                }
                double y = 200 + 120 * Math.sin(pointer[0] / 140.0);

                swing.setAngle(pendulum.step(pointer[0], 1 / 60.0));
                if (config.translate()) {
                    replica.setTranslateX(pointer[0] - baseX);
                    replica.setTranslateY(y - baseY);
                } else {
                    replica.setLayoutX(pointer[0]);
                    replica.setLayoutY(y);
                }
            }
        };

        interact(driver::start);
        settle(millis);
        interact(driver::stop);
        interact(() -> layer.getChildren().remove(replica));
        WaitForAsyncUtils.waitForFxEvents();

        if (!frames.isEmpty()) {
            config.frames().addAll(frames.subList(1, frames.size()));
        }
    }

    /**
     * Carries the real {@link DragGhost} for one slice, driven exactly as the list drives it.
     *
     * <p>The replica exists to compare positioning modes fairly, which needs the swing turning in
     * all of them. This does the opposite job: it measures the object that actually ships, moving
     * the only way it can be moved, so the before-and-after in {@code DragGhost}'s own
     * documentation is about that object and not about a stand-in.
     *
     * @param cached whether to leave the card's cache alone (it ships switched on) or turn it off
     */
    private void carryTheRealGhost(List<Long> into, boolean cached, long millis) {
        DragGhost ghost = app.dragGhost();
        List<Long> frames = new ArrayList<>();
        double[] pointer = {WIDTH * 0.5};
        double[] step = {17};

        interact(() -> {
            ghost.lift("probe [plan]", Color.web("#7aa2c8"), CARD_WIDTH, 40, 300, 70, 312);
            ghost.node().setCache(cached);
        });
        WaitForAsyncUtils.waitForFxEvents();

        AnimationTimer driver = new AnimationTimer() {
            private long previous;

            @Override
            public void handle(long now) {
                if (previous != 0) {
                    frames.add((now - previous) / 1_000_000);
                }
                previous = now;

                pointer[0] += step[0];
                if (pointer[0] > WIDTH - CARD_WIDTH - 20 || pointer[0] < 20) {
                    step[0] = -step[0];
                    pointer[0] += step[0];
                }
                ghost.moveTo(pointer[0], 200 + 120 * Math.sin(pointer[0] / 140.0), false);
            }
        };

        interact(driver::start);
        settle(millis);
        interact(driver::stop);
        interact(ghost::drop);
        WaitForAsyncUtils.waitForFxEvents();

        if (!frames.isEmpty()) {
            into.addAll(frames.subList(1, frames.size()));
        }
    }

    /**
     * Times the layout pass alone, with rendering out of the picture.
     *
     * <p>Calling {@code root.layout()} by hand right after the position is set does exactly what
     * the pulse would have done next, so the difference between the two halves is the cost
     * {@code setLayoutX} adds and {@code setTranslateX} does not.
     */
    private void measureLayoutPass() {
        layer.getChildren().add(replica);
        scene.getRoot().layout();

        // Does either kind of move dirty anything at all? isNeedsLayout is JavaFX's own answer
        // and beats timing a pass that may be returning at CLEAN without doing any work.
        replica.setLayoutX(77);
        System.out.printf("PROBE after setLayoutX:    ghostLayer needsLayout %b, root needsLayout %b%n",
                layer.isNeedsLayout(), scene.getRoot().isNeedsLayout());
        scene.getRoot().layout();
        replica.setTranslateX(77);
        System.out.printf("PROBE after setTranslateX: ghostLayer needsLayout %b, root needsLayout %b%n",
                layer.isNeedsLayout(), scene.getRoot().isNeedsLayout());
        scene.getRoot().layout();

        int rounds = 2000;
        long viaLayout = 0;
        for (int i = 0; i < rounds; i++) {
            replica.setLayoutX(40 + (i % 200));
            replica.setLayoutY(300 + (i % 90));
            long t = System.nanoTime();
            scene.getRoot().layout();
            viaLayout += System.nanoTime() - t;
        }

        long viaTranslate = 0;
        for (int i = 0; i < rounds; i++) {
            replica.setTranslateX(i % 200);
            replica.setTranslateY(i % 90);
            long t = System.nanoTime();
            scene.getRoot().layout();
            viaTranslate += System.nanoTime() - t;
        }

        // The control. Nothing moved, so whatever this costs is the price of asking a clean
        // tree to lay itself out, and the other two are only meaningful above it.
        long viaNothing = 0;
        for (int i = 0; i < rounds; i++) {
            long t = System.nanoTime();
            scene.getRoot().layout();
            viaNothing += System.nanoTime() - t;
        }

        System.out.printf("PROBE setLayoutX then layout():    %7.2f us per move%n",
                viaLayout / 1000.0 / rounds);
        System.out.printf("PROBE setTranslateX then layout(): %7.2f us per move%n",
                viaTranslate / 1000.0 / rounds);
        System.out.printf("PROBE no move then layout():       %7.2f us per call (the floor)%n",
                viaNothing / 1000.0 / rounds);
        layer.getChildren().remove(replica);
    }

    /** The card's style with the {@code -fx-effect} declaration taken out and nothing else. */
    private static String withoutShadow(String style) {
        return style.replaceAll("-fx-effect:[^;]*;", "");
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    private static void report(String what, List<Long> frames) {
        if (frames.isEmpty()) {
            System.out.println("PROBE " + what + ": no frames");
            return;
        }
        List<Long> sorted = new ArrayList<>(frames);
        sorted.sort(null);
        long total = 0;
        long worst = 0;
        int over33 = 0;
        for (long f : frames) {
            total += f;
            worst = Math.max(worst, f);
            if (f > 33) {
                over33++;
            }
        }
        System.out.printf(
                "PROBE %-30s %4d frames, median %3d ms, 90th %3d ms, worst %4d ms, "
                        + "%3d below 30fps (%2.0f%%), mean %5.1f ms%n",
                what, frames.size(), sorted.get(sorted.size() / 2),
                sorted.get((int) (sorted.size() * 0.9)), worst, over33,
                100.0 * over33 / frames.size(), (double) total / frames.size());
    }
}
