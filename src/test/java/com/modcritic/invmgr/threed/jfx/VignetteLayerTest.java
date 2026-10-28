package com.modcritic.invmgr.threed.jfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.threed.CameraPose;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The darkening over each wall and the floor (the vignette) has to win over the grid under it at
 * every pixel, from any distance and any angle, and must never cover a box.
 *
 * <p>Each surface is two quads in the same place. They used to be kept apart by floating the
 * vignette an eighth of an inch nearer the room, and in a 200 ft room the depth buffer could not
 * resolve that gap: from about 100 ft away the grid won patches and stripes of whole walls, and
 * which patches changed as the camera moved, so the walls flickered. The renderer now draws the
 * vignette with the depth test off instead.
 *
 * <p>The pixel check paints every grid quad solid green and every vignette quad solid magenta,
 * leaving the geometry, the order and the depth settings exactly as the renderer built them, so a
 * single green pixel anywhere is a place the vignette lost. The camera spots are the ones where
 * the old arrangement lost the most, measured by {@code WallFlickerProbe}.
 */
class VignetteLayerTest extends ApplicationTest {

    private static final int VIEW_W = 1280;
    private static final int VIEW_H = 720;

    private Scene scene;
    private JfxRenderer3D renderer;

    @Override
    public void start(Stage stage) {
        scene = new Scene(new StackPane(), VIEW_W, VIEW_H);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        if (renderer != null) {
            interact(renderer::dispose);
        }
    }

    @Test
    @DisplayName("in the biggest room, the vignette wins over its grid at every pixel")
    void theVignetteNeverLosesToItsGrid() {
        buildRoom(List.of());
        paintFlat();

        // x east, z south, y up, yaw 0 faces north; angles in degrees. Measured on the old
        // arrangement at 1280 x 720 with Mesa's software renderer (2026-10-26), the green count
        // was 17,505 at the first spot, 15,942 at the second, 1,637 overhead and 37 from outside.
        // The corner and the 150 ft spots lost nothing at this size and thousands at 1920 x 1080,
        // so they stay: where the losses land depends on the screen and the graphics card.
        //
        // This check alone cannot tell the fix from simply laying the two quads in exactly the
        // same plane with the depth test on, which also passes here, because identical corners
        // give identical depths and JavaFX's depth test lets a tie through. That relies on two
        // draws producing bit-identical depth on every graphics card, which nothing promises, so
        // the depth setting itself is pinned in theOrderAndTheDepthTestAreAsTheFixNeeds.
        double[][] spots = {
            {100, 6, 196, 20, 0},       // south end, looking a little east of north
            {100, 6, 196, 45, 0},       // south end, looking north-east
            {4, 6, 196, 45, 0},         // south-west corner, looking along the diagonal
            {100, 6, 150, 0, 0},        // the north wall face-on from 150 ft
            {100, 159, 100, 0, -88.8},  // the overhead view, straight down at the floor
            {100, 100, 260, 0, -30},    // outside the room, high, looking in
        };
        List<String> lost = new ArrayList<>();
        for (double[] p : spots) {
            WritableImage shot = shoot(new CameraPose(p[0], p[1], p[2], Math.toRadians(p[3]),
                    Math.toRadians(p[4])));
            long green = count(shot, VignetteLayerTest::isGreen);
            long magenta = count(shot, VignetteLayerTest::isMagenta);
            // A frame that drew no room at all has no green either, so ask for plenty of
            // magenta too. Every one of these spots has the room filling a good part of the view.
            assertTrue(magenta > (long) VIEW_W * VIEW_H / 10, String.format(
                    "the spot %s drew almost no room (%d magenta pixels), so it proves nothing",
                    java.util.Arrays.toString(p), magenta));
            if (green > 0) {
                lost.add(String.format("%s: %d pixels", java.util.Arrays.toString(p), green));
            }
        }
        assertTrue(lost.isEmpty(), "the grid showed through its vignette, which is the wall "
                + "flicker in a big room: " + lost);
    }

    @Test
    @DisplayName("a box in front of a wall is never painted over by that wall's vignette")
    void theVignetteNeverCoversABox() {
        // The vignette skips the depth test, so the one thing standing between it and the boxes
        // is the order things are drawn in: surfaces first. Draw a box first and the vignette
        // behind it paints straight over it.
        buildRoom(List.of(box(100, 2, 48, 24, 60)));
        paintFlat();
        CameraPose facingTheBox = new CameraPose(100.2, 4, 12, 0, Math.toRadians(-10));

        long withVignettes = count(shoot(facingTheBox), VignetteLayerTest::isBoxBlue);
        setVignettesVisible(false);
        long withoutVignettes = count(shoot(facingTheBox), VignetteLayerTest::isBoxBlue);
        setVignettesVisible(true);

        assertTrue(withoutVignettes > 1000,
                "the box should fill a good patch of the view, got " + withoutVignettes);
        assertEquals(withoutVignettes, withVignettes,
                "the vignettes covered part of the box in front of them");
    }

