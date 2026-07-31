package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import java.io.File;
import java.io.IOException;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Checks that the room actually renders in the documented colours, by sampling pixels.
 *
 * <p>The design workflow in {@code .claude/skills/invmgr-design/SKILL.md} is explicit that
 * "looks close" is not a result and that dark greys must be sampled rather than eyeballed —
 * {@code #1a1a1a} and {@code #181818} are both in use and are indistinguishable by eye. So this
 * renders the real window at the reference resolution and reads the pixels back.
 *
 * <p>The expected values are not invented. Each was measured from
 * {@code reference/desktop-04-item-on-canvas.png} with an image tool, and the derived ones (a
 * grid line over the floor, an item's border over its fill) are checked against that same
 * screenshot's arithmetic. Notably the reference's item border sampled as {@code (28,99,31)}
 * against a fill of {@code (48,166,52)} — exactly 0.6 of it — which is what confirms the border
 * is {@code rgba(0,0,0,0.4)} drawn <em>inside</em> the footprint rather than around it.
 *
 * <p>It also writes the screenshots to {@code target/screenshots/} for side-by-side comparison,
 * since pixel checks cannot judge layout or proportion.
 */
class CanvasAppearanceTest extends ApplicationTest {

    /** The reference screenshots are 2560×1440, so comparisons happen at that size. */
    private static final int REFERENCE_WIDTH = 2560;
    private static final int REFERENCE_HEIGHT = 1440;

    /**
     * Sampled from the reference: an item fill of {@code hsl(122,55%,42%)}. Doubles as a check on
     * the hand-written HSL conversion, which cannot use JavaFX's {@code Color.hsb} because
     * brightness and lightness are different quantities.
     */
    private static final Color REFERENCE_ITEM_FILL = Color.rgb(48, 166, 52);

    private static final String ITEM_COLOR = "hsl(122,55%,42%)";

    /** Mirrors {@code LayerTrack}'s own geometry, which is private to it. */
    private static final double TRACK_WIDTH_PX = 6;
    private static final double THUMB_RADIUS_PX = 9.5;

    private Stage stage;
    private Scene scene;
    private RoomCanvasView canvas;
    private LayerSliderDrawer drawer;
    private AppState state;
    private Item box;
    private Item neighbour;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        state = new AppState();
        state.room = new Room(12, 10, 8);

        // A box two feet square, three feet in from the north-west corner.
        box = item("item-id-11111111-2222-4333-8444-555555555555", 1, 24, 24, 288, 288, ITEM_COLOR);
        // A second box overlapping the first's south-east corner, with a higher drag order so it
        // is the one that dims. Its position is chosen so it covers neither the first box's
        // centre nor any point this test samples on it — an earlier version put its corner
        // exactly on that centre, and the fill assertion then sampled this box's border instead.
        neighbour = item("item-id-22222222-3333-4444-8555-666666666666", 2, 24, 24, 432, 432,
                ITEM_COLOR);
        state.items.add(box);
        state.items.add(neighbour);

        canvas = new RoomCanvasView(state);
        drawer = new LayerSliderDrawer(state);
        drawer.setOnLayerChanged(() -> canvas.refreshVisibility());

        HBox main = new HBox(drawer, canvas);
        HBox.setHgrow(canvas, Priority.ALWAYS);

        scene = new Scene(main, REFERENCE_WIDTH, REFERENCE_HEIGHT);
        stage.setScene(scene);
        stage.setWidth(REFERENCE_WIDTH);
        stage.setHeight(REFERENCE_HEIGHT);
        stage.show();
    }

    @Test
    @DisplayName("the floor, its grid and the area around it use the documented colours")
    void roomColoursMatchTheTokens() {
        WritableImage shot = capture("m2-room");

        Point2D origin = canvas.roomOriginInScene();
        double roomWidth = Units.feetToPx(state.room.w);
        double roomLength = Units.feetToPx(state.room.l);

        // Near the middle of the floor, but deliberately NOT at the exact centre: in a 12 x 10 ft
        // room the centre is 576, 480 px in, which is a whole number of feet on both axes and
        // therefore sits precisely on a grid-line crossing. Sampling there reads the grid, not
        // the floor — that mistake produced this test's first failure.
        double floorX = origin.getX() + roomWidth / 2 + 48;
        double floorY = origin.getY() + roomLength / 2 + 48;
        assertColor("room floor (#3a3a3a, lightly vignetted)", shot, floorX, floorY,
                Tokens.ROOM_FILL, 10);

        // And the vignette really is there: the floor darkens towards the edges. Checked as a
        // relationship rather than an absolute value, because the exact falloff at a given pixel
        // depends on gradient interpolation that is not worth pinning.
        Color nearCentre = read(shot, floorX, floorY);
        Color nearCorner = read(shot, origin.getX() + 30, origin.getY() + 30);
        assertTrue(nearCorner.getRed() < nearCentre.getRed() - 0.01,
                "the vignette should darken the floor towards its corners: centre "
                        + describe(nearCentre) + " vs corner " + describe(nearCorner));

        // Outside the room, in the surrounding area — a different, darker grey than the body.
        assertColor("canvas surround (#181818)", shot,
                origin.getX() + roomWidth + 60, origin.getY() + 60,
                Tokens.CANVAS_WRAP_BG, 2);

        // A grid line one foot in from the west wall: half-opacity black over the floor.
        Color floorAtEdge = read(shot, origin.getX() + Units.PX_PER_FOOT - 8,
                origin.getY() + roomLength / 2);
        Color gridExpected = blend(Color.BLACK, floorAtEdge, 0.5);
        assertColor("grid line (rgba(0,0,0,0.5) over the floor)", shot,
                origin.getX() + Units.PX_PER_FOOT, origin.getY() + roomLength / 2,
                gridExpected, 6);

        assertColor("slider drawer (#202020)", shot, 8, REFERENCE_HEIGHT / 2.0,
                Tokens.DRAWER_BG, 2);
    }

    @Test
    @DisplayName("an item's fill and its inside border match the reference exactly")
    void itemColoursMatchTheReference() {
        WritableImage shot = capture("m2-item");
        Point2D origin = canvas.roomOriginInScene();

        // The fill, which also proves the HSL conversion: hsl(122,55%,42%) must come out as the
        // (48,166,52) measured from the original app's own output.
        assertColor("item fill hsl(122,55%,42%)", shot,
                origin.getX() + box.x_px + Units.inchesToPx(box.w_in) / 2,
                origin.getY() + box.y_px + Units.inchesToPx(box.l_in) / 2,
                REFERENCE_ITEM_FILL, 2);

        // One pixel inside the top edge: the 2 px border, drawn inside the footprint. If the
        // border were drawn outside instead, this pixel would be the fill and every box would
        // be 4 px too big.
        assertColor("item border rgba(0,0,0,0.4) drawn inside", shot,
                origin.getX() + box.x_px + Units.inchesToPx(box.w_in) / 2,
                origin.getY() + box.y_px + 1,
                blend(Color.BLACK, REFERENCE_ITEM_FILL, 0.4), 3);
    }

    @Test
    @DisplayName("the slider's filled track is the #7ab accent")
    void sliderTrackUsesTheAccentColour() {
        WritableImage shot = capture("m2-slider");
        // The handle starts at the ceiling, so the whole track below it is filled — which is the
        // state every reference screenshot shows.
        Point2D trackCentre = drawer.slider().localToScene(Tokens.SLIDER_WIDTH / 2,
                drawer.slider().getHeight() / 2);
        assertColor("slider filled track (#7ab)", shot, trackCentre.getX(), trackCentre.getY(),
                Tokens.SLIDER_ACCENT, 3);
    }

    @Test
    @DisplayName("with the handle pulled down, the track is accent below it and near-white above")
    void sliderShowsBothTrackColours() {
        interact(() -> drawer.slider().setValue(4));      // 2 ft, well down the range
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage shot = capture("m2-slider-partway");

        LayerTrack track = drawer.slider();
        double height = track.getHeight();
        Point2D above = track.localToScene(Tokens.SLIDER_WIDTH / 2, height * 0.25);
        Point2D below = track.localToScene(Tokens.SLIDER_WIDTH / 2, height * 0.9);

        // Both values measured from a screenshot of the original taken with the slider down:
        // rgb(119,170,187) below the handle, rgb(233,233,237) above it.
        assertColor("slider track below the handle (#7ab)", shot, below.getX(), below.getY(),
                Tokens.SLIDER_ACCENT, 3);
        assertColor("slider track above the handle (near-white)", shot,
                above.getX(), above.getY(), LayerTrack.UNFILLED_TRACK, 3);
    }

    @Test
    @DisplayName("the track's ends are rounded, not square")
    void sliderTrackEndsAreRounded() {
        WritableImage shot = capture("m2-slider-rounded");
        LayerTrack track = drawer.slider();

        // Near a pill-shaped end the outer columns taper away while the middle is still solid;
        // a square end would be track colour edge to edge on every row.
        //
        // Scanned over the last few rows rather than pinned to one: the very last row is partly
        // transparent even in the middle, and exactly how many rows taper depends on the
        // renderer's antialiasing. What must be true of a rounded end is that SOME row near the
        // bottom has a solid middle and a faded edge.
        // Measured as a width, which needs no pixel-exact coordinates: count how many columns
        // across the track are solid accent, close to the end and well away from it. A square
        // end is the same width all the way down; a rounded one narrows.
        int wideRow = solidColumnsAcross(shot, track, THUMB_RADIUS_PX + 40);
        int nearEnd = solidColumnsAcross(shot, track, track.getHeight() - THUMB_RADIUS_PX - 2);

        assertTrue(wideRow >= 5,
                "the track should be about 6px of solid colour away from its ends, measured "
                        + wideRow);
        assertTrue(nearEnd < wideRow,
                "the track's end should be rounded, so it must be narrower two rows from the "
                        + "tip (" + nearEnd + " columns) than in the middle (" + wideRow + ")");
    }

    @Test
    @DisplayName("selecting an item draws a white outline clear of its edge")
    void selectionOutlineIsWhiteAndOffset() {
        interact(() -> canvas.setSelectedId(box.id));
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage shot = capture("m2-selected");
        Point2D origin = canvas.roomOriginInScene();

        double centreX = origin.getX() + box.x_px + Units.inchesToPx(box.w_in) / 2;
        double topY = origin.getY() + box.y_px;

        // 1 px clear of the edge is the gap; 2-4 px out is the 3 px outline itself.
        assertColor("selection outline (#fff, 3px at 1px offset)", shot, centreX, topY - 3,
                Color.WHITE, 6);
        // And the gap really is a gap — the floor shows through it.
        Color inGap = read(shot, centreX, topY - 1);
        assertTrue(inGap.getRed() < 0.5 && inGap.getGreen() < 0.5,
                "the 1px offset should show the floor, not white; got " + describe(inGap));
    }

    @Test
    @DisplayName("an item above and overlapping the selection fades to half opacity")
    void dimAboveHalvesOpacity() {
        interact(() -> canvas.setSelectedId(box.id));
        // The fade runs for 150 ms; sampling sooner catches it mid-transition.
        WaitForAsyncUtils.sleep(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        WritableImage shot = capture("m2-dimmed");
        Point2D origin = canvas.roomOriginInScene();

        // A point inside the neighbour but clear of the selected box, so what shows through the
        // half-transparent item is bare floor rather than the other item.
        double x = origin.getX() + neighbour.x_px + Units.inchesToPx(neighbour.w_in) - 30;
        double y = origin.getY() + neighbour.y_px + Units.inchesToPx(neighbour.l_in) - 30;

        // The floor immediately outside the item, rather than the raw token: the vignette has
        // already darkened it slightly by this far from the centre, and blending against the
        // undarkened value would put the expectation several units out.
        Color floorHere = read(shot, x + 40, y + 40);
        assertColor("dimmed item (opacity 0.5 over the floor)", shot, x, y,
                blend(REFERENCE_ITEM_FILL, floorHere, Tokens.DIM_OPACITY), 8);
    }

    @Test
    @DisplayName("the layer slider hides items at or above its height")
    void layerSliderHidesItems() {
        // Put the neighbour up on a shelf, then slide the layer down below it.
        interact(() -> {
            neighbour.baseHeight_in = 48;              // 4 ft up
            drawer.rebuild();
            drawer.slider().setValue(4);               // 2 ft — below the shelf
            canvas.refreshVisibility();
        });
        WaitForAsyncUtils.waitForFxEvents();

        WritableImage shot = capture("m2-layer-hidden");
        Point2D origin = canvas.roomOriginInScene();

        assertEquals(2, state.layerFeet, "two steps per foot: value 4 means 2 ft");

        // Where the raised box was, the floor should now show. Asserted as "this pixel is grey"
        // rather than as an exact value, because the vignette makes the floor's exact shade
        // position-dependent while the item is unmistakably green.
        double x = origin.getX() + neighbour.x_px + Units.inchesToPx(neighbour.w_in) - 30;
        double y = origin.getY() + neighbour.y_px + Units.inchesToPx(neighbour.l_in) - 30;
        Color uncovered = read(shot, x, y);
        assertTrue(Math.abs(to255(uncovered.getRed()) - to255(uncovered.getGreen())) < 6
                        && Math.abs(to255(uncovered.getGreen()) - to255(uncovered.getBlue())) < 6,
                "the raised item should be hidden, leaving grey floor; got "
                        + describe(uncovered));

        // The box on the floor is below the slider and must still be showing.
        assertColor("item below the layer is still drawn", shot,
                origin.getX() + box.x_px + Units.inchesToPx(box.w_in) / 2,
                origin.getY() + box.y_px + Units.inchesToPx(box.l_in) / 2,
                REFERENCE_ITEM_FILL, 3);
    }

    @Test
    @DisplayName("Fit mode scales a room bigger than the window down to fit")
    void fitModeScalesTheRoomDown() {
        interact(() -> {
            state.room = new Room(60, 40, 8);          // 5760 x 3840 px, far wider than the window
            canvas.rebuildRoom();
            canvas.setFitMode(true);
        });
        WaitForAsyncUtils.waitForFxEvents();
        capture("m2-fit-mode");

        double scale = canvas.fitScaleFactor();
        assertTrue(scale < 1, "a room wider than the window must be scaled down, got " + scale);

        // The whole room has to be inside the viewport, or Fit mode has not done its job.
        double scaledWidth = Units.feetToPx(state.room.w) * scale;
        double scaledHeight = Units.feetToPx(state.room.l) * scale;
        assertTrue(scaledWidth <= canvas.getViewportBounds().getWidth() + 1,
                "scaled width " + scaledWidth + " exceeds the viewport");
        assertTrue(scaledHeight <= canvas.getViewportBounds().getHeight() + 1,
                "scaled height " + scaledHeight + " exceeds the viewport");
    }

    @Test
    @DisplayName("Fit mode never magnifies a small room past 1:1")
    void fitModeDoesNotMagnify() {
        interact(() -> {
            state.room = new Room(4, 4, 8);            // 384 x 384 px, far smaller than the window
            canvas.rebuildRoom();
            canvas.setFitMode(true);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1.0, canvas.fitScaleFactor(), 1e-9,
                "a small room should stay its natural size, not be blown up");
    }

    // ------------------------------------------------------------------ helpers

    /** Screenshots the window, writes it to {@code target/screenshots}, and returns it. */
    private WritableImage capture(String name) {
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage image = WaitForAsyncUtils.waitForAsyncFx(5000,
                () -> scene.snapshot(null));
        try {
            File directory = new File("target/screenshots");
            if (directory.isDirectory() || directory.mkdirs()) {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        new File(directory, name + ".png"));
            }
        } catch (IOException e) {
            // Writing the file is a convenience for eyeballing, not the assertion — a failure
            // here must not disguise itself as a rendering problem.
            System.err.println("could not write screenshot " + name + ": " + e.getMessage());
        }
        return image;
    }

    private Color read(WritableImage image, double x, double y) {
        return image.getPixelReader().getColor((int) Math.round(x), (int) Math.round(y));
    }

    /**
     * Asserts a pixel matches a colour.
     *
     * @param tolerance allowed difference per channel, in 0-255 units. Small tolerances absorb
     *     antialiasing and the one-unit rounding difference between how JavaFX and a browser
     *     round a blended channel; anything larger would be a real token mismatch.
     */
    private void assertColor(String what, WritableImage image, double x, double y,
            Color expected, int tolerance) {
        Color actual = read(image, x, y);
        int dr = Math.abs(to255(actual.getRed()) - to255(expected.getRed()));
        int dg = Math.abs(to255(actual.getGreen()) - to255(expected.getGreen()));
        int db = Math.abs(to255(actual.getBlue()) - to255(expected.getBlue()));
        assertTrue(dr <= tolerance && dg <= tolerance && db <= tolerance,
                what + " at (" + Math.round(x) + "," + Math.round(y) + "): expected "
                        + describe(expected) + " but was " + describe(actual));
    }

    /**
     * How many pixels across the track's width are solid accent colour at the given height.
     *
     * <p>Sampling a width rather than individual points means the check does not depend on
     * landing on an exact pixel, which is what made an earlier version of this test fragile.
     */
    private int solidColumnsAcross(WritableImage shot, LayerTrack track, double localY) {
        int solid = 0;
        for (double x = 0; x <= Tokens.SLIDER_WIDTH; x += 1) {
            Point2D point = track.localToScene(x, localY);
            if (distance(read(shot, point.getX(), point.getY()), Tokens.SLIDER_ACCENT) <= 24) {
                solid++;
            }
        }
        return solid;
    }

    /** How far apart two colours are, summed across the channels in 0-255 units. */
    private static double distance(Color a, Color b) {
        return Math.abs(to255(a.getRed()) - to255(b.getRed()))
                + Math.abs(to255(a.getGreen()) - to255(b.getGreen()))
                + Math.abs(to255(a.getBlue()) - to255(b.getBlue()));
    }

    private static Color blend(Color over, Color under, double overOpacity) {
        return Color.color(
                over.getRed() * overOpacity + under.getRed() * (1 - overOpacity),
                over.getGreen() * overOpacity + under.getGreen() * (1 - overOpacity),
                over.getBlue() * overOpacity + under.getBlue() * (1 - overOpacity));
    }

    private static String describe(Color color) {
        return "rgb(" + to255(color.getRed()) + "," + to255(color.getGreen()) + ","
                + to255(color.getBlue()) + ")";
    }

    private static int to255(double channel) {
        return (int) Math.round(channel * 255);
    }

    private static Item item(String id, double dragOrder, double w_in, double l_in,
            double x_px, double y_px, String color) {
        Item item = new Item();
        item.id = id;
        item.serial = dragOrder;
        item.dragOrder = dragOrder;
        item.w_in = w_in;
        item.l_in = l_in;
        item.h_in = 12;
        item.x_px = x_px;
        item.y_px = y_px;
        item.baseHeight_in = 0;
        item.planned = false;
        item.name = "";
        item.customId = "";
        item.color = color;
        return item;
    }
}
