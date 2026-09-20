package com.modcritic.invmgr.perf;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Does {@code setTextFill} survive being called before the label is in a scene?
 *
 * <p>M6.7b's virtualized item list turned up a color that had been wrong since the list was
 * written: every row's text renders white, where the original gives white only to the selected row
 * and {@code #ccc} to the rest. The old panel built each row and called {@code restyle()} in the
 * constructor, before the node was anywhere; the new one binds most rows while they are already on
 * screen, and those come out the right color. This is the one-line experiment that says whether
 * that is really the difference.
 *
 * <p>Not a test. Run it deliberately: {@code mvn -B test -Dtest=TextFillProbe}.
 */
class TextFillProbe extends ApplicationTest {

    private HBox host;

    @Override
    public void start(Stage stage) {
        host = new HBox();
        Scene scene = new Scene(host, 400, 200);
        scene.getStylesheets().add(
                TextFillProbe.class.getResource("/com/modcritic/invmgr/ui/canvas.css")
                        .toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void doesTheFillSurviveJoiningTheScene() {
        WaitForAsyncUtils.waitForAsyncFx(10000, () -> {
            Label before = new Label("set before joining the scene");
            before.setTextFill(Color.web("#ccc"));
            System.out.println("PROBE set off-scene, read off-scene     -> " + before.getTextFill());
            host.getChildren().add(before);
            before.applyCss();
            System.out.println("PROBE set off-scene, read after joining -> " + before.getTextFill());

            Label after = new Label("set after joining the scene");
            host.getChildren().add(after);
            after.applyCss();
            after.setTextFill(Color.web("#ccc"));
            System.out.println("PROBE set in the scene                  -> " + after.getTextFill());
            after.applyCss();
            System.out.println("PROBE set in the scene, css re-applied  -> " + after.getTextFill());
            return null;
        });
    }
}
