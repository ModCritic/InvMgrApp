package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.sun.javafx.css.StyleManager;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextAreaSkin;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The phone's black text in an unfocused box, reproduced on the desktop, and the fix holding.
 *
 * <p><b>How the phone is imitated.</b> The phone's JavaFX adds {@code android.css}, a user-agent
 * stylesheet (the lowest level, below the app's own) that hands every text area its skin through
 * {@code -fx-skin}. This test adds one that does the same with {@link CssSkin}, through the same
 * internal {@code StyleManager} call the phone's startup code makes. Nothing else about the phone
 * matters: the bug is in JavaFX's CSS, and it shows the same way on a desktop given a skin that
 * arrives through CSS.
 *
 * <p><b>The canary.</b> {@link #aPlainTextAreaGoesBlackOnceFocusLeaves} asserts the bug itself on
 * a plain {@code TextArea}. It is what shows this setup still reproduces it; without it, the two
 * tests that assert the fix could pass because the setup had stopped doing anything. If it ever
 * fails, JavaFX no longer has the bug and {@link SkinnedTextArea} may no longer be needed.
 */
class SkinnedTextAreaTest extends ApplicationTest {

    /**
     * The skin the test stylesheet hands out, standing in for the phone's
     * {@code TextAreaSkinAndroid}. Public, with a public constructor taking the control, because
     * that is what JavaFX's {@code -fx-skin} loader looks for.
     */
    public static final class CssSkin extends TextAreaSkin {
        public CssSkin(TextArea area) {
            super(area);
        }
    }

    private static final Color TEXT = Color.web("#dddddd");

    /**
     * Anything in {@code src/main/java} that would make a multi-line box without the fix, written
     * with or without its package: a guard that only knew the short name let
     * {@code extends javafx.scene.control.TextArea} through when it was tried on purpose.
     */
    private static final Pattern PLAIN_TEXT_AREA = Pattern.compile(
            "(new\\s+|extends\\s+)(javafx\\.scene\\.control\\.)?TextArea\\b(?!\\.)"
                    + "|(javafx\\.scene\\.control\\.)?\\bTextArea::new");

    private VBox root;
    private TextField elsewhere;
    private Path stylesheet;
    private String stylesheetUrl;

    /** The two orders the app puts a box on screen in, before focusing it. */
    private enum Order {
        /**
         * {@code MenuWindow.showPage}: a CSS pass by hand ({@code applyCss}), then focus at once.
         * On the phone the Feedback box went black every time.
         */
        FEEDBACK_PAGE,
        /**
         * {@code AddItemDialog.onShown}: focus is asked for with {@code runLater}, and a pulse can
         * run first, which is the pass that brings the skin. On the phone the Name box went black
         * whenever one did. No {@code applyCss} here: a second pass before focus arrives would
         * repair the unfocused styles and hide the bug.
         */
        ADD_DIALOG
    }

    @Override
    public void start(Stage stage) {
        elsewhere = new TextField("12");
        root = new VBox(10, elsewhere);
        stage.setScene(new Scene(root, 400, 300));
        stage.show();
    }

    /**
     * ⚠ After the stage is showing, not before: showing a stage has JavaFX install its own list of
     * user-agent stylesheets again, and a rule added earlier was gone by the time the box arrived.
     * That cost a probe that silently tested nothing while this test was being written.
     */
    @BeforeEach
    void handTextAreasTheirSkinThroughCss() throws IOException {
        stylesheet = Files.createTempFile("skin-from-css", ".css");
        Files.writeString(stylesheet,
                ".text-area { -fx-skin: \"" + CssSkin.class.getName() + "\"; }\n");
        stylesheetUrl = stylesheet.toUri().toString();
        interact(() -> StyleManager.getInstance().addUserAgentStylesheet(stylesheetUrl));
        interact(elsewhere::requestFocus);
    }

    @AfterEach
    void removeTheStylesheet() throws IOException {
        interact(() -> StyleManager.getInstance().removeUserAgentStylesheet(stylesheetUrl));
        Files.deleteIfExists(stylesheet);
    }

    @Test
    void aPlainTextAreaGoesBlackOnceFocusLeaves() {
        for (Order order : Order.values()) {
            assertEquals(Color.BLACK, textFillAfterFocusLeaves(TextArea::new, order),
                    order + ": a plain TextArea kept its color, so this setup no longer reproduces "
                            + "the bug, and the two tests below cannot fail any more. If JavaFX "
                            + "fixed it, SkinnedTextArea may no longer be needed.");
        }
    }

    @Test
    void aSkinnedTextAreaKeepsItsColorOnceFocusLeaves() {
        for (Order order : Order.values()) {
            assertEquals(TEXT, textFillAfterFocusLeaves(SkinnedTextArea::new, order),
                    order + ": the text went back to its default once focus left, which is the "
                            + "phone's black text in an unfocused box. See SkinnedTextArea.");
        }
    }

    @Test
    void aSkinnedTextAreaHasItsSkinBeforeItJoinsAWindow() {
        interact(() -> {
            SkinnedTextArea area = new SkinnedTextArea();
            assertNotNull(area.getSkin(), "no skin yet, so the window's CSS will install one");
            assertEquals(CssSkin.class, area.getSkin().getClass(),
                    "the skin is not the one the platform's stylesheet asks for");
            assertNull(area.getScene(), "the throwaway scene is still attached");
        });
    }

    @Test
    void everyMultiLineBoxInTheAppIsASkinnedTextArea() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            offenders = files
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("SkinnedTextArea.java"))
                    .filter(file -> PLAIN_TEXT_AREA.matcher(read(file)).find())
                    .toList();
        }
        assertEquals(List.of(), offenders,
                "these make a plain TextArea, whose text goes black on the phone once focus "
                        + "leaves it; make them a SkinnedTextArea instead");
    }

    // ------------------------------------------------------------------------------ helpers

    /**
     * Puts a box on screen the way the app does, focuses it, moves focus away, and returns the
     * color its text is drawn in after that.
     */
    private Paint textFillAfterFocusLeaves(Supplier<TextArea> make, Order order) {
        TextArea area = WaitForAsyncUtils.waitForAsyncFx(5000, make::get);
        // The Add dialog's runLater landing straight after the pulse that brought the skin. Asked
        // from inside that pulse because a test cannot otherwise promise no second pulse runs
        // first, and a second one would repair the unfocused styles and hide the bug.
        boolean[] focusedYet = {false};
        Runnable focusAfterFirstPulse = () -> {
            if (!focusedYet[0]) {
                focusedYet[0] = true;
                area.requestFocus();
            }
        };
        interact(() -> {
            area.setText("abcd");
            area.setStyle("-fx-text-fill: #dddddd;");
            root.getChildren().add(area);
            if (order == Order.FEEDBACK_PAGE) {
                root.applyCss();
                area.requestFocus();
            } else {
                root.getScene().addPostLayoutPulseListener(focusAfterFirstPulse);
            }
        });
        pulse();
        pulse();
        interact(() -> root.getScene().removePostLayoutPulseListener(focusAfterFirstPulse));
        assertTrue(area.isFocused(), order + ": the box never took focus");
        assertEquals(CssSkin.class, area.getSkin().getClass(),
                order + ": the box's skin did not come from the test stylesheet, so nothing here "
                        + "is the phone's situation");
        assertEquals(TEXT, fill(area), order + ": the text is wrong while focused, too");

        interact(elsewhere::requestFocus);
        pulse();
        Paint after = fill(area);
        interact(() -> root.getChildren().remove(area));
        return after;
    }

    /** The color of the box's first line of text. */
    private static Paint fill(TextArea area) {
        for (Node node : area.lookupAll(".text")) {
            if (node instanceof Text text && "abcd".equals(text.getText())) {
                return text.getFill();
            }
        }
        throw new AssertionError("no line of text reading abcd in the box");
    }

    /**
     * Waits for one real pulse, the JavaFX frame in which CSS and layout run, because that is how
     * the app's boxes get styled after the first pass. Waiting on the event queue instead does not
     * promise a pulse happened in between.
     */
    private void pulse() {
        CountDownLatch done = new CountDownLatch(1);
        Runnable listener = done::countDown;
        interact(() -> {
            root.getScene().addPostLayoutPulseListener(listener);
            Platform.requestNextPulse();
        });
        try {
            assertTrue(done.await(5, TimeUnit.SECONDS), "no pulse arrived within five seconds");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a pulse", interrupted);
        }
        interact(() -> root.getScene().removePostLayoutPulseListener(listener));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }
}
