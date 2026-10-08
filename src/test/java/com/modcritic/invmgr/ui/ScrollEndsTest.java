package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A scroll pane that stops at its ends.
 *
 * <p>On a desktop JavaFX's skin clamps its own bars, so the stretch past the end never happens
 * here. What the phone's skin does is set its bar to a value past the end; these tests do the
 * same, straight to the bar, and check it comes back inside.
 *
 * <p>⚠ Each push starts from the end it pushes past. The desktop skin copies the pane's clamped
 * value back onto its bar whenever that value changes, so a push from the top to past the bottom
 * gets clamped by the skin alone, and a test written that way passed with this class's clamp
 * deleted. Starting at the bottom, the pane's value does not change and only this class can
 * bring the bar back.
 */
class ScrollEndsTest extends ApplicationTest {

    private ScrollPane scroller;

    @Override
    public void start(Stage stage) {
        Region tall = new Region();
        tall.setPrefSize(200, 600);
        // A text box inside, with scroll bars of its own that are not this pane's.
        scroller = new ScrollPane(new VBox(new TextArea("inside"), tall));
        // Installed before there is a skin, the way MenuWindow does it in its constructor.
        ScrollEnds.install(scroller);
        stage.setScene(new Scene(scroller, 200, 200));
        stage.show();
    }

    /** Puts the bar at its end first, then past it: see the class comment for why both steps. */
    private void pushPastTheEnd(ScrollBar bar) {
        interact(() -> bar.setValue(bar.getMax()));
        interact(() -> bar.setValue(bar.getMax() + 0.4));
    }

    private ScrollBar bar(Orientation orientation) {
        List<ScrollBar> found = new ArrayList<>();
        ScrollEnds.forEachBar(scroller, found::add);
        return found.stream().filter(bar -> bar.getOrientation() == orientation).findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("pushed past the bottom, the bar comes back to it")
    void pastTheEnd() {
        ScrollBar bar = bar(Orientation.VERTICAL);
        pushPastTheEnd(bar);
        assertEquals(bar.getMax(), bar.getValue(), 1e-9);
    }

    @Test
    @DisplayName("pushed past the top, the bar comes back to it")
    void pastTheStart() {
        ScrollBar bar = bar(Orientation.VERTICAL);
        interact(() -> bar.setValue(bar.getMin() - 0.4));
        assertEquals(bar.getMin(), bar.getValue(), 1e-9);
    }

    @Test
    @DisplayName("inside its range, a value is left alone")
    void insideIsUntouched() {
        ScrollBar bar = bar(Orientation.VERTICAL);
        interact(() -> bar.setValue(0.37));
        assertEquals(0.37, bar.getValue(), 1e-9);
    }

    @Test
    @DisplayName("sideways as well, which is the Add dialog's preset strip")
    void sideways() {
        ScrollBar bar = bar(Orientation.HORIZONTAL);
        pushPastTheEnd(bar);
        assertEquals(bar.getMax(), bar.getValue(), 1e-9);
    }

    @Test
    @DisplayName("only the pane's own two bars: a text box inside keeps its own")
    void onlyItsOwnBars() {
        List<ScrollBar> found = new ArrayList<>();
        ScrollEnds.forEachBar(scroller, found::add);
        assertEquals(2, found.size());
        for (ScrollBar bar : found) {
            assertEquals(scroller, bar.getParent());
        }
    }
}
