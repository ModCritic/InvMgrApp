package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.threed.BoxGeometry;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.Perspective;
import com.modcritic.invmgr.threed.RoomGeometry;
import com.modcritic.invmgr.threed.Transitions;
import com.modcritic.invmgr.threed.jfx.FaceStrip;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Proves the 3D view actually draws, and draws the right colors.
 *
 * <p><b>This is the test that stops the whole milestone being a black rectangle.</b> When JavaFX
 * has no usable 3D pipeline it falls back to software rendering, where a 3D scene produces nothing
 * whatsoever and reports no error at all. Every other check in the 3D package is arithmetic and
 * would stay perfectly green while the app showed an empty screen.
 *
 * <p>Colors are checked by <b>looking for them in the picture</b> rather than by sampling a
 * chosen pixel. That is deliberate: pinning coordinates would mean re-deriving where a face lands
 * every time the camera or the room changes, and a test that has to be re-tuned gets loosened
 * instead. Asking "is this exact color anywhere on screen" is both stricter and more stable;
 * shading in the wrong color space, or not drawing at all, both fail it.
 */
class Room3dAppearanceTest extends ApplicationTest {

    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    /** The color of the item put in the room. The app's own family: only the hue is random. */
    private static final String ITEM_COLOR = "hsl(207,55%,42%)";

    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    @Test
    @DisplayName("the 3D pipeline is really available, or every other 3D test is meaningless")
    void theGraphicsPipelineCanDraw3d() {
        // A hard assertion, not a JUnit assumption. An assumption would SKIP, and a skipped test
        // reads as a passing one at a glance, which is exactly how a suite goes quietly blind.
        // If this fails, the two Prism settings in pom.xml's surefire block have been lost.
        assertTrue(App.isScene3dSupported(),
                "no 3D pipeline: the surefire configuration must set "
                        + "prism.forceGPU=true and prism.order=es2, or every 3D test draws nothing "
                        + "and passes anyway");
    }

    @Test
    @DisplayName("the room and its contents are all in the scene, and planned items are not")
    void everythingThatShouldBeThereIs() {
        interact(() -> {
            Item planned = new Item();
            planned.id = "planned-1";
            planned.serial = 2;
            planned.x_px = 96;
            planned.y_px = 96;
            planned.w_in = 12;
            planned.l_in = 12;
            planned.h_in = 12;
            planned.color = ITEM_COLOR;
            planned.planned = true;
            app.state().items.add(planned);
        });
        openThreeD();

        List<String> ids = new java.util.ArrayList<>();
        for (javafx.scene.Node node : ((javafx.scene.Group) app.view3d().subScene().getRoot())
                .getChildren()) {
            if (node.getId() != null) {
                ids.add(node.getId());
            }
        }

        assertTrue(ids.contains("box-1"), "the real item is missing from the room: " + ids);
        assertTrue(!ids.contains("planned-1"),
                "a planned item was built into the 3D room, and never should be: " + ids);
        assertTrue(ids.contains("room-floor") && ids.contains("room-north")
                        && ids.contains("room-east"),
                "the room's surfaces are missing: " + ids);
        // Five surfaces and no ceiling, each one a grid quad with a vignette quad over it.
        // Counting both halves separately is what catches a surface that lost one of them: since
        // M6.7 a room with the grid but no vignette, or the other way round, would still look
        // roughly right in a screenshot and be wrong.
        assertEquals(5, ids.stream().filter(id -> id.startsWith("room-")
                        && !id.endsWith("-vignette")).count(),
                "there should be exactly five grid surfaces and no ceiling, got " + ids);
        assertEquals(5, ids.stream().filter(id -> id.endsWith("-vignette")).count(),
                "and one vignette over each of them, got " + ids);

        // And each vignette floats clear of the grid it darkens. Laid in exactly the same plane
        // the two fight over the depth buffer and the room speckles; there is no pixel test that
        // catches that reliably, because which of the two wins is decided per pixel and can go
        // either way from one frame to the next. This asks the geometry instead.
        for (String name : new String[] {"room-floor", "room-north", "room-east"}) {
            javafx.scene.shape.TriangleMesh grid =
                    (javafx.scene.shape.TriangleMesh) ((javafx.scene.shape.MeshView)
                            find(name)).getMesh();
            javafx.scene.shape.TriangleMesh over =
                    (javafx.scene.shape.TriangleMesh) ((javafx.scene.shape.MeshView)
                            find(name + "-vignette")).getMesh();
            double apart = 0;
            for (int i = 0; i < grid.getPoints().size(); i++) {
                apart = Math.max(apart,
                        Math.abs(grid.getPoints().get(i) - over.getPoints().get(i)));
            }
            assertTrue(apart > 0, name + "'s vignette is in exactly the same plane as its grid");
        }
    }

