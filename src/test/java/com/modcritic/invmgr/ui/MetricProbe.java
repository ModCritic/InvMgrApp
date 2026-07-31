package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.App;
import java.io.File;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

class MetricProbe extends ApplicationTest {
    private App app;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        app = new App();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(2560);
        stage.setHeight(1440);
        stage.setX(0);
        stage.setY(0);
        scene = stage.getScene();
    }

    @Test
    void probe() throws Exception {
        clickOn(app.topBar().unitsButton());
        WaitForAsyncUtils.waitForFxEvents();
        System.out.println("PROBE metricMode=" + app.canvas().state().metricMode);
        WritableImage img = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
        File dir = new File("target/screenshots");
        dir.mkdirs();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(dir, "probe-metric.png"));
    }
}
