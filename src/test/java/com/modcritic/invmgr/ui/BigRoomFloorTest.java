package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.model.Units;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * B11: a room too big for a texture still draws its floor.
 *
 * <p><b>The bug this pins.</b> The floor, its grid and its vignette used to be painted onto a
 * single {@code Canvas} sized to the room in room pixels, with nothing capping it. A canvas is a
 * raster, so that asked the renderer for a texture of the room's size times the screen's scale.
 * At 180 ft it passes the 16384 limit a phone reported at M6.1 and the floor renders <b>blank</b>,
 * measured on this machine at scale 1. On a phone at scale 3 the same wall arrives three times
 * sooner: a 30 ft x 40 ft room wanted 380 MB, took the graphics pool, and the 3D wall textures
 * then failed to allocate and drew black. That is what the user reported.
 *
 * <p><b>Why a size test rather than a memory test.</b> How much graphics memory a device has is
 * not knowable from here and differs per phone. The texture <em>limit</em> is not: past it the
 * allocation cannot succeed anywhere, so a room over it is the one case that fails identically on
 * every machine. Fixing it for this case is what fixes it for the phone's tighter one.
 *
 * <p>{@link com.modcritic.invmgr.model.Room} allows up to 200 ft, so these are legal rooms rather
 * than invented extremes.
 */
class BigRoomFloorTest extends ApplicationTest {

    private RoomCanvasView canvas;
    private AppState state;

    @Override
    public void start(Stage stage) {
        state = new AppState();
        state.room = new Room(20, 16, 8);
        canvas = new RoomCanvasView(state);
        stage.setScene(new Scene(new HBox(canvas), 1400, 900));
        stage.setMaximized(false);
        stage.show();
    }

    @Test
    @DisplayName("a room past the texture limit still paints its floor")
    void aRoomPastTheTextureLimitStillPaintsItsFloor() {
        // 180 ft is 17,280 room pixels, over the 16384 a phone reported and over what this
        // machine will allocate either. Bounded on the other side by a small room, or a change
        // that broke the floor everywhere would still pass the interesting half.
        assertTrue(floorIsPainted(20), "a small room's floor must paint");
        assertTrue(floorIsPainted(180), "and so must one past the texture limit");
        assertTrue(floorIsPainted(200), "up to the largest room the model allows");
    }

    @Test
    @DisplayName("the floor is not a raster, at any room size")
    void theFloorIsNotARasterAtAnyRoomSize() {
        // The rule underneath the test above, stated so that reintroducing a Canvas fails here
        // rather than only on a phone somebody happens to own. The original draws this with SVG
        // and reserves canvases for the 3D surfaces, which have their own 2048 cap.
        //
        // Asked of the LIVE SCENE GRAPH rather than of the accessors. `floorNode() instanceof
        // Canvas` does not compile at all, because the accessor's own type already rules it out,
        // and a check the compiler has finished with is not a test. Walking the room's children
        // catches the thing that would really happen: a canvas added back alongside them.
        setRoom(20, 16);
        for (javafx.scene.Node child : canvas.floorNode().getParent()
                .getChildrenUnmodifiable()) {
            assertFalse(child instanceof javafx.scene.canvas.Canvas,
                    "the room holds a Canvas again (" + child + "), which is a texture the size "
                            + "of the room and is what B11 was");
        }
    }

    @Test
    @DisplayName("the grid has one line per foot, and one node per line as the original does")
    void theGridHasOneLinePerFootAndOneNodePerLineAsTheOriginalDoes() {
        setRoom(12, 10);
        // Lines start one step in and stop before the far edge: 11 vertical across 12 ft and
        // 9 horizontal down 10 ft. One node each, because crossings must composite twice.
        assertTrue(canvas.gridNode().getChildren().size() == 11 + 9,
                "expected 20 grid lines, got " + canvas.gridNode().getChildren().size());
    }

    private void setRoom(double w, double l) {
        state.room = new Room(w, l, 8);
        interact(() -> canvas.rebuildRoom());
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Snapshots a corner of the floor and asks whether anything was actually drawn there. */
    private boolean floorIsPainted(double ft) {
        setRoom(ft, ft);
        WritableImage[] out = new WritableImage[1];
        interact(() -> {
            SnapshotParameters sp = new SnapshotParameters();
            sp.setViewport(new Rectangle2D(0, 0, 40, 40));
            out[0] = canvas.floorNode().snapshot(sp, null);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return out[0] != null
                && out[0].getPixelReader() != null
                && out[0].getPixelReader().getColor(20, 20).getOpacity() > 0;
    }

    /** Kept honest: the room really is as many pixels across as the test claims. */
    @Test
    @DisplayName("a 180 ft room really is past the limit these tests are about")
    void aOneHundredAndEightyFootRoomReallyIsPastTheLimit() {
        assertTrue(Units.feetToPx(180) > 16384,
                "if this ever stops being true the tests above stop testing anything");
    }
}
