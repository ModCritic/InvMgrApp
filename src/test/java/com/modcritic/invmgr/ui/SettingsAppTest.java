package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.persist.SettingsFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The Settings window's one setting (Hide system bars), through the real app on the phone layout:
 * the toggle, the space it gives the room, and the file beside the autosave that carries it to the
 * next launch.
 *
 * <p>Launched per test, like {@code AutosaveAppTest}, because the file has to be in place before
 * the app starts for the startup case to mean anything. The bars themselves are the phone's, so
 * what is checked here is everything short of them: the setting, the space the layout reserves,
 * and the file. The device suite checks the bars.
 */
class SettingsAppTest extends ApplicationTest {

    @TempDir
    Path dataDir;

    private static String restorePlatform;
    private Stage stage;

    @BeforeAll
    static void forceThePhoneLayout() {
        restorePlatform = System.getProperty(Device.OVERRIDE_PROPERTY);
        System.setProperty(Device.OVERRIDE_PROPERTY, "android");
    }

    @AfterAll
    static void putThePlatformBack() {
        if (restorePlatform == null) {
            System.clearProperty(Device.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(Device.OVERRIDE_PROPERTY, restorePlatform);
        }
    }

    @Override
    public void start(Stage ignored) {
        // Deliberately empty: each test launches its own app, after arranging the folder.
    }

    @AfterEach
    void closeTheApp() {
        if (stage != null) {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    private App launchApp() {
        App app = onFxThread(() -> {
            App started = new App();
            started.useAutosaveDirectory(dataDir);
            stage = new Stage();
            started.start(stage);
            stage.setMaximized(false);
            stage.setWidth(1280);
            stage.setHeight(800);
            return started;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return app;
    }

    private void openSettings(App app) {
        clickOn(app.island().menuButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertNotNull(app.menuWindow().settingsEntry(), "a phone's Menu has Settings");
        clickOn(app.menuWindow().settingsEntry());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Path settingsFile() {
        return dataDir.resolve(SettingsFile.FILE_NAME);
    }

    @Test
    @DisplayName("hiding the bars gives the room their space, and writes the choice beside the autosave")
    void hidingTheBars() throws IOException {
        App app = launchApp();
        assertFalse(app.currentInsets().areNone(), "at the start the bars have their space");

        openSettings(app);
        assertEquals("Off", app.menuWindow().hideSystemBarsToggle().getText());
        clickOn(app.menuWindow().hideSystemBarsToggle());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(app.settings().hideSystemBars);
        assertEquals("On", app.menuWindow().hideSystemBarsToggle().getText());
        assertTrue(app.currentInsets().areNone(), "the room takes the space: " + app.currentInsets());
        assertTrue(Files.readString(settingsFile()).contains("hideSystemBars=true"),
                "and the file beside the autosave says so");
    }

    @Test
    @DisplayName("showing them again gives the space back")
    void showingTheBarsAgain() throws IOException {
        App app = launchApp();
        openSettings(app);
        clickOn(app.menuWindow().hideSystemBarsToggle());
        clickOn(app.menuWindow().hideSystemBarsToggle());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(app.settings().hideSystemBars);
        assertEquals(SystemInsets.ANDROID_STATUS_BAR, app.currentInsets().top());
        assertTrue(Files.readString(settingsFile()).contains("hideSystemBars=false"));
    }

    @Test
    @DisplayName("a launch whose settings file says so starts with the bars hidden")
    void theNextLaunchRemembers() throws IOException {
        Files.writeString(settingsFile(), "hideSystemBars=true\n");
        App app = launchApp();

        assertTrue(app.settings().hideSystemBars);
        assertTrue(app.currentInsets().areNone(), "no space is kept for bars that are hidden");
        assertEquals("On", app.menuWindow().hideSystemBarsToggle().getText());
    }

    private static <T> T onFxThread(Callable<T> work) {
        try {
            return WaitForAsyncUtils.asyncFx(work).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
