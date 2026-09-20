package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The "are you sure?" box, and whether it fits on the glass.
 *
 * <p>Reported by the user 2026-08-07 from the phone: the confirmation that deletes a preset is wider
 * than the screen. A JavaFX {@code Alert} comes out 419 pixels wide whatever it is asked, and the
 * phone's whole scene is 360.
 *
 * <p><b>These drive {@link Hints#confirmDialog} rather than {@code Hints.confirm}</b>, and they have
 * to: {@code confirm} ends in {@code showAndWait}, which blocks in a nested event loop until
 * somebody presses a button, and there is nobody here to press one. What is not given up by that
 * split is the mechanism: every case below <em>shows</em> a real dialog and measures what it
 * actually became, rather than reading back the property that was just set. Setting a maximum width
 * and having the window ignore it is exactly the failure that would otherwise pass.
 */
class ConfirmDialogTest extends ApplicationTest {

    /** The user's phone: 1080 real pixels at a scale of 3. */
    private static final double PHONE_WIDTH = 360;

    /** And its height, so the owner window here is the shape of the thing being tested. */
    private static final double PHONE_HEIGHT = 740;

    /**
     * Where the owner window is put. Anywhere but the origin, deliberately: a dialog placed by its
     * owner's center and a dialog placed by the screen's are the same number at 0, 0, and the
     * centering case below has to be able to tell those two apart.
     */
    private static final double OWNER_X = 200;
    private static final double OWNER_Y = 100;

    private Scene scene;
    private Stage owner;

    @Override
    public void start(Stage stage) {
        owner = stage;
        scene = new Scene(new Pane(), PHONE_WIDTH, PHONE_HEIGHT);
        stage.setScene(scene);
        // Pinned, because TestFX reuses one stage for the whole run and an earlier class may have
        // left it maximized: the trap M6.2 documented.
        stage.setMaximized(false);
        stage.setX(OWNER_X);
        stage.setY(OWNER_Y);
        stage.setWidth(PHONE_WIDTH);
        stage.setHeight(PHONE_HEIGHT);
        stage.show();
    }

    /** Shows the dialog, measures the pane, and takes it away again. */
    private double shownWidth(String question, double screenWidthPx) {
        double[] measured = new double[1];
        interact(() -> {
            Alert alert = Hints.confirmDialog(scene, question, screenWidthPx);
            alert.show();
            alert.getDialogPane().applyCss();
            alert.getDialogPane().layout();
            measured[0] = alert.getDialogPane().getWidth();
            alert.close();
        });
        return measured[0];
    }

    @Test
    @DisplayName("on a phone the question fits on the screen with a margin either side")
    void itFitsAPhone() {
        double width = shownWidth("Delete preset \"Big Blue Storage Bin\"?", PHONE_WIDTH);

        assertEquals(PHONE_WIDTH - 2 * Hints.SCREEN_MARGIN, width, 0.5,
                "the dialog must be the screen less its two margins");
        assertTrue(width < PHONE_WIDTH, "and it must actually fit: this is the reported bug");
    }

    @Test
    @DisplayName("on a desktop it is left exactly as JavaFX built it")
    void itLeavesADesktopAlone() {
        double capped = shownWidth("Delete preset \"Bin\"?", 2560);

        // Bounded on both sides, per the lesson from M4's autosave test that was satisfied by the
        // broken code as well as the working one. "Small enough" would pass at 100 px, which would
        // mean the fix had quietly shrunk every desktop dialog to nothing.
        assertEquals(419, capped, 1,
                "a wide screen must leave the dialog at the size JavaFX chose");
        assertTrue(capped < 2560 - 2 * Hints.SCREEN_MARGIN,
                "the cap is a maximum, not a preferred width: it must not stretch to the screen");
    }

    @Test
    @DisplayName("the width does not come from the question, so shortening the words fixes nothing")
    void theWidthIsNotTheTexts() {
        // This is the diagnosis, pinned. The obvious reading of "the dialog is too wide" is that
        // the message is too long, and the obvious fix is to wrap it or shorten it. Neither would
        // do anything: the pane is 419 px for three words and for a sentence alike, because
        // DialogPane computes a floor that has nothing to do with the content, and the content
        // label already wraps. Anybody who reverts the cap in favor of shortening the text should
        // fail here.
        double shortest = shownWidth("Delete?", 2560);
        double longest = shownWidth(
                "Delete preset \"A name long enough to run right off the side of a phone\"?", 2560);

        assertEquals(shortest, longest, 0.5,
                "an Alert is the same width whatever it says: the text is not the problem");
    }

    @Test
    @DisplayName("both buttons stay inside the phone's screen, which is the point of the whole fix")
    void theButtonsAreReachable() {
        interact(() -> {
            Alert alert = Hints.confirmDialog(scene, "Delete preset \"Big Blue Storage Bin\"?",
                    PHONE_WIDTH);
            alert.show();
            alert.getDialogPane().applyCss();
            alert.getDialogPane().layout();

            for (ButtonType type : new ButtonType[] { ButtonType.OK, ButtonType.CANCEL }) {
                javafx.scene.Node button = alert.getDialogPane().lookupButton(type);
                javafx.geometry.Bounds inPane =
                        alert.getDialogPane().sceneToLocal(button.localToScene(button.getBoundsInLocal()));
                assertTrue(inPane.getMaxX() <= PHONE_WIDTH,
                        type + " must be on the screen, not past its right edge");
                assertTrue(inPane.getMinX() >= 0, type + " must not be off the left edge either");
            }
            alert.close();
        });
    }

    /** Shows the dialog and reports where it landed and how wide it ended up. */
    private double[] shownPlacement(String question, double screenWidthPx) {
        double[] measured = new double[2];
        interact(() -> {
            Alert alert = Hints.confirmDialog(scene, question, screenWidthPx);
            alert.show();
            measured[0] = alert.getX();
            measured[1] = alert.getWidth();
            alert.close();
        });
        return measured;
    }

    /** Where JavaFX means to put a dialog this wide: the middle of the window it belongs to. */
    private double centeredOnOwner(double dialogWidth) {
        return owner.getX() + scene.getWidth() / 2 - dialogWidth / 2;
    }

    @Test
    @DisplayName("on a phone the question sits in the middle of the window, not off to one side")
    void itIsCenteredOnAPhone() {
        double[] placed = shownPlacement("Delete preset \"Big Blue Storage Bin\"?", PHONE_WIDTH);

        // The M6.3 regression, pinned. Setting only a maximum width leaves the dialog the right
        // size and placed as though it were still the size it wanted, which put it (419 - 344) / 2
        // = 37.5 px to the left, and on the phone, 29.5 px off the edge of the glass.
        assertEquals(centeredOnOwner(placed[1]), placed[0], 1,
                "the dialog must be centered on its owner window");
        assertTrue(placed[0] >= OWNER_X, "and nothing of it may hang off the left of the screen");
        assertTrue(placed[0] + placed[1] <= OWNER_X + PHONE_WIDTH,
                "nor off the right: bounded both ways, because 'not off the left' alone passes "
                        + "for a dialog pushed clean off the other side");
    }

    @Test
    @DisplayName("a desktop dialog is left where JavaFX put it, so the fix is a no-op up there")
    void itIsCenteredOnADesktopToo() {
        double[] placed = shownPlacement("Delete preset \"Bin\"?", 2560);

        assertEquals(419, placed[1], 1, "nothing should have been capped at this screen width");
        assertEquals(centeredOnOwner(placed[1]), placed[0], 1,
                "and an uncapped dialog was already centered: this case must stay a no-op");
    }

    @Test
    @DisplayName("the size and the placement are worked out from the same number")
    void theSizeAndThePlacementAgree() {
        // This is the diagnosis, pinned, the same way theWidthIsNotTheTexts pins the last one. A
        // dialog is SIZED by Stage.sizeToScene, which honors a maximum width, and PLACED by
        // HeavyweightDialog.positionStage, which reads dialogPane.prefWidth(-1) and does not. Set
        // only a maximum and those two numbers disagree by 75 px, which is the whole bug, and the
        // height goes with it, since positionStage computes prefHeight AT that preferred width.
        // Anybody who tidies setPrefWidth back into setMaxWidth should fail here.
        interact(() -> {
            Alert alert = Hints.confirmDialog(scene, "Delete preset \"Big Blue Storage Bin\"?",
                    PHONE_WIDTH);
            alert.show();
            assertEquals(alert.getWidth(), alert.getDialogPane().prefWidth(-1), 0.5,
                    "what the pane prefers and what the window became must be the same number");
            alert.close();
        });
    }
}