    @Test
    @DisplayName("picking through the real renderer finds the box, and on the right side of it")
    void theRendererAnswersWhatIsUnderAPoint() {
        openThreeD();

        // openThreeD puts a 2 ft cube with its west face 5 ft east of the west wall and its north
        // face 3 ft south of the north wall, sitting on the floor, so it occupies x 5..7, y 0..2,
        // z 3..5 in feet. This is the seam between PickingTest, which checks the arithmetic with
        // no window, and the real renderer, which has to hand it the right boxes and the right
        // surface size.
        double width = app.view3d().subScene().getWidth();
        double height = app.view3d().subScene().getHeight();

        // Stand level with the middle of the box and look straight at it.
        interact(() -> {
            app.view3d().camera().x = 6;
            app.view3d().camera().y = 1;
            app.view3d().camera().z = 12;
            app.view3d().camera().yaw = 0;
            app.view3d().camera().pitch = 0;
        });
        assertEquals("box-1", app.view3d().pickItem(width / 2, height / 2),
                "looking straight at the box, the middle of the picture must find it");
        assertEquals(null, app.view3d().pickItem(width * 0.02, height / 2),
                "the far edge of the picture is empty room and must find nothing");

        // Now step west so the box sits off to the right, and check the ray agrees about which
        // side that is. This is the assertion a mirrored world fails and the one above does not:
        // a reflection leaves the middle of the picture exactly where it was.
        interact(() -> app.view3d().camera().x = 2);

        assertEquals("box-1", app.view3d().pickItem(width * 0.72, height / 2),
                "a box to the east must be found on the RIGHT of the picture");
        assertEquals(null, app.view3d().pickItem(width * 0.28, height / 2),
                "and must not be found at the mirror-image point on the left");

        // And nothing is pickable once the room is gone. Note what this does and does not pin:
        // it is View3D's own "not open" guard being tested, not the renderer forgetting its
        // boxes. The renderer clears them too, but that line is for releasing memory and no test
        // here can see it; deleting it changes no answer, which the sweep confirms.
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().close());
        assertEquals(null, app.view3d().pickItem(width / 2, height / 2),
                "a closed view must pick nothing");
    }

    @Test
    @DisplayName("nothing in the room is shiny, and the walls cannot be picked")
    void theSurfacesBehaveAsThePickingWillNeed() {
        openThreeD();

        // Counted, because every assertion below sits behind a `continue` and both of them are in
        // an if/else. An empty root, or one whose children all had null ids, would walk straight
        // through and report a pass having checked nothing. Counting the two branches separately
        // also catches the subtler version, where the walls arrive and the boxes do not.
        int walls = 0;
        int items = 0;
        for (javafx.scene.Node node
                : ((javafx.scene.Group) app.view3d().subScene().getRoot()).getChildren()) {
            if (node.getId() == null) {
                continue;
            }
            if (node.getId().startsWith("room-")) {
                walls++;
                // Hovering an item in M5.3 works by casting a ray and taking what it hits. A wall
                // that can be hit stops the ray before it ever reaches a box, and NOTHING in the
                // room is ever pickable. Pinned now rather than at M5.3, because the property is
                // set now and a rule with no test is a rule waiting to be deleted; the mutation
                // sweep turned this up as a blind spot exactly that way.
                assertTrue(node.isMouseTransparent(),
                        node.getId() + " must be mouse-transparent, or picking hits it first");
            } else {
                items++;
                assertTrue(!node.isMouseTransparent(),
                        node.getId() + " is an item and must stay pickable");
            }

            if (node instanceof javafx.scene.shape.MeshView mesh) {
                javafx.scene.paint.PhongMaterial material =
                        (javafx.scene.paint.PhongMaterial) mesh.getMaterial();
                // No shine anywhere. A specular highlight is a bright spot that moves as you walk,
                // which is the one kind of lighting this view is meant to have none of. JavaFX
                // leaves it off by default and that default is what is relied on, so the property
                // is pinned rather than a redundant setter being called.
                assertEquals(null, material.getSpecularColor(),
                        node.getId() + " has a specular highlight, and nothing here should");
            }
        }

        // Two nodes per surface, not one: JfxRenderer3D lays a vignette over the grid and both
        // are named after the surface, so both start with "room-". Ten is five surfaces fully
        // built. A smaller number means a surface or a layer is missing and the rule above went
        // unchecked for it.
        assertEquals(2 * RoomGeometry.SURFACE_COUNT, walls,
                "expected a grid and a vignette for each of the room's surfaces");
        assertTrue(items > 0,
                "no item node in the scene, so the pickable rule went unchecked");
    }

    @Test
    @DisplayName("the box sits exactly where the model says it does, in feet")
    void theBoxIsWhereTheModelPutIt() {
        openThreeD();
        javafx.scene.Node box = find("box-1");
        assertNotNull(box, "the box is not in the scene at all");

        // The item is 24 inches cubed, at 480 px east and 288 px south, sitting on the floor.
        // That is 5-7 ft east, 3-5 ft south, 0-2 ft up in the specification's axes; and JavaFX's
        // are the same ones turned half a turn about x, so y and z both come out negative: x 5 to
        // 7, y −2 to 0, z −5 to −3.
        //
        // A stray factor of 8 or 96 anywhere in the conversion shows up here as a box the size of
        // a building, which is much easier to see in numbers than in a dark room. The signs are
        // worth pinning for their own sake: this test read z as 3 to 5 while the room was
        // reflected east-to-west, and agreed with the code the whole time.
        javafx.geometry.Bounds bounds = box.getBoundsInParent();
        assertEquals(5, bounds.getMinX(), 0.001);
        assertEquals(7, bounds.getMaxX(), 0.001);
        assertEquals(-5, bounds.getMinZ(), 0.001);
        assertEquals(-3, bounds.getMaxZ(), 0.001);
        assertEquals(-2, bounds.getMinY(), 0.001);
        assertEquals(0, bounds.getMaxY(), 0.001);
    }

    @Test
    @DisplayName("the room draws something, and it is not just the background color")
    void theRoomIsActuallyDrawn() throws IOException {
        openThreeD();
        WritableImage shot = capture("m5-1-room");

        Map<Integer, Integer> counts = histogram(shot);
        assertTrue(counts.size() > 20,
                "the 3D view drew " + counts.size() + " distinct colors: a black rectangle "
                        + "would be one, which is what a missing 3D pipeline looks like");

        // The void above the walls is the background. The room's surfaces are lighter than it.
        // Both must be present: only background means nothing drew, and no background means the
        // camera is not where it was put.
        assertTrue(counts.containsKey(key(Tokens.CANVAS_WRAP_BG)),
                "the void above the open ceiling should be the background color");
        assertTrue(counts.keySet().stream().anyMatch(c -> isNear(c, Tokens.ROOM_FILL, 6)),
                "no surface anywhere is the room's own gray");
    }

    @Test
    @DisplayName("a box's faces are the exact shades the original computes")
    void theBoxIsShadedLikeTheOriginal() throws IOException {
        openThreeD();
        WritableImage shot = captureRoom("m5-1-box");
        Map<Integer, Integer> counts = histogram(shot);

        // Exact equality. These colors come from the same arithmetic the original uses, which
        // ShadesTest already checks against the original's own output, so if this finds them on
        // screen, the whole chain from the saved color string to lit pixels is right.
        //
        // The center of the screen is where the full-screen darkening is fully transparent, so a
        // box there keeps its color untouched. Faces are flat and large, so each covers a great
        // many pixels; a handful would suggest an edge artifact rather than a face.
        assertPresent(counts, "top face", BoxGeometry.BLOCK_TOP, 200);
        assertPresent(counts, "the side facing the camera", BoxGeometry.BLOCK_NORTH_SOUTH, 200);
        assertPresent(counts, "the side facing east or west", BoxGeometry.BLOCK_EAST_WEST, 200);

        // The edge frame is thin, so it covers far fewer pixels than a face, but it must be there,
        // because it is the whole reason each face is five pieces instead of one.
        assertPresent(counts, "the dark edge frame", BoxGeometry.BLOCK_EDGE, 20);
    }

    @Test
    @DisplayName("the top face is lighter than the sides, and the sides are not all the same")
    void theShadingReadsAsItShould() throws IOException {
        openThreeD();
        Map<Integer, Integer> counts = histogram(captureRoom("m5-1-shading"));

        Color top = FaceStrip.colorOfBlock(ITEM_COLOR, BoxGeometry.BLOCK_TOP);
        Color northSouth = FaceStrip.colorOfBlock(ITEM_COLOR, BoxGeometry.BLOCK_NORTH_SOUTH);
        Color eastWest = FaceStrip.colorOfBlock(ITEM_COLOR, BoxGeometry.BLOCK_EAST_WEST);
        Color edge = FaceStrip.colorOfBlock(ITEM_COLOR, BoxGeometry.BLOCK_EDGE);

        // Stated as an ordering as well as as exact values, because this is what the look actually
        // depends on: a box has to read as a solid object lit from nowhere in particular, and it
        // does that only because the faces step down in brightness in a fixed order.
        assertTrue(top.getBrightness() > northSouth.getBrightness(),
                "the top must be the lightest face");
        assertTrue(northSouth.getBrightness() > eastWest.getBrightness(),
                "north and south must be lighter than east and west");
        assertTrue(eastWest.getBrightness() > edge.getBrightness(),
                "the edge frame must be darker than any face it borders");

        // And all four are genuinely distinguishable on screen, not rounded into each other.
        assertTrue(counts.containsKey(key(top)) && counts.containsKey(key(northSouth))
                        && counts.containsKey(key(eastWest)),
                "the three visible face shades must all appear as distinct colors");
    }

    @Test
    @DisplayName("east is on the right of the screen when you face north: the room is not mirrored")
    void theRoomIsNotMirrored() throws IOException {
        openTwoBoxesAgainstTheNorthWall();
        WritableImage shot = captureRoom("m5-1-mirror");

        double eastX = meanXofFaceFacingCamera(shot, EAST_COLOR);
        double westX = meanXofFaceFacingCamera(shot, WEST_COLOR);
        double middle = shot.getWidth() / 2;

        // Stated three ways on purpose. The ordering alone would still pass if the whole view were
        // shifted sideways; the two side assertions alone would still pass if one box were missing.
        assertTrue(eastX > westX,
                "the box on the east side of the room is drawn to the LEFT of the western one: "
                        + "the view is mirrored (east at x=" + Math.round(eastX)
                        + ", west at x=" + Math.round(westX) + ")");
        assertTrue(eastX > middle,
                "facing north, the eastern box must be on the right half of the screen, and it is"
                        + " at x=" + Math.round(eastX) + " of " + shot.getWidth());
        assertTrue(westX < middle,
                "facing north, the western box must be on the left half of the screen, and it is"
                        + " at x=" + Math.round(westX) + " of " + shot.getWidth());
    }

    @Test
    @DisplayName("a positive yaw turns you to the right, so the room slides left")
    void turningRightMovesTheRoomLeft() throws IOException {
        openTwoBoxesAgainstTheNorthWall();
        double before = meanXofFaceFacingCamera(captureRoom("m5-1-yaw-ahead"), EAST_COLOR);

        // About nine degrees to the right. Small enough that everything stays comfortably on
        // screen, large enough that the shift is unmistakable, roughly 160 px at this width.
        interact(() -> {
            app.view3d().camera().yaw = 0.15;
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();
        double after = meanXofFaceFacingCamera(captureRoom("m5-1-yaw-right"), EAST_COLOR);

        // Added because the mutation sweep found nothing here at all: reversing yaw's sign left
        // every test in the project green. The two that look at a turned camera both aim it
        // straight at what they are checking, and one aim is as good as its mirror image when the
        // thing you are looking for only has to be somewhere on the screen.
        //
        // This is not a small gap. Yaw's sign is what M5.3's mouse look is built on, and getting
        // it backwards means the room turns the wrong way when the mouse moves, which is
        // obvious the moment anybody tries it and invisible until then.
        assertTrue(after < before - 50,
                "turning right should slide the room left across the screen, and the eastern box"
                        + " went from x=" + Math.round(before) + " to x=" + Math.round(after));
    }

    @Test
    @DisplayName("the 3D view can be opened, closed and opened again")
    void theViewCanBeOpenedMoreThanOnce() {
        openThreeD();
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().close());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(!app.view3d().isOpen(), "closing the 3D view left it open");

        // The failure this pins was total and immediate: JavaFX threw
        // "already set as root of another scene or subScene" out of the button handler, so the
        // second press did nothing at all and the only way back to 3D was to restart the app.
        // waitForAsyncFx is what makes it a failure rather than a stack trace on the console;
        // an exception on the interface thread is otherwise printed and swallowed.
        WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> app.view3d().open(app.state(), scene));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.view3d().isOpen(), "the 3D view did not open the second time");
        assertNotNull(find("box-1"), "the room came back empty the second time it was opened");
        assertEquals(10, ((javafx.scene.Group) app.view3d().subScene().getRoot()).getChildren()
                        .stream().filter(n -> n.getId() != null && n.getId().startsWith("room-"))
                        .count(),
                "reopening should rebuild five surfaces and their five vignettes: no more, and "
                        + "no fewer. A count that grows means the old room was left behind");
    }

    /** The eastern box's color. Two different hues so each can be found on its own. */
    private static final String EAST_COLOR = "hsl(207,55%,42%)";

    /** The western box's color. */
    private static final String WEST_COLOR = "hsl(0,55%,42%)";

    /**
     * Stands near the south wall looking due north, with a box against the north wall well to the
     * east and another well to the west.
     *
     * <p><b>Deliberately off-axis, which is the whole point.</b> The other tests here aim the
     * camera straight at the thing they are checking, and a mirrored view leaves anything at the
     * center of the screen exactly where it was, which is why every one of them stayed green
     * while the room was inside out.
     */
    private void openTwoBoxesAgainstTheNorthWall() {
        interact(() -> {
            app.state().items.add(box("east-box", 3, 8 * 96, 0, EAST_COLOR));
            app.state().items.add(box("west-box", 4, 2 * 96, 0, WEST_COLOR));

            // Direct, not through the button: see openThreeD above for why.
            app.view3d().open(app.state(), scene);

            // A 12 x 10 ft room. Standing at (6, 9) is a foot off the south wall, dead center
            // east-to-west, so the two boxes are symmetric about the middle of the screen and
            // about 20 degrees to either side of straight ahead, comfortably inside the view.
            // Tipped slightly down so both boxes are fully on screen rather than half cut off.
            app.view3d().camera().x = 6;
            app.view3d().camera().y = CameraPose.EYE_HEIGHT_FT;
            app.view3d().camera().z = 9;
            app.view3d().camera().yaw = 0;
            app.view3d().camera().pitch = -0.3;
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.view3d().isOpen(), "the 3D view did not open");
    }

    private static Item box(String id, int serial, double xpx, double ypx, String color) {
        Item item = new Item();
        item.id = id;
        item.serial = serial;
        item.dragOrder = serial;
        item.x_px = xpx;
        item.y_px = ypx;
        item.w_in = 24;
        item.l_in = 24;
        item.h_in = 24;
        item.color = color;
        return item;
    }

    /**
     * The average horizontal position, in pixels, of the one face of a box that points back at a
     * camera looking north, that is, the box's south face, which takes the north/south shade.
     */
    private double meanXofFaceFacingCamera(WritableImage image, String color) {
        int wanted = key(FaceStrip.colorOfBlock(color, BoxGeometry.BLOCK_NORTH_SOUTH));
        long total = 0;
        long found = 0;
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = 0; x < (int) image.getWidth(); x++) {
                if (key(image.getPixelReader().getColor(x, y)) == wanted) {
                    total += x;
                    found++;
                }
            }
        }
        assertTrue(found > 200,
                "the " + color + " box's near face covers only " + found + " pixels: it is not"
                        + " on screen at all, so nothing can be concluded about which side it is on");
        return (double) total / found;
    }

    /**
     * Puts one box in the room and looks at it from a corner, slightly downward, so that three of
     * its faces and its edge frame are all on screen at once.
     */
    private void openThreeD() {
        interact(() -> {
            Item box = new Item();
            box.id = "box-1";
            box.serial = 1;
            box.dragOrder = 1;
            box.x_px = 5 * 96;        // 5 ft east
            box.y_px = 3 * 96;        // 3 ft south of the north wall
            box.w_in = 24;
            box.l_in = 24;
            box.h_in = 24;
            box.color = ITEM_COLOR;
            app.state().items.add(box);

            // Opened directly rather than by pressing the button, since M5.2 put a two-and-a-half
            // second flight behind the button and every test in this class would have to sit
            // through it to look at a room the flight only delivers you to. What the button does
            // is ThreeDEntryTest's job; what the room looks like once you are in it is this
            // class's, and `theDirectOpenLandsWhereTheDescentLands` below is the seam between
            // them: it fails if these two ever stop arriving at the same place.
            app.view3d().open(app.state(), scene);

            // Stand back and to one side, looking down at the box: from due north at eye level
            // only one face would show, which would prove far less.
            //
            // The angles are worked out rather than picked, because guessing them once already
            // aimed the camera at the wrong wall. The box's middle is at (6, 1, 4); the camera is
            // at (3, 5, 9). Yaw 0 faces north, which is towards −z, and a POSITIVE yaw turns
            // right, so the angle to a box that is east and north of here is atan2(+dx, −dz).
            double eyeX = 3;
            double eyeY = 5;
            double eyeZ = 9;
            double toX = 6 - eyeX;
            double toY = 1 - eyeY;
            double toZ = 4 - eyeZ;
            app.view3d().camera().x = eyeX;
            app.view3d().camera().y = eyeY;
            app.view3d().camera().z = eyeZ;
            app.view3d().camera().yaw = Math.atan2(toX, -toZ);
            app.view3d().camera().pitch = Math.atan2(toY, Math.hypot(toX, toZ));
            app.view3d().render();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(app.view3d().isOpen(), "the 3D view did not open");
    }

    @Test
    @DisplayName("opening directly lands the camera exactly where the descent lands it")
    void theDirectOpenLandsWhereTheDescentLands() {
        // Every other test in this class opens the view directly, so if that shortcut ever
        // arrived somewhere the descent does not, they would all be describing a view the app
        // never shows, which is precisely the M3.5 failure, where a fix stayed green for a week
        // because the tests drove a path the running app never took.
        interact(() -> app.view3d().open(app.state(), scene));
        WaitForAsyncUtils.waitForFxEvents();

        CameraPose direct = app.view3d().camera().copy();

        // Where the descent ends up: build the same journey the app builds, and read its end.
        CameraPose overhead = new CameraPose(
                app.state().room.w / 2,
                Perspective.overheadHeightFt(app.state().room, scene.getWidth(), scene.getHeight()),
                app.state().room.l / 2,
                0, -Math.PI / 2);
        CameraPose landed = Transitions.descent(overhead).at(1);

        assertEquals(landed.x, direct.x, 1e-9, "east-west");
        assertEquals(landed.y, direct.y, 1e-9, "eye height");
        assertEquals(landed.z, direct.z, 1e-9, "north-south");
        assertEquals(landed.yaw, direct.yaw, 1e-9, "facing north");
        assertEquals(landed.pitch, direct.pitch, 1e-9, "level");
    }

    private void assertPresent(Map<Integer, Integer> counts, String what, int block, int atLeast) {
        Color expected = FaceStrip.colorOfBlock(ITEM_COLOR, block);
        Integer found = counts.get(key(expected));
        assertNotNull(found,
                what + " is missing: expected " + describe(expected) + " somewhere in the room");
        assertTrue(found >= atLeast,
                what + " covers only " + found + " pixels, expected at least " + atLeast);
    }

    /**
     * Finds a node in the 3D world by its id.
     *
     * <p>Written by hand rather than with {@code lookup("#id")}, which returns nothing here: the
     * usual selector search does not reach inside a {@code SubScene}'s 3D content. Worth knowing
     * before losing time to it, as this test did.
     */
    private javafx.scene.Node find(String id) {
        for (javafx.scene.Node node
                : ((javafx.scene.Group) app.view3d().subScene().getRoot()).getChildren()) {
            if (id.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    /**
     * Every color in the picture, counted, keyed at the precision a screen actually has.
     *
     * <p><b>Eight bits per channel, not the full floating-point value.</b> Reading a pixel back
     * gives a color reconstructed from three bytes, while working one out from scratch gives full
     * precision, so the two are never exactly equal even when they are unmistakably the same
     * color. This test failed on precisely that: the screenshot had {@code rgb(61,137,199)} in it
     * across 89,000 pixels while the comparison insisted the color was missing.
     */
    private Map<Integer, Integer> histogram(WritableImage image) {
        Map<Integer, Integer> counts = new HashMap<>();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                counts.merge(key(image.getPixelReader().getColor(x, y)), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int key(Color color) {
        return (channel(color.getRed()) << 16)
                | (channel(color.getGreen()) << 8)
                | channel(color.getBlue());
    }

    private static int channel(double value) {
        return (int) Math.round(value * 255);
    }

    private static boolean isNear(int colorKey, Color target, int tolerance) {
        return Math.abs(((colorKey >> 16) & 0xFF) - channel(target.getRed())) <= tolerance
                && Math.abs(((colorKey >> 8) & 0xFF) - channel(target.getGreen())) <= tolerance
                && Math.abs((colorKey & 0xFF) - channel(target.getBlue())) <= tolerance;
    }

    private static String describe(Color c) {
        return String.format("rgb(%d,%d,%d)",
                channel(c.getRed()), channel(c.getGreen()), channel(c.getBlue()));
    }

    /**
     * Screenshots just the 3D drawing, without the darkening laid over the whole screen.
     *
     * <p>The two are separated deliberately. The screen-wide vignette is a black wash that gets
     * stronger toward the corners, so a box away from the middle of the screen comes out darker
     * than the color it was painted, measured at two thirds, on the first attempt at this test,
     * which looked exactly like a shading bug and was not one. Sampling the 3D surface on its own
     * removes the question entirely and lets the color comparison stay exact.
     */
    private WritableImage captureRoom(String name) throws IOException {
        WaitForAsyncUtils.waitForFxEvents();
        return write(name,
                WaitForAsyncUtils.waitForAsyncFx(5000, () -> app.view3d().subScene().snapshot(
                        null, null)));
    }

    /** Screenshots the whole window, darkening and all: what you actually see. */
    private WritableImage capture(String name) throws IOException {
        WaitForAsyncUtils.waitForFxEvents();
        // Taking the picture has to happen on the interface thread, the same idiom the other
        // appearance tests use.
        return write(name, WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null)));
    }

    private WritableImage write(String name, WritableImage image) throws IOException {
        File directory = new File("target/screenshots");
        if (directory.isDirectory() || directory.mkdirs()) {
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                    new File(directory, name + ".png"));
        }
        return image;
    }
}
