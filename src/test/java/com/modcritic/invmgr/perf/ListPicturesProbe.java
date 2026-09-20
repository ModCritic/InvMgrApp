package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Photographs the item list at fixed scroll positions, so two builds can be held side by side.
 *
 * <p>Written for M6.7b's virtualized list. Behavior tests say the right item is acted on; they say
 * nothing about whether the panel <em>looks</em> the same, and a virtualized list is held up by two
 * spacers whose heights are computed rather than laid out. A spacer a pixel out would move every
 * row below it and no assertion in the suite would notice.
 *
 * <p>Run it once on each build, under a different label, then compare with
 * {@code tools/perf/comparepics.py}:
 *
 * <pre>
 *   DISPLAY=:99 mvn -B test -Dtest=ListPicturesProbe -Dinvmgr.pictures=list-before
 *   DISPLAY=:99 mvn -B test -Dtest=ListPicturesProbe -Dinvmgr.pictures=list-after
 *   python3 tools/perf/comparepics.py list-before list-after
 * </pre>
 *
 * <p>Everything is fixed: the names, the colors, the planned row, and the scroll positions. One
 * name is long enough to wrap onto several lines, because a row of a different height is the case
 * the spacers can get wrong.
 */
class ListPicturesProbe extends ApplicationTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;
    private static final int ITEMS = 120;

    /** Where to stop and photograph, as a fraction of the way down. */
    private static final double[] STOPS = {0.0, 0.17, 0.4, 0.63, 0.86, 1.0};

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
    void photographTheListAtSixScrollPositions() {
        String label = System.getProperty("invmgr.pictures", "unlabeled");
        Path out = Path.of("target", "pictures", label);
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException("could not make " + out, e);
        }

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
                // Three shapes of row, on a repeating cycle so every screenful has one of each:
                // an ordinary name, a name long enough to wrap, and a planned row.
                if (i % 13 == 4) {
                    item.name = "row " + i + " with a name long enough that it has to wrap onto "
                            + "more than one line in a panel this narrow";
                } else {
                    item.name = String.format("row%03d", i);
                }
                item.planned = i % 11 == 3;
                app.state().items.add(item);
            }
            app.canvas().rebuildItems();
            app.listPanel().rebuild();
            // One selected row, because selected is a different background and text color.
            app.listPanel().setSelectedId(app.state().items.get(7).id);
        });
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(600, TimeUnit.MILLISECONDS);

        System.out.printf("PROBE content height %.2f, viewport %.2f%n",
                WaitForAsyncUtils.waitForAsyncFx(5000,
                        () -> scroller().getContent().getBoundsInLocal().getHeight()),
                WaitForAsyncUtils.waitForAsyncFx(5000,
                        () -> scroller().getViewportBounds().getHeight()));
        for (int i = 0; i < STOPS.length; i++) {
            final double where = STOPS[i];
            interact(() -> scroller().setVvalue(where));
            WaitForAsyncUtils.waitForFxEvents();
            WaitForAsyncUtils.sleep(200, TimeUnit.MILLISECONDS);
            System.out.printf("PROBE stop %d (v=%.2f): first row on screen is %s%n",
                    i, where, firstNameOnScreen());
            dumpRows(i);
            write(out.resolve(String.format("list-%d.png", i)));
        }
        System.out.println("PROBE wrote " + STOPS.length + " pictures to " + out.toAbsolutePath());
    }

    /** The text of the topmost row actually showing through the viewport. */
    private String firstNameOnScreen() {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            double viewTop = scroller().localToScene(0, 0).getY();
            String best = null;
            double bestY = Double.MAX_VALUE;
            for (Node child : ((javafx.scene.layout.Region) scroller().getContent())
                    .getChildrenUnmodifiable()) {
                for (Node inner : child instanceof javafx.scene.layout.Region region
                        ? region.getChildrenUnmodifiable() : java.util.List.<Node>of()) {
                    if (inner instanceof javafx.scene.control.Label label) {
                        double y = child.localToScene(0, 0).getY();
                        if (y >= viewTop - 2 && y < bestY) {
                            bestY = y;
                            best = label.getText();
                        }
                    }
                }
            }
            return best;
        });
    }

    /** Every realized row at this stop, with what decides how bright its text comes out. */
    private void dumpRows(int stop) {
        interact(() -> {
            for (Node child : ((javafx.scene.layout.Region) scroller().getContent())
                    .getChildrenUnmodifiable()) {
                for (Node inner : child instanceof javafx.scene.layout.Region region
                        ? region.getChildrenUnmodifiable() : java.util.List.<Node>of()) {
                    if (inner instanceof javafx.scene.control.Label label) {
                        System.out.printf("ROW stop%d %-70.70s opacity %.3f fill %s nodeOpacity %.3f%n",
                                stop, label.getText(), child.getOpacity(),
                                label.getTextFill(), label.getOpacity());
                    }
                }
            }
        });
    }

    private ScrollPane scroller() {
        for (Node child : app.listPanel().getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane found) {
                return found;
            }
        }
        throw new IllegalStateException("the item list has no ScrollPane in it any more");
    }

    /** The panel alone, not the window: the room behind it is not what is being compared. */
    private void write(Path path) {
        WritableImage shot = WaitForAsyncUtils.waitForAsyncFx(30000,
                () -> app.listPanel().snapshot(null, null));
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(shot, null), "png", new File(path.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + path, e);
        }
    }
}
