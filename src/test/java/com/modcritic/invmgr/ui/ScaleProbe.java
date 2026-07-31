package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.Item;
import java.io.File;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

class ScaleProbe extends ApplicationTest {
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
        Item box = new Item();
        box.id = "item-id-11111111-2222-4333-8444-555555555555";
        box.w_in = 24; box.l_in = 24; box.h_in = 12;
        box.x_px = 96; box.y_px = 96;
        box.color = "hsl(122,55%,42%)"; box.name = "Bin"; box.customId = "";
        interact(() -> { app.canvas().state().items.add(box); app.canvas().rebuildItems(); });
        WaitForAsyncUtils.waitForFxEvents();

        shot("probe-scale-100");
        for (int i = 0; i < 2; i++) {
            interact(this::notchUp);
        }
        WaitForAsyncUtils.waitForFxEvents();
        System.out.println("PROBE now at " + app.uiScalePercent() + "%");
        shot("probe-scale-150");
    }

    private void notchUp() {
        scene.getRoot().fireEvent(new ScrollEvent(ScrollEvent.SCROLL,
                0, 0, 0, 0, false, true, false, false, false, false,
                0, 40, 0, 40,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
    }

    private void shot(String name) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage img = WaitForAsyncUtils.waitForAsyncFx(5000, () -> scene.snapshot(null));
        File dir = new File("target/screenshots");
        dir.mkdirs();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(dir, name + ".png"));
    }
}
