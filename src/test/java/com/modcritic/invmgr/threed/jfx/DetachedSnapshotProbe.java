package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.threed.CameraPose;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * <b>A {@code SubScene} that has never been on screen snapshots blank.</b> This is the measurement
 * that says so, and it is kept because the failure it explains is invisible from every direction
 * except the pixels.
 *
 * <h2>What it cost</h2>
 *
 * <p>{@link ScaledSurface} draws the 3D room by snapshotting it into a smaller picture. The first
 * version handed that picture to {@code View3D} straight away, so {@code buildRoom} mounted the
 * ImageView and the room went into the scene graph <b>never</b>. The entire descent into a 200 ft
 * room showed the screen-wide vignette over nothing at all.
 *
 * <p><b>Nothing reported it.</b> No error, no warning. The 771-test suite passed with
 * {@code -Dinvmgr.renderscale=0.5} set, because no test looks at pixels while the camera is
 * moving. It was found by photographing the descent through {@code DescentProbe} and comparing
 * the frames against a run with the reduction off: mean step between neighboring pixels
 * <b>0.010 against 0.455</b>, which is the vignette and nothing else.
 *
 * <h2>What this probe measures</h2>
 *
 * <table border="1">
 *   <caption>The same room, the same snapshot call, three times</caption>
 *   <tr><th>when the snapshot is taken</th><th>detail in the picture</th></tr>
 *   <tr><td>the SubScene has never been mounted</td><td><b>0.0000</b>, one flat color</td></tr>
 *   <tr><td>after one frame on screen</td><td>0.3612</td></tr>
 *   <tr><td>mounted once, then taken back out</td><td>0.3612, it keeps working</td></tr>
 * </table>
 *
 * <p>So the requirement is <b>one frame on screen, ever</b>, and after that a detached SubScene
 * snapshots correctly for as long as it lives. The blank picture is uniformly the SubScene's own
 * fill ({@code 0x181818ff}, the canvas wrap background), which is what
 * {@code ScaledSurface.isOneFlatColor} looks for: it asks the picture whether the room is in it
 * rather than counting frames and hoping.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=DetachedSnapshotProbe}.
 */
class DetachedSnapshotProbe extends ApplicationTest {

    private static final int W = 600;
    private static final int H = 400;

    private final StackPane host = new StackPane();
    private final JfxRenderer3D renderer = new JfxRenderer3D();

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(host, W, H));
        stage.show();

        AppState state = new AppState();
        state.room.w = 40;
        state.room.l = 40;
        state.room.h = 10;
        renderer.init(W, H);
        renderer.build(state);
        renderer.render(new CameraPose(20, CameraPose.EYE_HEIGHT_FT, 36, 0, Math.toRadians(-8)));
        // DELIBERATELY NOT MOUNTED. That is the whole question.
    }

    @Test
    void neverMountedThenSnapshotted() {
        Image cold = take(0.25);
        System.out.printf("PROBE never mounted:            detail %.4f  corner %s  middle %s%n",
                detail(cold), cold.getPixelReader().getColor(0, 0),
                cold.getPixelReader().getColor((int) cold.getWidth() / 2,
                        (int) cold.getHeight() / 2));

        interact(() -> host.getChildren().setAll(renderer.node()));
        WaitForAsyncUtils.waitForFxEvents();
        Image warm = take(0.25);
        System.out.printf("PROBE after one frame mounted:  detail %.4f  corner %s  middle %s%n",
                detail(warm), warm.getPixelReader().getColor(0, 0),
                warm.getPixelReader().getColor((int) warm.getWidth() / 2,
                        (int) warm.getHeight() / 2));

        interact(() -> host.getChildren().clear());
        WaitForAsyncUtils.waitForFxEvents();
        System.out.printf("PROBE mounted once then removed: detail %.4f%n", detail(take(0.25)));
    }

    private Image take(double scale) {
        WritableImage buffer = new WritableImage((int) (W * scale), (int) (H * scale));
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(new Scale(scale, scale));
        params.setFill(Color.TRANSPARENT);
        Image[] out = new Image[1];
        interact(() -> out[0] = renderer.node().snapshot(params, buffer));
        WaitForAsyncUtils.waitForFxEvents();
        return out[0];
    }

    private static double detail(Image image) {
        PixelReader pixels = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        double total = 0;
        long counted = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w; x++) {
                Color one = pixels.getColor(x - 1, y);
                Color two = pixels.getColor(x, y);
                total += (Math.abs(one.getRed() - two.getRed())
                        + Math.abs(one.getGreen() - two.getGreen())
                        + Math.abs(one.getBlue() - two.getBlue())) / 3 * 255;
                counted++;
            }
        }
        return counted == 0 ? 0 : total / counted;
    }
}
