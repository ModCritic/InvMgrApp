package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Photographs the same 3D room from the same places, so two builds of the app can be held side by
 * side.
 *
 * <p>Written for M6.7b's color atlas, at the user's request: they asked to see the pictures before
 * the change landed rather than to be told it was identical. A pixel comparison is in here too, but
 * <b>the pictures are the deliverable</b> and the number beside them only says where to look.
 *
 * <p>Run it once on each build, under a different label:
 *
 * <pre>
 *   DISPLAY=:99 mvn -B test -Dtest=RoomPicturesProbe -Dinvmgr.pictures=before
 *   DISPLAY=:99 mvn -B test -Dtest=RoomPicturesProbe -Dinvmgr.pictures=after
 *   python3 tools/perf/comparepics.py before after
 * </pre>
 *
 * <p><b>Everything about the room is fixed, including the colors.</b> {@code Items.randomColor}
 * gives a new item a random hue, so a room built the ordinary way would photograph differently
 * every run and the comparison would be worthless. These colors are written out.
 */
class RoomPicturesProbe extends ApplicationTest {

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;

    /** Where the camera stands and looks, one row per picture: yaw, pitch, x, z, eye height. */
    private static final double[][] POSES = {
        {0, 0, 0, 0, 0},
        {35, -8, 2, 2, 0},
        {110, 4, -3, 1, 0},
        {195, -14, 1, -2, 0},
        {280, 10, -2, -3, 0},
        {330, -3, 3, 3, 0},
    };

    /** Eight colors, chosen to span the hue circle so a row mix-up would be obvious. */
    private static final String[] COLORS = {
        "hsl(0,55%,42%)", "hsl(45,55%,42%)", "hsl(90,55%,42%)", "hsl(135,55%,42%)",
        "hsl(180,55%,42%)", "hsl(225,55%,42%)", "hsl(270,55%,42%)", "hsl(315,55%,42%)",
    };

    private App app;
    private Scene scene;

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

    @Test
    void photographTheRoomFromSixPlaces() {
        String label = System.getProperty("invmgr.pictures", "unlabeled");
        Path out = Path.of("target", "pictures", label);
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException("could not make " + out, e);
        }

        interact(() -> {
            // Not a whole number of feet either way, for the same reason DescentProbe is not: a
            // 12 x 10 room hides every grid-alignment question because there is no leftover part
            // of a foot for the mirrored walls to place.
            app.state().room.w = 12.5;
            app.state().room.l = 10.5;
            app.state().room.h = 8;
            app.state().items.clear();
            for (int i = 0; i < COLORS.length; i++) {
                app.state().items.add(box("p" + i, i + 1,
                        (1 + (i % 4) * 2.6) * 96, (1 + (i / 4) * 3.1) * 96,
                        14 + i * 5, COLORS[i]));
            }
            app.canvas().rebuildItems();
        });
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> app.view3d().open(app.state(), scene));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(700, TimeUnit.MILLISECONDS);

        for (int i = 0; i < POSES.length; i++) {
            double[] pose = POSES[i];
            interact(() -> {
                app.view3d().camera().yaw = Math.toRadians(pose[0]);
                app.view3d().camera().pitch = Math.toRadians(pose[1]);
                app.view3d().camera().x = pose[2];
                app.view3d().camera().z = pose[3];
                app.view3d().render();
            });
            WaitForAsyncUtils.waitForFxEvents();
            WaitForAsyncUtils.sleep(150, TimeUnit.MILLISECONDS);
            write(out.resolve(String.format("pose-%d.png", i)));
        }
        System.out.println("PROBE wrote " + POSES.length + " pictures to " + out.toAbsolutePath());
    }

    private void write(Path path) {
        WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(30000, () -> scene.snapshot(null));
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(shot, null), "png", new File(path.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + path, e);
        }
    }

    private static Item box(String id, int serial, double xpx, double ypx, double heightIn,
            String color) {
        Item item = new Item();
        item.id = id;
        item.serial = serial;
        item.dragOrder = serial;
        item.x_px = xpx;
        item.y_px = ypx;
        item.w_in = 26;
        item.l_in = 22;
        item.h_in = heightIn;
        item.color = color;
        return item;
    }
}
