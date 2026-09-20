package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * Where does every row of the item list actually start?
 *
 * <p>M6.7b's virtualized list holds its rows up on two spacers whose heights come from measuring
 * rows that do not exist. If those measurements disagree with what the layout does, rows land a
 * pixel off and nothing in the suite notices: the list is the right total height, the right rows
 * are on screen, and the text is a pixel out. That is what the picture comparison caught, at 4.3%
 * of the frame on two of six scroll positions.
 *
 * <p>This writes one number per row so the two builds can be diffed directly. Run it on each:
 *
 * <pre>
 *   DISPLAY=:99 mvn -B test -Dtest=RowTopsProbe -Dinvmgr.pictures=tops-before
 *   diff target/pictures/tops-before/tops.txt target/pictures/tops-after/tops.txt
 * </pre>
 */
class RowTopsProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;
    private static final int ITEMS = 120;

    private App app;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.setX(0);
        stage.setY(0);
    }

    @Test
    void writeEveryRowTop() {
        String label = System.getProperty("invmgr.pictures", "unlabeled");
        interact(() -> {
            app.state().room.w = 200;
            app.state().room.l = 200;
            app.state().items.clear();
            for (int i = 0; i < ITEMS; i++) {
                Item item = new Item();
                item.id = "item-id-" + String.format("%036d", i);
                item.serial = i + 1;
                item.dragOrder = i + 1;
                item.x_px = (2 + (i % 12) * 9) * 96;
                item.y_px = (2 + (i / 12) * 9) * 96;
                item.w_in = 24;
                item.l_in = 24;
                item.h_in = 12 + (i % 7) * 6;
                item.color = "hsl(" + (i * 17 % 360) + ",55%,42%)";
                item.name = i % 13 == 4
                        ? "row " + i + " with a name long enough that it has to wrap onto "
                                + "more than one line in a panel this narrow"
                        : String.format("row%03d", i);
                item.planned = i % 11 == 3;
                app.state().items.add(item);
            }
            app.canvas().rebuildItems();
            app.listPanel().rebuild();
        });
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(600, TimeUnit.MILLISECONDS);

        // Walk the whole list and record, for every row, where its top sits inside the content.
        // Read off the nodes rather than out of the panel, so the two builds are asked the same
        // question in the same way.
        List<String> lines = new ArrayList<>();
        int stops = 80;
        java.util.Map<String, Double> topOf = new java.util.TreeMap<>();
        java.util.Map<String, Double> opacityOf = new java.util.TreeMap<>();
        for (int i = 0; i <= stops; i++) {
            final double where = i / (double) stops;
            interact(() -> scroller().setVvalue(where));
            WaitForAsyncUtils.waitForFxEvents();
            WaitForAsyncUtils.sleep(30, TimeUnit.MILLISECONDS);
            interact(() -> {
                for (Node child : ((Region) scroller().getContent()).getChildrenUnmodifiable()) {
                    String name = nameOn(child);
                    if (name != null) {
                        topOf.put(name, Math.round(child.getBoundsInParent().getMinY() * 100) / 100.0);
                        opacityOf.put(name, Math.round(child.getOpacity() * 1000) / 1000.0);
                    }
                }
            });
        }
        topOf.forEach((name, top) -> lines.add(String.format("%-90s %10.2f  opacity %.3f",
                name, top, opacityOf.getOrDefault(name, -1.0))));
        lines.add(String.format("%-90s %10.2f", "== CONTENT HEIGHT ==",
                WaitForAsyncUtils.waitForAsyncFx(5000,
                        () -> scroller().getContent().getBoundsInLocal().getHeight())));

        Path out = Path.of("target", "pictures", label);
        try {
            Files.createDirectories(out);
            Files.write(out.resolve("tops.txt"), lines);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + out, e);
        }
        System.out.println("PROBE wrote " + lines.size() + " row tops to " + out.toAbsolutePath());
    }

    private static String nameOn(Node row) {
        if (!(row instanceof Region region)) {
            return null;
        }
        for (Node child : region.getChildrenUnmodifiable()) {
            if (child instanceof Label label) {
                return label.getText();
            }
        }
        return null;
    }

    private ScrollPane scroller() {
        for (Node child : app.listPanel().getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane found) {
                return found;
            }
        }
        throw new IllegalStateException("the item list has no ScrollPane in it any more");
    }
}
