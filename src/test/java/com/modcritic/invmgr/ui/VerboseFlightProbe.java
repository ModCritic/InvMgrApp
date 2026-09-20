package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.Verbose;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Drives a real descent and ascent so that the lines {@code -Dinvmgr.verbose=3d} prints can be
 * read, rather than assumed from the pieces passing their own unit tests.
 *
 * <p>{@code Verbose} works out its topics once, when the class is first touched, which is the right
 * design and makes this awkward to test inside a shared test JVM. So it is run deliberately, with
 * the property handed to the forked JVM rather than to Maven:
 *
 * <pre>
 *   mvn -B test -Dtest=VerboseFlightProbe -DargLine="-Dinvmgr.verbose=3d"
 * </pre>
 *
 * <p>Without that argument it still passes and prints nothing, which is itself worth seeing: the
 * app must be silent for anyone who did not ask.
 */
class VerboseFlightProbe extends ApplicationTest {

    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(1600);
        stage.setHeight(900);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    @Test
    void flyThereAndBack() {
        System.out.println("PROBE topics on: \"" + Verbose.summary() + "\"");

        interact(() -> app.topBar().threeDButtonNode().fire());
        settle(4200);
        interact(() -> app.view3d().returnButton().fire());
        settle(2800);

        System.out.println("PROBE done; the lines above this are what an issue report would "
                + "carry");
    }

    private void settle(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            WaitForAsyncUtils.sleep(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }
}