    @Test
    @DisplayName("each vignette skips the depth test, follows its own grid, and every surface "
            + "comes before any box")
    void theOrderAndTheDepthTestAreAsTheFixNeeds() {
        buildRoom(List.of(box(100, 2, 48, 24, 60), box(50, 50, 24, 24, 24)));

        List<Node> children = new ArrayList<>(((Group) renderer.node().getRoot()).getChildren());
        int lastSurface = -1;
        int firstBox = Integer.MAX_VALUE;
        int vignettes = 0;
        for (int i = 0; i < children.size(); i++) {
            String id = children.get(i).getId();
            if (id == null) {
                continue;
            }
            if (!id.startsWith("room-")) {
                firstBox = Math.min(firstBox, i);
                continue;
            }
            lastSurface = i;
            if (id.endsWith("-vignette")) {
                vignettes++;
                assertEquals(DepthTest.DISABLE, children.get(i).getDepthTest(),
                        id + " must be drawn without the depth test, or it flickers far away");
                String grid = id.substring(0, id.length() - "-vignette".length());
                assertEquals(grid, children.get(i - 1).getId(),
                        id + " must come straight after its own grid, or the grid covers it");
            } else {
                assertTrue(children.get(i).getDepthTest() != DepthTest.DISABLE,
                        id + " is a grid and needs the depth test, or boxes behind it show");
            }
        }
        assertEquals(5, vignettes, "one vignette for each of the five surfaces");
        assertTrue(lastSurface < firstBox,
                "every surface must be drawn before any box, or a vignette paints over a box");
    }

    private void buildRoom(List<Item> items) {
        AppState state = new AppState();
        state.room.w = 200;
        state.room.l = 200;
        state.room.h = 10;
        state.items.addAll(items);
        renderer = new JfxRenderer3D();
        interact(() -> {
            renderer.init(VIEW_W, VIEW_H);
            renderer.build(state);
            ((StackPane) scene.getRoot()).getChildren().setAll(renderer.node());
        });
    }

    /** Grid quads solid green, vignette quads solid magenta, boxes untouched. */
    private void paintFlat() {
        interact(() -> {
            for (Node node : ((Group) renderer.node().getRoot()).getChildren()) {
                String id = node.getId();
                if (node instanceof MeshView && id != null && id.startsWith("room-")) {
                    ((MeshView) node).setMaterial(new PhongMaterial(
                            id.endsWith("-vignette") ? Color.MAGENTA : Color.LIME));
                }
            }
        });
    }

    private void setVignettesVisible(boolean visible) {
        interact(() -> {
            for (Node node : ((Group) renderer.node().getRoot()).getChildren()) {
                if (node.getId() != null && node.getId().endsWith("-vignette")) {
                    node.setVisible(visible);
                }
            }
        });
    }

    private WritableImage shoot(CameraPose pose) {
        interact(() -> renderer.render(pose));
        WaitForAsyncUtils.waitForFxEvents();
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
    }

    private static long count(WritableImage shot, java.util.function.Predicate<Color> which) {
        long n = 0;
        for (int y = 0; y < (int) shot.getHeight(); y++) {
            for (int x = 0; x < (int) shot.getWidth(); x++) {
                if (which.test(shot.getPixelReader().getColor(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean isGreen(Color c) {
        return c.getGreen() > 0.5 && c.getRed() < 0.3 && c.getBlue() < 0.3;
    }

    private static boolean isMagenta(Color c) {
        return c.getRed() > 0.5 && c.getBlue() > 0.5 && c.getGreen() < 0.3;
    }

    /** The box's blue, in any of its five shades, and not the magenta that also has blue in it. */
    private static boolean isBoxBlue(Color c) {
        return c.getBlue() - c.getRed() > 0.15 && c.getGreen() < 0.9;
    }

    private static Item box(double xFt, double zFt, double wIn, double lIn, double hIn) {
        Item item = new Item();
        item.id = "box-" + xFt + "-" + zFt;
        item.serial = 1;
        item.x_px = xFt * 96;
        item.y_px = zFt * 96;
        item.w_in = wIn;
        item.l_in = lIn;
        item.h_in = hIn;
        item.color = "hsl(207,55%,42%)";
        return item;
    }
}
