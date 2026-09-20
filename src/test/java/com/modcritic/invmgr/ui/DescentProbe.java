package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.io.File;
import java.io.IOException;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A throwaway screenshot probe, in the manner of {@code ScaleProbe} and {@code MetricProbe}: not
 * a test, and it asserts nothing.
 *
 * <p>It presses the 3D button and photographs the window every 120 ms through the whole journey
 * in and back out, writing the frames to {@code target/screenshots/descent/}. It exists because
 * <b>no test can look at a movement.</b> {@code TransitionsTest} pins every number about the
 * descent and would stay green through an animation that jumped, stuttered, ran backwards, or
 * drew the room upside down; the numbers are right and the picture is separate. So the numbers get
 * tests, and the picture gets a human; this just makes the frames to hand them.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=DescentProbe}.
 */
class DescentProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;
    private static final int EVERY_MS = 120;
    private static final int FRAMES_IN = 30;
    private static final int FRAMES_OUT = 22;

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
    void photographTheWholeJourney() {
        interact(() -> {
            // Deliberately NOT a whole number of feet in either direction. A 12 x 10 room hides
            // the grid-alignment bug the user reported on 2026-08-05 completely, because there is
            // no leftover part of a foot for the mirrored walls to put at the wrong end.
            app.state().room.w = 12.5;
            app.state().room.l = 10.5;
            app.state().items.add(box("a", 1, 2 * 96, 1 * 96, 30, "hsl(207,55%,42%)"));
            app.state().items.add(box("b", 2, 8 * 96, 2 * 96, 48, "hsl(28,55%,45%)"));
            app.state().items.add(box("c", 3, 5 * 96, 1 * 96, 18, "hsl(120,45%,40%)"));
        });
        WaitForAsyncUtils.waitForFxEvents();

        shoot("00-before");

        // Sampled against the WALL CLOCK, not against a frame counter. Taking a snapshot costs
        // roughly 290 ms in this container, so "wait 120 ms, shoot, repeat" actually walks
        // forward in 400 ms steps and lands almost every frame after the descent has already
        // finished, which is how the first run of this probe produced twenty identical pictures
        // of the far wall and looked like a bug.
        long pressedAt = System.currentTimeMillis();
        interact(() -> app.topBar().threeDButtonNode().fire());
        for (int frame = 1; frame <= FRAMES_IN; frame++) {
            waitUntil(pressedAt + (long) frame * EVERY_MS);
            shoot(String.format("in-%02d", frame));
        }

        // The landing pose looks level from six feet up, so a short box two feet from your shoes
        // is below the bottom of the screen; correct, and it makes for a useless photograph.
        // One frame tipped down, purely so a human can see the room is still furnished.
        interact(() -> {
            app.view3d().camera().pitch = -0.45;
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();
        shoot("landed-looking-down");

        // Standing back in the south-west corner looking at where the north and east walls meet.
        // This is the frame that shows the grid-alignment fix: in a 12.5 x 10.5 ft room the north
        // wall's lines and the east wall's have to arrive at the same heights and meet the floor
        // grid squarely, even though each wall has half a foot left over. Before the fix, the
        // mirrored pair put their leftover at the wrong end and every line was half a foot out.
        interact(() -> {
            app.view3d().camera().x = 1;
            app.view3d().camera().z = 9.5;
            app.view3d().camera().y = 5;
            app.view3d().camera().yaw = Math.toRadians(35);
            app.view3d().camera().pitch = -0.15;
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();
        shoot("corner-grid-alignment");

        interact(() -> {
            app.view3d().camera().pitch = 0;
            app.view3d().render();
        });

        long leftAt = System.currentTimeMillis();
        interact(() -> app.view3d().returnButton().fire());
        for (int frame = 1; frame <= FRAMES_OUT; frame++) {
            waitUntil(leftAt + (long) frame * EVERY_MS);
            shoot(String.format("out-%02d", frame));
        }
        System.out.println("DescentProbe: frames in target/screenshots/descent/");
    }

    private void waitUntil(long wallClockMs) {
        while (System.currentTimeMillis() < wallClockMs) {
            WaitForAsyncUtils.sleep(5, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void shoot(String name) {
        WritableImage image = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
        try {
            File directory = new File("target/screenshots/descent");
            if (directory.isDirectory() || directory.mkdirs()) {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File(directory, name + ".png"));
            }
        } catch (IOException e) {
            System.err.println("could not write " + name + ": " + e.getMessage());
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
        item.w_in = 24;
        item.l_in = 24;
        item.h_in = heightIn;
        item.color = color;
        return item;
    }
}
