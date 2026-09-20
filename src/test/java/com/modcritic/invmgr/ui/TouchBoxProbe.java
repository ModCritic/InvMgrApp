package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.App;
import java.io.File;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.image.WritableImage;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A throwaway readout of every touch-layout box the user reported as the wrong size, printed beside
 * the number measured off the original's own screenshots.
 *
 * <p><b>Why this exists.</b> M6.4 verified the touch layout by reading constants out of the
 * original and checking they had been transcribed. That cannot see a constant applied to no node,
 * and it cannot see JavaFX laying out text at a different size from a browser told the same number,
 * which is between them the cause of four of the ten bugs the phone found. This prints what the
 * scene graph actually built, because on this project the numbers have never once been what
 * reasoning said they would be.
 *
 * <p>A <b>probe</b>, not a test: it asserts nothing and Surefire does not run it, because the suite
 * matches {@code *Test}. Run it by name when the numbers are wanted. Its output is the input to the
 * fixes, and once those land the assertions belong in {@code TouchLayoutTest} instead.
 *
 * <p>Targets below are CSS pixels measured by pixel-scanning {@code #555} button borders in
 * {@code ~/ClaudeWorkspace/mobile-touchscreen*.jpg}, 1080 x 2220 at scale 3, the user's own phone, so
 * device pixels divided by three. Two images agree on the island to within a pixel.
 */
class TouchBoxProbe extends ApplicationTest {

    /** The user's phone, in design pixels. */
    private static final int PHONE_WIDTH = 360;
    private static final int PHONE_HEIGHT = 740;

    private static String restorePlatform;

    private App app;
    private Stage stage;
    private Scene scene;

    @BeforeAll
    static void forceThePhoneLayout() {
        restorePlatform = System.getProperty(Device.OVERRIDE_PROPERTY);
        System.setProperty(Device.OVERRIDE_PROPERTY, "android");
    }

    /**
     * TestFX reuses one stage for the whole run, so a class that shrinks it to a phone has to put
     * it back (see {@code TouchLayoutTest}, which learned this the expensive way when five later
     * classes started clicking outside the window).
     */
    @AfterAll
    static void restoreThePlatform() {
        if (restorePlatform == null) {
            System.clearProperty(Device.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(Device.OVERRIDE_PROPERTY, restorePlatform);
        }
    }

    @Override
    public void start(Stage primary) {
        app = new App();
        app.start(primary);
        stage = primary;
        stage.setMaximized(false);
        stage.setWidth(PHONE_WIDTH);
        stage.setHeight(PHONE_HEIGHT);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    @Test
    void readOutEveryBoxTheUserReported() throws Exception {
        WaitForAsyncUtils.waitForFxEvents();

        System.out.println();
        System.out.println("=== TOUCH BOX PROBE: app vs the original's own screenshots ===");
        System.out.println("scene " + scene.getWidth() + " x " + scene.getHeight());
        System.out.println();
        header();

        TopBar bar = app.topBar();
        TouchIsland island = app.island();

        // The island. Targets from `mobile-touchscreen default view.jpg`, confirmed to within a
        // pixel by `... top-bar down.jpg`. Add and menu are the two the user called elongated.
        System.out.println("-- the island (island width " + island.getWidth() + ") --");
        row("island add  ■", island.addButton(), 43.0, 40.7);
        row("island undo", island.undoButton(), 57.0, 29.3);
        row("island fit", island.fitButton(), 39.0, 23.7);
        row("island plan", island.planButton(), 46.0, 23.7);
        row("island units", island.unitsButton(), 53.3, 23.7);
        row("island menu ▼", island.menuButton(), 35.0, 33.3);
        System.out.println("   island spacing = " + island.getSpacing() + "   (original 8.0)");

        // The bar's third row. Save and Load grow on touch; the 3D button is the one left behind.
        System.out.println();
        System.out.println("-- the top bar, row 3 (open the bar first) --");
        // interact(), because setBarOpen sets the arrow's text and a Labeled may only be touched on
        // the FX thread, the same rule TouchLayoutTest follows for every state change it makes.
        interact(() -> island.setBarOpen(true, false));
        WaitForAsyncUtils.waitForFxEvents();
        row("bar save", bar.saveButton(), 68.3, 37.0);
        row("bar load", bar.loadButton(), 68.3, 37.0);
        row("bar 3D", bar.threeDButtonNode(), 51.0, 38.7);

        // The status bar. The original wraps to two full lines; this one truncates.
        System.out.println();
        System.out.println("-- the status bar --");
        StatusBar status = app.statusBar();
        System.out.println("   bar width      = " + status.getWidth()
                + ", bar height " + status.getHeight());
        System.out.println("   bar prefHeight(360) = " + status.prefHeight(360)
                + ", prefHeight(-1) = " + status.prefHeight(-1));
        System.out.println("   text           = \"" + status.text() + "\"");
        Labeled label = firstLabeled(status);
        if (label != null) {
            System.out.println("   font           = " + label.getFont().getSize() + "   (original 11.0)");
            System.out.println("   wrapText       = " + label.isWrapText() + "   (original wraps: true)");
            System.out.println("   label width    = " + label.getWidth()
                    + ", height " + label.getHeight() + "   (original is TWO lines)");
            System.out.println("   text would need = " + textWidth(status.text(), label.getFont())
                    + " px on one line");
            System.out.println("   label prefHeight(340) = " + label.prefHeight(340)
                    + "   (one line is about 15.5)");
            System.out.println("   label maxWidth = " + label.getMaxWidth()
                    + ", prefWidth = " + label.getPrefWidth()
                    + ", textOverrun = " + label.getTextOverrun());
            System.out.println("   label layoutBounds = " + label.getLayoutBounds());
        }
        System.out.println("   the original's full line is:");
        System.out.println("     \"Tap: tooltip. Double-tap: edit. Hold 1.5s: delete.\"");
        System.out.println("     \"Drag: move.  ► layer  ◄ items  ▼ menu\"");

        shoot("probe-touch-bar-open");

        // The fold, sampled while it runs. The phone reports the Timeline getting 13-19 frames in
        // 250 ms (a real animation) and the user still sees a delayed snap. So the question is no
        // longer whether the property animates but whether anything MOVES while it does: if the bar's
        // own height climbs smoothly and the island below it stays put until the end, then the
        // layout is not being re-run per frame and the whole fold arrives at once.
        System.out.println();
        System.out.println("-- the fold, sampled every ~25 ms --");
        interact(() -> island.setBarOpen(false, false));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> island.setBarOpen(true, true));
        for (int i = 0; i < 14; i++) {
            Thread.sleep(25);
            double barHeight = WaitForAsyncUtils.waitForAsyncFx(1000, bar::getHeight);
            double islandTop = WaitForAsyncUtils.waitForAsyncFx(1000,
                    () -> island.localToScene(island.getBoundsInLocal()).getMinY());
            System.out.printf("   t=%3d ms   bar height %7.2f   island top %7.2f%n",
                    i * 25, barHeight, islandTop);
        }

        interact(() -> island.setBarOpen(false, false));
        WaitForAsyncUtils.waitForFxEvents();
        shoot("probe-touch-default");

        // The Add dialog, which is where Cancel and Confirm were coming out as "C...".
        System.out.println();
        System.out.println("-- the Add Item dialog --");
        clickOn(island.addButton());
        WaitForAsyncUtils.waitForFxEvents();
        for (Node node : lookup(".button").queryAll()) {
            if (node instanceof Button button && button.getText() != null
                    && !button.getText().isBlank()) {
                Bounds b = button.getBoundsInParent();
                System.out.printf("   %-10s %7.2f x %6.2f   font %.1f   text \"%s\"%n",
                        button.getText(), b.getWidth(), b.getHeight(),
                        button.getFont().getSize(), button.getText());
            }
        }
        shoot("probe-touch-add-dialog");

        System.out.println();
        System.out.println("=== END PROBE ===");
    }

    private static void header() {
        System.out.printf("   %-14s %8s %8s   %8s %8s   %7s %7s   %s%n",
                "what", "width", "height", "wantW", "wantH", "dW", "dH", "font / padding");
    }

    /**
     * One box, its target, and the two things that decide it (the font and the padding) because
     * when a box is the wrong size those are the only two places the difference can come from.
     */
    private static void row(String what, Node node, double wantW, double wantH) {
        Bounds b = node.getBoundsInParent();
        double w = b.getWidth();
        double h = b.getHeight();
        String detail = "";
        if (node instanceof Labeled labeled) {
            Font font = labeled.getFont();
            Insets pad = labeled.getPadding();
            detail = String.format("%.2f px / %.2f v %.2f h", font.getSize(), pad.getTop(), pad.getLeft());
            if (labeled instanceof Button button && button.getGraphic() == null) {
                detail += String.format("  [text \"%s\" measures %.2f]",
                        button.getText(), textWidth(button.getText(), font));
            } else if (labeled.getGraphic() != null) {
                Bounds g = labeled.getGraphic().getBoundsInParent();
                detail += String.format("  [graphic %.1f x %.1f]", g.getWidth(), g.getHeight());
            }
        }
        System.out.printf("   %-14s %8.2f %8.2f   %8.2f %8.2f   %+7.2f %+7.2f   %s%n",
                what, w, h, wantW, wantH, w - wantW, h - wantH, detail);
    }

    /** What one line of this text actually measures in this font: the browser's advance is 0.6 em. */
    private static double textWidth(String text, Font font) {
        Text probe = new Text(text);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    private static Labeled firstLabeled(javafx.scene.Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Labeled labeled) {
                return labeled;
            }
        }
        return null;
    }

    private void shoot(String name) throws Exception {
        // Two pulses before the picture. A snapshot renders whatever the last layout pass left, and
        // a node whose height depends on its width settles on the pass AFTER the width lands, so a
        // single pulse photographs the interface mid-thought. This cost an hour on the status bar,
        // where the measurements said two lines and the picture kept showing one.
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage img = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
        File dir = new File("target/screenshots");
        dir.mkdirs();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(dir, name + ".png"));
        System.out.println("   wrote target/screenshots/" + name + ".png");
    }
}
