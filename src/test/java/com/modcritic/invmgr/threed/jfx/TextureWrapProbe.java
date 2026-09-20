package com.modcritic.invmgr.threed.jfx;

import java.io.File;
import java.io.IOException;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A throwaway probe that asks JavaFX one question it will not answer in writing: <b>what happens to
 * a texture coordinate greater than 1 on a 3D mesh?</b>
 *
 * <p>It matters because the room's grid is periodic. If texture coordinates repeat, one small tile
 * of grid drawn at full resolution can cover a room of any size, and the blur that
 * {@code SurfaceMetrics.MAX_TEXTURE_PX} causes in a large room simply stops existing. If they
 * clamp, that whole approach is dead and the fix has to be geometry or chunks instead.
 *
 * <p>The answer is not in the public documentation, and reading Prism's bytecode only got as far
 * as {@code NGPhongMaterial} handing a {@code TextureMap} down to a pipeline-specific class. This
 * renders the actual thing and counts the stripes, which settles it in one run.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=TextureWrapProbe}.
 */
class TextureWrapProbe extends ApplicationTest {

    private static final int VIEW = 600;

    /** How many times the texture is asked to repeat across the quad. */
    private static final int REPEATS = 4;

    private Scene scene;
    private final Group world = new Group();

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        SubScene sub = new SubScene(world, VIEW, VIEW, true, SceneAntialiasing.DISABLED);
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-560);
        camera.setNearClip(1);
        camera.setFarClip(4000);
        sub.setCamera(camera);
        sub.setFill(Color.BLACK);
        root.getChildren().add(sub);

        scene = new Scene(root, VIEW, VIEW);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    @Test
    void doTextureCoordinatesAboveOneRepeat() {
        // Without a light a PhongMaterial renders black, which the first run of this probe
        // reported as "0 red bands" and very nearly read as an answer to the question.
        interact(() -> {
            world.getChildren().add(new javafx.scene.AmbientLight(Color.WHITE));
            world.getChildren().add(quad(cellTexture(), REPEATS));
        });
        WaitForAsyncUtils.waitForFxEvents();
        settle(300);

        WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
        write(shot, "texture-wrap");

        // The texture is gray with one red line down its left edge, so the number of red bands
        // across the middle of the quad IS the number of times the texture was laid down.
        int bands = redBandsAcrossTheMiddle(shot);
        System.out.printf("PROBE texture coordinates 0 to %d produced %d red bands%n",
                REPEATS, bands);
        System.out.println(bands >= REPEATS
                ? "PROBE ==> texture coordinates REPEAT. A tiled grid is possible."
                : "PROBE ==> texture coordinates CLAMP or stretch. A tiled grid is NOT possible.");
    }

    /** Gray with a red stripe down its left edge and a green one across its top. */
    private static Image cellTexture() {
        int size = 64;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.rgb(60, 60, 60));
        g.fillRect(0, 0, size, size);
        g.setFill(Color.RED);
        g.fillRect(0, 0, 4, size);
        g.setFill(Color.LIME);
        g.fillRect(0, 0, size, 4);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, null);
    }

    /** A flat quad facing the camera, asking for the texture {@code repeats} times each way. */
    private static MeshView quad(Image texture, int repeats) {
        float half = 250;
        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(
                -half, -half, 0,
                half, -half, 0,
                half, half, 0,
                -half, half, 0);
        mesh.getTexCoords().setAll(
                0, 0,
                repeats, 0,
                repeats, repeats,
                0, repeats);
        mesh.getFaces().setAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);

        MeshView view = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseMap(texture);
        view.setMaterial(material);
        view.setCullFace(CullFace.NONE);
        return view;
    }

    /** Counts runs of reddish pixels along the horizontal center line of the shot. */
    private static int redBandsAcrossTheMiddle(WritableImage shot) {
        PixelReader pixels = shot.getPixelReader();
        int y = (int) shot.getHeight() / 2;
        int bands = 0;
        boolean inBand = false;
        for (int x = 0; x < (int) shot.getWidth(); x++) {
            Color c = pixels.getColor(x, y);
            boolean red = c.getRed() > 0.4 && c.getGreen() < 0.3 && c.getBlue() < 0.3;
            if (red && !inBand) {
                bands++;
            }
            inBand = red;
        }
        return bands;
    }

    private static void write(WritableImage image, String name) {
        try {
            File directory = new File("target/screenshots");
            if (directory.isDirectory() || directory.mkdirs()) {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File(directory, name + ".png"));
            }
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
