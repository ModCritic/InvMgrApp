package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.threed.CameraPose;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Photographs the biggest room the app allows from a sweep of camera spots, to show where a
 * wall's darkening loses to the grid under it.
 *
 * <p>Every wall and the floor are two quads in the same place: the grid, and the darkening
 * (the vignette) over it. When the depth buffer cannot tell the two apart, which one is drawn
 * is decided pixel by pixel and changes as the camera moves, so the wall flickers. A normal
 * photograph shows that only faintly, because the vignette is transparent in the middle of a
 * wall. So each spot is photographed three ways:
 *
 * <ul>
 *   <li>as the app draws it;
 *   <li>the same, nudged a hundredth of a foot forward, with a picture of what changed;
 *   <li>with every grid painted solid green and every vignette solid magenta, keeping the
 *       geometry and the depth settings exactly as the renderer built them. Any green pixel is a
 *       place the vignette lost.
 * </ul>
 *
 * <p>Pictures go to {@code target/screenshots/wall-flicker/}. Run it deliberately:
 * {@code mvn -B test -Dtest='WallFlickerProbe#sweep'}. {@link #cost} measures what drawing the
 * vignette without the depth test costs against the old way.
 */
class WallFlickerProbe extends ApplicationTest {

    private static final int VIEW_W = 1920;
    private static final int VIEW_H = 1080;

    private static final File OUT = new File("target/screenshots/wall-flicker");

    private Scene scene;

    @Override
    public void start(Stage stage) {
        scene = new Scene(new StackPane(), VIEW_W, VIEW_H);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    @Test
    void sweep() {
        AppState state = new AppState();
        state.room.w = 200;
        state.room.l = 200;
        state.room.h = 10;
        state.items.add(box("near-north", 100, 1, 48, 24, 60));
        state.items.add(box("middle", 98, 100, 36, 36, 36));

        // Spot name -> pose. x east, z south, y up, yaw 0 faces north, angles in degrees here.
        Map<String, double[]> spots = new LinkedHashMap<>();
        for (double d : new double[] {5, 10, 25, 50, 100, 150, 196}) {
            spots.put(String.format("north-face-on-%03.0fft", d), new double[] {100, 6, d, 0, 0});
        }
        for (double yaw : new double[] {20, 45, 70}) {
            spots.put(String.format("south-end-yaw%02.0f", yaw),
                    new double[] {100, 6, 196, yaw, 0});
        }
        spots.put("sw-corner-diagonal", new double[] {4, 6, 196, 45, 0});
        spots.put("outside-south-60ft", new double[] {100, 6, 260, 0, 0});
        spots.put("low-eye-floor", new double[] {100, 0.5, 196, 0, -5});
        spots.put("overhead-159ft", new double[] {100, 159, 100, 0, -88.8});
        spots.put("high-45deg", new double[] {100, 100, 260, 0, -30});

        JfxRenderer3D renderer = new JfxRenderer3D();
        interact(() -> {
            renderer.init(VIEW_W, VIEW_H);
            renderer.build(state);
            ((StackPane) scene.getRoot()).getChildren().setAll(renderer.node());
        });

        OUT.mkdirs();
        System.out.println("PROBE spot | green px (vignette lost) | magenta px | changed px on a"
                + " 0.01 ft nudge");
        for (Map.Entry<String, double[]> spot : spots.entrySet()) {
            double[] p = spot.getValue();
            CameraPose pose = new CameraPose(p[0], p[1], p[2], Math.toRadians(p[3]),
                    Math.toRadians(p[4]));
            CameraPose nudged = new CameraPose(p[0] - 0.01 * Math.sin(pose.yaw), p[1],
                    p[2] - 0.01 * Math.cos(pose.yaw), pose.yaw, pose.pitch);

            WritableImage real = shoot(renderer, pose);
            WritableImage next = shoot(renderer, nudged);
            write(real, spot.getKey() + "-real");
            WritableImage diff = difference(real, next);
            write(diff, spot.getKey() + "-nudge-diff");
            long changed = countChanged(real, next);

            Map<Node, Material> saved = paintFlat(renderer);
            WritableImage flat = shoot(renderer, pose);
            restore(saved);
            write(flat, spot.getKey() + "-flat");
            long[] gm = greenAndMagenta(flat);

            System.out.printf("PROBE %-22s | %8d | %8d | %8d%n", spot.getKey(), gm[0], gm[1],
                    changed);
        }
        interact(renderer::dispose);
    }

    /**
     * What drawing the vignettes without the depth test costs, against the old way.
     *
     * <p>Three arms, all made from one renderer's own nodes so nothing else differs: the old
     * arrangement (each vignette moved 0.01 ft into the room, depth test on), each vignette
     * straight after its grid with the test off (ten switches of the test a frame), and every
     * grid first, then every vignette (two switches). Round robin in short slices with the
     * camera turning, because a run of one arm after another drifts by more than this measures.
     * Only meaningful with {@code -Djavafx.animation.fullspeed=true}, or every frame reads as
     * the 60 a second cap.
     */
    @Test
    void cost() {
        AppState state = new AppState();
        state.room.w = 200;
        state.room.l = 200;
        state.room.h = 10;
        JfxRenderer3D renderer = new JfxRenderer3D();
        interact(() -> {
            renderer.init(VIEW_W, VIEW_H);
            renderer.build(state);
            ((StackPane) scene.getRoot()).getChildren().setAll(renderer.node());
        });
        Group world = (Group) renderer.node().getRoot();
        List<Node> asBuilt = new ArrayList<>(world.getChildren());

        String[] arms = {"old: lifted, test on", "pairs, test off", "grouped, test off"};
        List<List<Long>> frames = new ArrayList<>();
        for (int a = 0; a < arms.length; a++) {
            frames.add(new ArrayList<>());
        }
        for (int slice = 0; slice < 8; slice++) {
            for (int a = 0; a < arms.length; a++) {
                int arm = a;
                interact(() -> arrange(world, asBuilt, arm, state));
                frames.get(arm).addAll(turnFor(renderer, 600));
            }
        }
        interact(() -> arrange(world, asBuilt, 1, state));
        for (int a = 0; a < arms.length; a++) {
            List<Long> sorted = new ArrayList<>(frames.get(a));
            sorted.sort(null);
            System.out.printf("PROBE COST %-22s median %.2f ms, p90 %.2f ms, mean %.2f ms, "
                            + "%d frames%n", arms[a],
                    sorted.get(sorted.size() / 2) / 1e6, sorted.get(sorted.size() * 9 / 10) / 1e6,
                    sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6,
                    sorted.size());
        }
        interact(renderer::dispose);
    }

    /** Puts the renderer's own nodes in one of the three arrangements {@link #cost} measures. */
    private static void arrange(Group world, List<Node> asBuilt, int arm, AppState state) {
        List<Node> grids = new ArrayList<>();
        List<Node> vignettes = new ArrayList<>();
        List<Node> rest = new ArrayList<>();
        for (Node node : asBuilt) {
            String id = node.getId();
            if (id != null && id.endsWith("-vignette")) {
                vignettes.add(node);
            } else if (id != null && id.startsWith("room-")) {
                grids.add(node);
            } else {
                rest.add(node);
            }
        }
        List<Node> order = new ArrayList<>();
        if (arm == 2) {
            order.addAll(grids);
            order.addAll(vignettes);
        } else {
            for (int i = 0; i < grids.size(); i++) {
                order.add(grids.get(i));
                order.add(vignettes.get(i));
            }
        }
        for (Node vignette : vignettes) {
            boolean old = arm == 0;
            vignette.setDepthTest(old ? DepthTest.INHERIT : DepthTest.DISABLE);
            double[] lift = old ? liftOf(vignette.getId(), state) : new double[3];
            vignette.setTranslateX(lift[0]);
            vignette.setTranslateY(lift[1]);
            vignette.setTranslateZ(lift[2]);
        }
        // The light and the boxes keep their places around the room's layers.
        List<Node> all = new ArrayList<>();
        all.add(rest.get(0));
        all.addAll(order);
        all.addAll(rest.subList(1, rest.size()));
        world.getChildren().setAll(all);
    }

    /** The old 0.01 ft move into the room, in JavaFX axes (y and z are negated there). */
    private static double[] liftOf(String vignetteId, AppState state) {
        double d = 0.01;
        switch (vignetteId) {
            case "room-floor-vignette": return new double[] {0, -d, 0};
            case "room-north-vignette": return new double[] {0, 0, -d};
            case "room-south-vignette": return new double[] {0, 0, d};
            case "room-west-vignette": return new double[] {d, 0, 0};
            case "room-east-vignette": return new double[] {-d, 0, 0};
            default: throw new IllegalArgumentException(vignetteId);
        }
    }

    /** Turns the camera back and forth at the south end for a while; returns each frame's time. */
    private List<Long> turnFor(JfxRenderer3D renderer, long millis) {
        List<Long> frames = new ArrayList<>();
        AnimationTimer clock = new AnimationTimer() {
            private long previous;
            private double angle;

            @Override
            public void handle(long now) {
                if (previous != 0) {
                    frames.add(now - previous);
                }
                previous = now;
                angle += 0.4;
                renderer.render(new CameraPose(100, 6, 196,
                        Math.toRadians(Math.sin(Math.toRadians(angle)) * 35), 0));
            }
        };
        interact(clock::start);
        settle(millis);
        interact(clock::stop);
        return frames.size() > 2 ? frames.subList(2, frames.size()) : frames;
    }

    private WritableImage shoot(JfxRenderer3D renderer, CameraPose pose) {
        interact(() -> renderer.render(pose));
        settle(250);
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
    }

    /** Paints every grid quad green and every vignette quad magenta; returns what to put back. */
    private Map<Node, Material> paintFlat(JfxRenderer3D renderer) {
        Map<Node, Material> saved = new LinkedHashMap<>();
        interact(() -> {
            for (Node node : ((Group) renderer.node().getRoot()).getChildren()) {
                String id = node.getId();
                if (!(node instanceof MeshView) || id == null || !id.startsWith("room-")) {
                    continue;
                }
                MeshView view = (MeshView) node;
                saved.put(view, view.getMaterial());
                view.setMaterial(new PhongMaterial(
                        id.endsWith("-vignette") ? Color.MAGENTA : Color.LIME));
            }
        });
        return saved;
    }

    private void restore(Map<Node, Material> saved) {
        interact(() -> saved.forEach((node, material) -> ((MeshView) node).setMaterial(material)));
    }

    private static long[] greenAndMagenta(WritableImage shot) {
        long green = 0;
        long magenta = 0;
        for (int y = 0; y < (int) shot.getHeight(); y++) {
            for (int x = 0; x < (int) shot.getWidth(); x++) {
                Color c = shot.getPixelReader().getColor(x, y);
                if (c.getGreen() > 0.5 && c.getRed() < 0.3 && c.getBlue() < 0.3) {
                    green++;
                } else if (c.getRed() > 0.5 && c.getBlue() > 0.5 && c.getGreen() < 0.3) {
                    magenta++;
                }
            }
        }
        return new long[] {green, magenta};
    }

    private static long countChanged(WritableImage a, WritableImage b) {
        long changed = 0;
        for (int y = 0; y < (int) a.getHeight(); y++) {
            for (int x = 0; x < (int) a.getWidth(); x++) {
                if (delta(a.getPixelReader().getColor(x, y), b.getPixelReader().getColor(x, y))
                        > 8) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** White where the two frames differ by more than 8 of 255, black elsewhere. */
    private static WritableImage difference(WritableImage a, WritableImage b) {
        int w = (int) a.getWidth();
        int h = (int) a.getHeight();
        WritableImage out = new WritableImage(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean changed = delta(a.getPixelReader().getColor(x, y),
                        b.getPixelReader().getColor(x, y)) > 8;
                out.getPixelWriter().setColor(x, y, changed ? Color.WHITE : Color.BLACK);
            }
        }
        return out;
    }

    private static double delta(Color a, Color b) {
        return Math.max(Math.abs(a.getRed() - b.getRed()),
                Math.max(Math.abs(a.getGreen() - b.getGreen()),
                        Math.abs(a.getBlue() - b.getBlue()))) * 255;
    }

    private static Item box(String id, double xFt, double zFt, double wIn, double lIn,
            double hIn) {
        Item item = new Item();
        item.id = id;
        item.serial = id.hashCode() & 0xffff;
        item.x_px = xFt * 96;
        item.y_px = zFt * 96;
        item.w_in = wIn;
        item.l_in = lIn;
        item.h_in = hIn;
        item.color = "hsl(207,55%,42%)";
        return item;
    }

    private static void write(WritableImage image, String name) {
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                    new File(OUT, name + ".png"));
        } catch (IOException e) {
            System.err.println("could not write screenshot " + name + ": " + e.getMessage());
        }
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }
}
