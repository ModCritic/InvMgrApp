package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The scrollbar a phone gets, checked on a desktop by asking for it outright.
 *
 * <p>This machine has no touch screen, so {@link ScrollHint#around(ScrollPane)} would hand back the
 * bare scroll pane here. The two-argument form is told to draw, which is the phone's case. What
 * the phone actually shows is checked on the phone.
 */
class ScrollHintTest extends ApplicationTest {

    private ScrollPane scroller;
    private Region content;
    private Node hinted;

    @Override
    public void start(Stage stage) {
        content = new Region();
        content.setPrefSize(200, 600);
        scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        hinted = ScrollHint.around(scroller, true);
        VBox root = new VBox(hinted);
        root.setPrefSize(200, 200);
        scroller.setPrefHeight(200);
        stage.setScene(new Scene(root, 200, 200));
        stage.setX(0);
        stage.setY(0);
        stage.show();
    }

    private Region thumb() {
        return (Region) hinted.lookup("#" + ScrollHint.THUMB_ID);
    }

    @Test
    @DisplayName("with more than fits, the thumb shows, as long as the share that is visible")
    void itShowsWhenThereIsMore() {
        WaitForAsyncUtils.waitForFxEvents();
        Region thumb = thumb();
        assertTrue(thumb.isVisible());
        double viewport = scroller.getViewportBounds().getHeight();
        assertEquals(viewport * viewport / 600, thumb.getHeight(), 1.0,
                "a third of the content showing, a third of the track long");
        assertEquals(0, thumb.getLayoutY(), 0.5, "at the top before any scrolling");
        assertEquals(Tokens.SCROLL_HINT_WIDTH, thumb.getWidth(), 0.01);
        // Past the page's edge, in the margin, so it never covers the page's last letters.
        assertEquals(scroller.getWidth() + Tokens.SCROLL_HINT_GAP, thumb.getLayoutX(), 0.01);
    }

    @Test
    @DisplayName("scrolled to the end, the thumb is at the bottom of the track")
    void itFollowsTheScroll() {
        interact(() -> scroller.setVvalue(1));
        WaitForAsyncUtils.waitForFxEvents();
        double viewport = scroller.getViewportBounds().getHeight();
        assertEquals(viewport, thumb().getLayoutY() + thumb().getHeight(), 1.0);
    }

    @Test
    @DisplayName("when everything fits, there is no thumb")
    void itHidesWhenEverythingFits() {
        interact(() -> content.setPrefHeight(100));
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(thumb().isVisible());
    }

    @Test
    @DisplayName("it takes no touches: the content underneath still gets them")
    void itTakesNoTouches() {
        WaitForAsyncUtils.waitForFxEvents();
        StackPane stack = (StackPane) hinted;
        assertTrue(stack.getChildren().get(1).isMouseTransparent());
    }

    @Test
    @DisplayName("JavaFX's own bars are hidden and take no touches, so there is one bar, not two")
    void itHidesJavaFxsOwn() {
        WaitForAsyncUtils.waitForFxEvents();
        java.util.List<javafx.scene.control.ScrollBar> bars = new java.util.ArrayList<>();
        ScrollEnds.forEachBar(scroller, bars::add);
        assertEquals(2, bars.size());
        for (javafx.scene.control.ScrollBar bar : bars) {
            assertEquals(0, bar.getOpacity(), 1e-9);
            assertTrue(bar.isMouseTransparent());
        }
    }

    @Test
    @DisplayName("without a touch screen it adds nothing: the desktop draws its own scrollbar")
    void aDesktopGetsTheScrollPaneAlone() {
        ScrollPane bare = new ScrollPane();
        assertSame(bare, ScrollHint.around(bare, false));
    }
}
