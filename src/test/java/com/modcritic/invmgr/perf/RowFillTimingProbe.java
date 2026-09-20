package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.util.concurrent.TimeUnit;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * When does an item list row's text turn white?
 *
 * <p>{@code ItemListPanel.restyle} sets an unselected row to {@code Tokens.TEXT_PRIMARY}, which is
 * {@code #ccc}, and the original app agrees: its stylesheet gives {@code #fff} to
 * {@code .list-entry.selected} alone, and the brightest pixel anywhere in the list panel of its own
 * reference screenshot is 204, which is {@code #cc}. The shipped Java app draws every row at 255.
 * So something puts the color back after {@code restyle} has set it, and this says when.
 *
 * <p>Not a test. Run it deliberately: {@code mvn -B test -Dtest=RowFillTimingProbe}.
 */
class RowFillTimingProbe extends ApplicationTest {

    private App app;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(1600);
        stage.setHeight(900);
        stage.setX(0);
        stage.setY(0);
    }

    @Test
    void whenDoesItTurnWhite() {
        interact(() -> {
            app.state().items.clear();
            for (int i = 0; i < 4; i++) {
                Item item = new Item();
                item.id = "item-id-" + String.format("%036d", i);
                item.serial = i + 1;
                item.dragOrder = i + 1;
                item.x_px = 96 * (i + 1);
                item.y_px = 96;
                item.w_in = 24;
                item.l_in = 24;
                item.h_in = 18;
                item.color = "hsl(200,55%,42%)";
                item.name = "row" + i;
                app.state().items.add(item);
            }
            app.canvas().rebuildItems();
            app.listPanel().rebuild();
            say("straight after rebuild, still inside the same runnable");
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> say("after one round of FX events"));
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS);
        interact(() -> say("after half a second"));
        interact(() -> {
            app.listPanel().setSelectedId(null);
            say("after setSelectedId(null), which restyles every row");
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> say("and one more round of FX events after that"));
    }

    private void say(String when) {
        StringBuilder fills = new StringBuilder();
        for (Node child : ((Region) scroller().getContent()).getChildrenUnmodifiable()) {
            if (child instanceof Region region) {
                for (Node inner : region.getChildrenUnmodifiable()) {
                    if (inner instanceof Label label) {
                        fills.append(label.getText()).append('=').append(label.getTextFill())
                                .append("  ");
                    }
                }
            }
        }
        System.out.printf("PROBE %-52s %s%n", when, fills);
    }

    private ScrollPane scroller() {
        for (Node child : app.listPanel().getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane found) {
                return found;
            }
        }
        throw new IllegalStateException("no ScrollPane in the item list");
    }
}
