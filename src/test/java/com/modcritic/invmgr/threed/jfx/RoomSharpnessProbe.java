package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.GridTile;
import com.modcritic.invmgr.threed.RoomGeometry;
import com.modcritic.invmgr.threed.SurfaceMetrics;
import com.modcritic.invmgr.ui.Tokens;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A throwaway probe that puts the room's two grids side by side and photographs them: the capped
 * picture the app used to paint, reconstructed, and the one-foot tile it repeats now.
 *
 * <p><b>It is kept rather than deleted because it is the evidence for M6.7 item 1</b>, and the old
 * painter is gone from the source, so the before-and-after can only be re-run if something still
 * knows how to draw it. {@link #shippedQuad} is that reconstruction, clamp included.
 *
 * <p>The question it settled is not whether tiling is sharper, which is arithmetic. It is
 * <b>whether tiling trades blur for shimmer</b>. The capped picture in a 200 ft room is 9.4 times
 * under-sampled, which is ugly close up but also means it is already smoothed; a full-resolution
 * grid seen from across the same room is thinner than a pixel, and if nothing is filtering it, it
 * will crawl and sparkle as the camera moves. That would be a worse room, not a better one, and it
 * is not something that can be reasoned out. Both are rendered from the same camera and both are
 * written to {@code target/screenshots/}. The answer was that the sharp one is six times steadier.
 *
 * <p>It also asks what each costs, whether a small offset between coplanar layers survives the
 * depth buffer at room distances (the vignette needs a second quad and the far clip is 2000 ft),
 * and photographs the shipped renderer itself, which is where the D-25 crease shot comes from.
 *
 * <p>Run it deliberately: {@code mvn -B test -Dtest=RoomSharpnessProbe}.
 */
class RoomSharpnessProbe extends ApplicationTest {

    private static final int VIEW_W = 1200;
    private static final int VIEW_H = 800;

    /** How long one slice of one way of drawing the room is measured for. */
    private static final long SLICE_MS = 600;

    /** How many slices each way gets, interleaved with the others. */
    private static final int SLICES = 6;

    private static final double NEAR_CLIP_FT = 0.05;
    private static final double FAR_CLIP_FT = 2000;
    private static final double EYE_HEIGHT_FT = 6;

    /**
     * How far from the far wall the camera stands, in feet.
     *
     * <p>Standing near one end and looking at the other is the whole point: the first version of
     * this probe stood a third of the way in and looked at the wall 33 ft behind it, so it
     * measured a 200 ft room at 33 ft and found no difference in anything.
     */
    private static final double STAND_BACK_FT = 4;

    /** The vignette cap that shipped before M6.7, which the historical arm is frozen at. */
    private static final double OLD_CAP_PX = 2048;

    private Scene scene;
    private final Group world = new Group();
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Translate position = new Translate();
    private final Rotate yaw = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(0, Rotate.X_AXIS);

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        SubScene sub = new SubScene(world, VIEW_W, VIEW_H, true, SceneAntialiasing.DISABLED);
        camera.setVerticalFieldOfView(true);
        camera.setFieldOfView(45);
        camera.setNearClip(NEAR_CLIP_FT);
        camera.setFarClip(FAR_CLIP_FT);
        camera.getTransforms().setAll(position, yaw, pitch);
        sub.setCamera(camera);
        sub.setFill(Tokens.CANVAS_WRAP_BG);
        root.getChildren().add(sub);
        world.getChildren().add(new AmbientLight(Color.WHITE));

        scene = new Scene(root, VIEW_W, VIEW_H);
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    @Test
    @DisplayName("the shipped renderer, photographed in a big room and a small one")
    void photographTheRealThing() {
        // The comparison below builds its quads by hand so the two grids can be put side by side.
        // This one drives JfxRenderer3D itself, so what comes out is what the app draws: two quads
        // per surface, the vignette over the grid, the room's boundary lines and all.
        // The third is the D-25 shot: standing back in the middle of a room, looking at the
        // crease where the floor meets a wall, which is where the one line the original does not
        // draw has to land. Whether it can be seen there is not a thing to reason about.
        for (double[] room : new double[][] {{200, 200, 10}, {12.5, 10.5, 8}, {12, 10, 8}}) {
            JfxRenderer3D renderer = new JfxRenderer3D();
            AppState state = new AppState();
            state.room.w = room[0];
            state.room.l = room[1];
            state.room.h = room[2];

            interact(() -> {
                renderer.init(VIEW_W, VIEW_H);
                renderer.build(state);
                // Yaw and pitch are RADIANS, and a positive pitch looks UP. Degrees here drew a
                // black frame, which is what an eight-radian pitch looks like.
                boolean creaseShot = room[0] == 12 && room[1] == 10;
                renderer.render(creaseShot
                        ? new CameraPose(room[0] / 2, 3, room[1] / 2, 0, Math.toRadians(-20))
                        : new CameraPose(room[0] / 2, EYE_HEIGHT_FT, room[1] - STAND_BACK_FT, 0,
                                Math.toRadians(-8)));
                StackPane host = (StackPane) scene.getRoot();
                host.getChildren().setAll(renderer.node());
            });
            settle(500);

            WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
            write(shot, String.format("real-%03.0fx%03.0f", room[0], room[1]));
            interact(renderer::dispose);
        }
    }

    @Test
    void compareTheTwoGrids() {
        for (double side : new double[] {200, 60, 30}) {
            shootBothWays(side);
        }

        System.out.println("PROBE ---- what the two cost ----");
        for (double side : new double[] {200, 30}) {
            measureCost(side);
        }

        System.out.println("PROBE ---- does the sharp grid crawl when the camera moves? ----");
        // 0.05 ft a frame is a slow walk at 60 fps. The 0.37 ft arm is the control: without it a
        // measurement reporting 0.00% cannot be told from one that is broken.
        //
        // The control step is deliberately not a whole number of feet. It was 1.0 ft, and the
        // tiled room reported a mean change of EXACTLY zero, which looked like a dead
        // measurement and was the opposite: a one-foot grid moved by exactly one foot is the
        // same picture, so the render was working perfectly and saying so.
        for (double side : new double[] {200, 60}) {
            measureCrawl(side, 0.05);
        }
        measureCrawl(200, 0.37);

        reportLayerOffsets();
    }

    /** The ways of drawing a surface that the cost measurement compares. */
    private enum Arm {
        OLD("old, one capped picture", OLD_CAP_PX),
        TILED("tiled, vignette as shipped", -1),
        TILED_512("tiled, vignette at 512", 512),
        TILED_256("tiled, vignette at 256", 256);

        private final String label;

        /** The vignette cap this arm draws at, or -1 to use whatever the app currently ships. */
        private final double capPx;

        private final List<Long> frames = new ArrayList<>();
        private final List<Long> builds = new ArrayList<>();

        Arm(String label, double capPx) {
            this.label = label;
            this.capPx = capPx;
        }

        double cap() {
            return capPx < 0 ? SurfaceMetrics.MAX_TEXTURE_PX : capPx;
        }
    }

    /**
     * What each way of drawing the room costs to build and to draw.
     *
     * <p>Two separate numbers, because they fail differently. <b>Build</b> is the hitch when the 3D
     * view opens. <b>Frame</b> is what it costs to keep drawing once you are in there, where the
     * tiled version has twice as many quads but a far smaller working set.
     *
     * <p>The third arm is not a proposal, it is a question the first two raise: the vignette is
     * still painted at up to 2048 px for what is a smooth gradient, so how much of what is left is
     * that?
     *
     * <p><b>Round robin in short slices, and the reason is the same one that caught
     * {@code DragGhostCostProbe}.</b> Run each arm once through, in order, and this machine
     * reported the 30 ft room at 7.6 ms for the old way and 8.2 for the tiled one, then on the very
     * next run 13.0 and 11.4: not merely different numbers but a different winner. Drift larger
     * than the difference being measured is not a measurement.
     */
    private void measureCost(double sideFt) {
        Room room = new Room();
        room.w = sideFt;
        room.l = sideFt;
        room.h = 10;

        for (Arm arm : Arm.values()) {
            arm.frames.clear();
            arm.builds.clear();
        }
        for (int slice = 0; slice < SLICES; slice++) {
            for (Arm arm : Arm.values()) {
                timeOneSlice(room, arm);
            }
        }

        System.out.printf("PROBE %.0f ft room:%n", sideFt);
        for (Arm arm : Arm.values()) {
            List<Long> frames = new ArrayList<>(arm.frames);
            frames.sort(null);
            List<Long> builds = new ArrayList<>(arm.builds);
            builds.sort(null);
            System.out.printf("PROBE   %-24s build %5.1f ms (median of %d), "
                            + "frame median %2d ms, mean %4.1f ms over %d frames%n",
                    arm.label, builds.get(builds.size() / 2) / 1e6, builds.size(),
                    frames.get(frames.size() / 2),
                    frames.stream().mapToLong(Long::longValue).average().orElse(0), frames.size());
        }
    }

    /** Builds the room one way, turns on the spot for a while, and records both costs. */
    private void timeOneSlice(Room room, Arm arm) {
        List<Long> frames = new ArrayList<>();
        interact(() -> {
            world.getChildren().removeIf(node -> !(node instanceof AmbientLight));
            float[] center = RoomGeometry.centerOf(room);
            long start = System.nanoTime();
            for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(room)) {
                if (arm == Arm.OLD) {
                    world.getChildren().add(shippedQuad(s));
                } else {
                    world.getChildren().addAll(tiledQuads(s, center, arm.cap()));
                }
            }
            arm.builds.add(System.nanoTime() - start);
            standAtOneEnd(room.w, room.l);
        });
        settle(200);

        AnimationTimer clock = new AnimationTimer() {
            private long previous;
            private double angle;

            @Override
            public void handle(long now) {
                if (previous != 0) {
                    frames.add((now - previous) / 1_000_000);
                }
                previous = now;
                // Turning on the spot rather than standing still, so every frame really is
                // redrawn and the numbers are not measuring an idle scene.
                angle += 0.4;
                yaw.setAngle(Math.sin(Math.toRadians(angle)) * 35);
            }
        };
        interact(clock::start);
        settle(SLICE_MS);
        interact(clock::stop);

        if (!frames.isEmpty()) {
            arm.frames.addAll(frames.subList(1, frames.size()));
        }
    }

    /**
     * How much the far floor churns from frame to frame while the camera creeps forward.
     *
     * <p>This is the question a still picture cannot answer. A grid line drawn at its correct
     * width is about a tenth of a pixel across at the far end of a 200 ft room, and a renderer
     * with no mipmapping samples a line that thin more or less at random, so it winks in and out
     * as the camera moves. That reads as crawling or sparkling, and it would be a worse room than
     * a blurry one.
     *
     * <p>The measure is the share of pixels in a far band that change by more than a small amount
     * between one frame and the next, while the camera moves a twentieth of a foot each time. Both
     * versions are moving, so both change; the number worth reading is the difference between them.
     */
    private void measureCrawl(double sideFt, double stepFt) {
        Room room = new Room();
        room.w = sideFt;
        room.l = sideFt;
        room.h = 10;

        WritableImage[] shots = new WritableImage[2];
        for (boolean tiled : new boolean[] {false, true}) {
            interact(() -> {
                world.getChildren().removeIf(node -> !(node instanceof AmbientLight));
                float[] center = RoomGeometry.centerOf(room);
                for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(room)) {
                    if (tiled) {
                        world.getChildren().addAll(tiledQuads(s, center));
                    } else {
                        world.getChildren().add(shippedQuad(s));
                    }
                }
                standAtOneEnd(sideFt, sideFt);
            });
            settle(300);

            WritableImage previous = null;
            long churned = 0;
            long counted = 0;
            long delta = 0;
            for (int step = 0; step < 24; step++) {
                double z = -(sideFt - STAND_BACK_FT) + step * stepFt;
                interact(() -> position.setZ(z));
                WritableImage now = WaitForAsyncUtils.waitForAsyncFx(5000,
                        () -> scene.snapshot(null));
                if (previous != null) {
                    long[] pair = churnInFarBand(previous, now);
                    churned += pair[0];
                    counted += pair[1];
                    delta += pair[2];
                }
                previous = now;
            }
            System.out.printf("PROBE %3.0f ft, %-7s, %.2f ft a frame: %5.2f%% of %d far-band "
                            + "pixels change by more than 8/255 from frame to frame%n",
                    sideFt, tiled ? "tiled" : "shipped", stepFt,
                    100.0 * churned / counted, counted);
            System.out.printf("PROBE     mean absolute change per pixel: %.3f of 255%n",
                    (double) delta / counted);
        }
    }

    /** Counts changed pixels in the band of screen where the floor is furthest away. */
    private static long[] churnInFarBand(WritableImage a, WritableImage b) {
        // The floor starts a little below the middle of the frame at this pitch and runs to the
        // bottom edge. The first version of this band sat at 0.52 to 0.62 and was reading the
        // WALL, which is why it found nothing: the overlay measurement below prints the floor's
        // actual rows, and they begin at 566 of 800.
        int y0 = (int) (a.getHeight() * 0.72);
        int y1 = (int) a.getHeight();
        long changed = 0;
        long total = 0;
        double sumDelta = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < (int) a.getWidth(); x++) {
                double la = luminance(a.getPixelReader().getColor(x, y));
                double lb = luminance(b.getPixelReader().getColor(x, y));
                total++;
                sumDelta += Math.abs(la - lb) * 255;
                if (Math.abs(la - lb) * 255 > 8) {
                    changed++;
                }
            }
        }
        return new long[] {changed, total, Math.round(sumDelta)};
    }

    private static double luminance(Color c) {
        return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
    }

    /**
     * How far apart two photographs of the same room are.
     *
     * <p>The interesting reading is the small room. There the old picture was already close to 96
     * pixels to the foot, so the grid barely moves and <b>whatever difference is left is the
     * vignette</b>: it used to be painted into the same picture as the grid and is now blended
     * from a quad floating a hair above it. A small number here is the evidence that splitting the
     * picture in two did not change how the room looks.
     */
    private static String howDifferent(WritableImage a, WritableImage b) {
        if (a == null || b == null) {
            return "";
        }
        long changed = 0;
        long total = 0;
        double sum = 0;
        for (int y = 0; y < (int) a.getHeight(); y++) {
            for (int x = 0; x < (int) a.getWidth(); x++) {
                double la = luminance(a.getPixelReader().getColor(x, y));
                double lb = luminance(b.getPixelReader().getColor(x, y));
                double delta = Math.abs(la - lb) * 255;
                total++;
                sum += delta;
                if (delta > 8) {
                    changed++;
                }
            }
        }
        return String.format("The two photographs differ on %.1f%% of pixels by more than 8/255, "
                + "mean %.2f", 100.0 * changed / total, sum / total);
    }

    /**
     * Puts the camera near one end of the room, at eye height, looking down its length.
     *
     * <p>The room runs from z = 0 at the north wall to z = -l at the south, and the camera looks
     * along +z by default, so standing at the south end and looking north is the view with the
     * most distance in it.
     */
    private void standAtOneEnd(double wFt, double lFt) {
        position.setX(wFt / 2);
        position.setY(-EYE_HEIGHT_FT);
        position.setZ(-(lFt - STAND_BACK_FT));
        yaw.setAngle(0);
        pitch.setAngle(8);
    }

    /** Builds the same room twice, once each way, from one camera position. */
    private void shootBothWays(double sideFt) {
        Room room = new Room();
        room.w = sideFt;
        room.l = sideFt;
        room.h = 10;

        WritableImage[] shots = new WritableImage[2];
        for (boolean tiled : new boolean[] {false, true}) {
            interact(() -> {
                world.getChildren().removeIf(node -> !(node instanceof AmbientLight));
                float[] center = RoomGeometry.centerOf(room);
                for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(room)) {
                    if (tiled) {
                        world.getChildren().addAll(tiledQuads(s, center));
                    } else {
                        world.getChildren().add(shippedQuad(s));
                    }
                }
                standAtOneEnd(sideFt, sideFt);
            });
            settle(400);
            WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
            write(shot, String.format("room-%03.0fft-%s", sideFt, tiled ? "tiled" : "shipped"));
            shots[tiled ? 1 : 0] = shot;
        }
        double capped = cappedPixelsPerFoot(sideFt);
        System.out.printf("PROBE %.0f ft room: the old capped picture was %.1f px/ft "
                        + "(%.1fx under 96), the tile is 96 px/ft. %s%n",
                sideFt, capped, GridTile.PX_PER_FOOT / capped, howDifferent(shots[0], shots[1]));
    }

    /**
     * A surface textured the way the app used to do it, before M6.7: one stretched, capped picture
     * carrying the fill, the grid and the darkening together.
     *
     * <p><b>Reconstructed here rather than called</b>, because the code that did it is gone. That
     * is the point of keeping this probe: the before-and-after can be re-run and re-photographed
     * by anyone who doubts the change, instead of resting on two screenshots in a commit message.
     * It is a faithful copy of the old {@code SurfaceTexture.forSurface}, clamp included.
     */
    private static MeshView shippedQuad(RoomGeometry.Surface s) {
        double pxPerFoot = cappedPixelsPerFoot(Math.max(s.widthFt, s.heightFt));
        int w = (int) Math.max(2, Math.round(s.widthFt * pxPerFoot));
        int h = (int) Math.max(2, Math.round(s.heightFt * pxPerFoot));

        Canvas canvas = new Canvas(w, h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Tokens.ROOM_FILL);
        g.fillRect(0, 0, w, h);

        // The clamp is the whole reason a big room's lines were heavy as well as soft: it stops
        // the line shrinking below one texture pixel, and one texture pixel in a 200 ft room is
        // 1.17 inches of floor.
        double step = pxPerFoot;
        double lineWidth = Math.max(1, GridTile.LINE_WIDTH_PX * pxPerFoot / GridTile.PX_PER_FOOT);
        g.setStroke(Tokens.GRID_LINE);
        g.setLineWidth(lineWidth);
        double firstX = s.mirroredGridX ? w % step : step;
        if (s.mirroredGridX && firstX < 1) {
            firstX += step;
        }
        for (double x = firstX; x < w - 1; x += step) {
            g.strokeLine(x, 0, x, h);
        }
        for (double y = step; y < h - 1; y += step) {
            g.strokeLine(0, y, w, y);
        }

        g.setFill(new RadialGradient(0, 0, w / 2.0, h / 2.0,
                SurfaceMetrics.VIGNETTE_RADIUS_FRACTION * Math.max(w, h),
                false, CycleMethod.NO_CYCLE,
                List.of(new Stop(0, Color.rgb(85, 85, 85, 0)),
                        new Stop(1, Color.rgb(17, 17, 17, SurfaceMetrics.VIGNETTE_MAX_ALPHA)))));
        g.fillRect(0, 0, w, h);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return quad(s.corners, canvas.snapshot(params, null), 1, 1, 0, 0);
        // The vignette above is painted here rather than called out to SurfaceTexture, for the
        // same reason as the clamp: this arm has to keep drawing what M6.6 drew whatever the app
        // does next.
    }

    /**
     * The shipped vignette, or the same gradient at a smaller cap so the cost of the cap can be
     * read on its own.
     *
     * <p>At the shipped cap this calls the shipped painter, so arm 1 of the cost measurement is
     * measuring the real thing and not a copy of it.
     */
    /**
     * The old rule: 96 pixels to the foot, or whatever fits in 2048, whichever is smaller.
     *
     * <p><b>The 2048 is a literal here and must stay one.</b> It read
     * {@code SurfaceMetrics.MAX_TEXTURE_PX} for one afternoon, which quietly made the historical
     * arm follow the current cap: lowering the cap to measure what that was worth also shrank the
     * thing it was being measured against, and the comparison said the change was worth much less
     * than it is. A frozen baseline has to be frozen.
     */
    private static double cappedPixelsPerFoot(double longestSideFt) {
        return Math.min(GridTile.PX_PER_FOOT, OLD_CAP_PX / longestSideFt);
    }

    /**
     * The same surface as the app draws it now: the shipped tile laid down span times over, with
     * the vignette on its own quad a hair nearer the room.
     *
     * <p>Both quads, not just the grid, because half the reason to keep this probe is the
     * question the sharpness does not answer: <b>did splitting the picture in two change how the
     * darkening looks?</b> A small room should come out of both arms as very nearly the same
     * photograph, and {@code compareSmallRoom} is where that is read as a number.
     */
    private static List<MeshView> tiledQuads(RoomGeometry.Surface s, float[] center) {
        return tiledQuads(s, center, SurfaceMetrics.MAX_TEXTURE_PX);
    }

    private static List<MeshView> tiledQuads(RoomGeometry.Surface s, float[] center,
            double vignetteCapPx) {
        GridTile tile = GridTile.forGrid(false);
        MeshView grid = quad(s.corners, SurfaceTexture.gridTile(false),
                tile.span(s.widthFt), tile.span(s.heightFt),
                tile.originX(s.widthFt, s.mirroredGridX), 0);
        MeshView vignette = quad(RoomGeometry.liftedTowards(s.corners, center, 0.01),
                SurfaceTexture.vignette(s.widthFt, s.heightFt, vignetteCapPx), 1, 1, 0, 0);
        return List.of(grid, vignette);
    }

    private static MeshView quad(float[] corners, Image texture, double repeatX, double repeatY,
            double offsetX, double offsetY) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(corners);
        mesh.getTexCoords().setAll(
                (float) offsetX, (float) offsetY,
                (float) (offsetX + repeatX), (float) offsetY,
                (float) (offsetX + repeatX), (float) (offsetY + repeatY),
                (float) offsetX, (float) (offsetY + repeatY));
        mesh.getFaces().setAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);

        MeshView view = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseMap(texture);
        view.setMaterial(material);
        view.setCullFace(CullFace.BACK);
        return view;
    }

    /**
     * Whether a second quad a hair above the floor survives the depth buffer.
     *
     * <p>Keeping the vignette on top of a tiled grid needs one, and the far clip is 2000 ft, so
     * the depth buffer is spread thin. This lays a bright patch over the floor at several heights
     * and several distances and reads back whether the patch actually won.
     */
    private void reportLayerOffsets() {
        Room room = new Room();
        room.w = 200;
        room.l = 200;
        room.h = 10;

        System.out.println("PROBE ---- does a coplanar overlay survive the depth buffer? ----");
        for (double offsetFt : new double[] {0, 0.001, 0.01, 0.05}) {
            interact(() -> {
                world.getChildren().removeIf(node -> !(node instanceof AmbientLight));
                for (RoomGeometry.Surface s : RoomGeometry.surfacesOf(room)) {
                    world.getChildren().add(shippedQuad(s));
                }
                // A magenta band down the middle of the floor, from under the camera to the far
                // wall, lifted by offsetFt. If the depth buffer cannot separate the two at a given
                // distance, the band breaks up there and the count falls.
                world.getChildren().add(overlayStrip(room.w, room.l, offsetFt));
                standAtOneEnd(room.w, room.l);
            });
            settle(300);

            WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
            write(shot, String.format("overlay-%.3fft", offsetFt));
            System.out.printf("PROBE offset %.3f ft: %s%n", offsetFt, magentaReport(shot));
        }
    }

    /** A magenta band lying along the floor's center line, lifted by {@code offsetFt}. */
    private static MeshView overlayStrip(double wFt, double lFt, double offsetFt) {
        float y = (float) -offsetFt;
        float x0 = (float) (wFt / 2 - 1.5);
        float x1 = (float) (wFt / 2 + 1.5);
        TriangleMesh mesh = new TriangleMesh();
        // Wound the same way round as RoomGeometry winds the floor, so it faces up like the
        // floor does rather than being culled away from a camera standing on it.
        mesh.getPoints().setAll(
                x0, y, (float) -lFt,
                x1, y, (float) -lFt,
                x1, y, 0,
                x0, y, 0);
        mesh.getTexCoords().setAll(0, 0, 1, 0, 1, 1, 0, 1);
        mesh.getFaces().setAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);
        MeshView view = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(Color.MAGENTA);
        view.setMaterial(material);
        view.setCullFace(CullFace.BACK);
        return view;
    }

    /**
     * How much of the overlay strip actually won the depth test, and where.
     *
     * <p>A clean win is a solid count and a continuous run of rows. Z-fighting shows up as a
     * lower count and as rows in the middle of the run with nothing on them, because the two
     * surfaces swap which one is in front from pixel to pixel.
     */
    private static String magentaReport(WritableImage shot) {
        int total = 0;
        int firstRow = -1;
        int lastRow = -1;
        int emptyRowsInside = 0;
        for (int y = 0; y < (int) shot.getHeight(); y++) {
            int run = magentaRun(shot, y);
            total += run;
            if (run > 0) {
                if (firstRow < 0) {
                    firstRow = y;
                }
                lastRow = y;
            }
        }
        for (int y = firstRow; y >= 0 && y <= lastRow; y++) {
            if (magentaRun(shot, y) == 0) {
                emptyRowsInside++;
            }
        }
        return String.format("%5d magenta pixels over rows %d to %d, %d rows inside that run "
                + "with none at all", total, firstRow, lastRow, emptyRowsInside);
    }

    private static int magentaRun(WritableImage shot, int y) {
        int count = 0;
        for (int x = 0; x < (int) shot.getWidth(); x++) {
            Color c = shot.getPixelReader().getColor(x, y);
            if (c.getRed() > 0.5 && c.getBlue() > 0.5 && c.getGreen() < 0.3) {
                count++;
            }
        }
        return count;
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
