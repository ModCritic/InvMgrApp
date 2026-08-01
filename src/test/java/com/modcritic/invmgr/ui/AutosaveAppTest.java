package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.App;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.persist.SaveFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Autosave as the running application actually uses it (M4).
 *
 * <p>{@code AutosaveTest} and {@code AutosavePolicyTest} cover the file and the timing on their
 * own. This file exists because those two passing proves nothing about whether the app is wired
 * to them — which is precisely how M3.5 shipped a fix that could never have worked in the real
 * app while its tests stayed green. So every test here launches a real {@link App} through its
 * real {@code start} method and lets it find, or fail to find, a real autosave on disk.
 *
 * <p>Each test launches its own app, rather than sharing one from the usual {@code start(Stage)}
 * hook, because what is being tested happens <em>during</em> startup and the directory has to be
 * arranged before the app opens.
 */
class AutosaveAppTest extends ApplicationTest {

    /** Long enough for a 1 s tick plus the 2 s quiet period, with room for a loaded machine. */
    private static final long WRITE_TIMEOUT_MS = 20_000;

    @TempDir
    Path dataDir;

    @Override
    public void start(Stage stage) {
        // Deliberately empty. See the class comment: the app under test is launched per test.
    }

    // ------------------------------------------------------------------ launching

    private App launchApp() {
        App app = onFxThread(() -> {
            App started = new App();
            started.useAutosaveDirectory(dataDir);
            Stage stage = new Stage();
            started.start(stage);
            // Undo the maximise, so a test machine's screen size cannot matter here.
            stage.setMaximized(false);
            stage.setWidth(1280);
            stage.setHeight(800);
            return started;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return app;
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

    private Path autosaveFile() {
        return dataDir.resolve("autosave.json");
    }

    private static AppState roomWithOneBox(String name) {
        AppState state = new AppState();
        Item item = new Item();
        item.id = "i7_7";
        item.serial = 1;
        item.dragOrder = 1;
        item.w_in = 24;
        item.l_in = 18;
        item.h_in = 12;
        item.x_px = 40;
        item.y_px = 60;
        item.color = "hsl(200,70%,50%)";
        item.name = name;
        item.customId = "";
        state.items.add(item);
        state.itemCounter = 1;
        state.dragOrderCounter = 1;
        return state;
    }

    // ------------------------------------------------------------------ restoring

    @Test
    void theLastSessionComesBackWithoutBeingAskedAbout() throws IOException {
        Files.writeString(autosaveFile(), SaveFormat.save(roomWithOneBox("Christmas decorations")));

        App app = launchApp();

        assertEquals(1, app.state().items.size(), "the saved room did not come back");
        assertEquals("Christmas decorations", app.state().items.get(0).name);
        // Silently, which is the whole decision: no dialog to answer, and nothing modal on top
        // of the app waiting for a click.
        assertFalse(app.addDialog().isShowing(), "a dialog opened on launch");
        assertFalse(app.editDialog().isShowing(), "a dialog opened on launch");
    }

    @Test
    void afirstEverLaunchOpensTheOrdinaryEmptyRoom() {
        App app = launchApp();

        assertTrue(app.state().items.isEmpty());
        assertEquals(12, app.state().room.w, 0.001);
        assertEquals(10, app.state().room.l, 0.001);
        assertEquals(8, app.state().room.h, 0.001);
    }

    @Test
    void adamagedAutosaveStillLetsTheAppOpenAndIsKept() throws IOException {
        Files.writeString(autosaveFile(), "{\"room\": {\"w\": 12,");

        App app = launchApp();

        assertTrue(app.state().items.isEmpty(), "a damaged file somehow produced a room");
        // The app is open and usable, which is the point — a broken autosave must never be the
        // reason someone cannot start the program at all.
        assertNotNull(app.canvas());
        // And the damaged file was copied aside before this session could overwrite it.
        Path backups = app.autosave().backupDir();
        assertTrue(Files.isDirectory(backups), "no backup was taken of the damaged autosave");
        assertEquals(1, Files.list(backups).count());
    }

    @Test
    void thePreviousSessionIsBackedUpBeforeThisOneCanOverwriteIt() throws IOException {
        Files.writeString(autosaveFile(), SaveFormat.save(roomWithOneBox("last week")));

        App app = launchApp();

        Path backup = Files.list(app.autosave().backupDir()).findFirst().orElseThrow();
        assertTrue(Files.readString(backup).contains("last week"));
    }

    // ------------------------------------------------------------------ writing

    @Test
    void aChangeIsWrittenWithoutAnyonePressingSave() throws IOException {
        App app = launchApp();

        // Nothing has happened yet, so nothing should have been written. A launch that writes
        // every time would churn the disk and defeat the "did anything change" test below.
        sleep(3_500);
        assertFalse(Files.exists(autosaveFile()),
                "an untouched app wrote an autosave it had no reason to write");

        onFxThread(() -> {
            app.state().items.add(roomWithOneBox("Garden tools").items.get(0));
            return null;
        });

        waitForAutosaveToContain("Garden tools");
    }

    @Test
    void theLastFewSecondsOfWorkAreWrittenOnTheWayOut() throws IOException {
        App app = launchApp();

        onFxThread(() -> {
            app.state().items.add(roomWithOneBox("Bike parts").items.get(0));
            return null;
        });
        // Straight out, well inside the quiet period, so the timer has certainly not written.
        // This is the write that saves whatever the user did in the seconds before they quit.
        onFxThread(() -> {
            app.stop();
            return null;
        });

        assertTrue(Files.exists(autosaveFile()), "quitting wrote nothing at all");
        assertTrue(Files.readString(autosaveFile()).contains("Bike parts"),
                "the work done just before quitting was not written");
    }

    @Test
    void loadingAFileMakesThatTheAutosavedRoom() throws IOException {
        // Autosave mirrors whatever is on screen, so anything that replaces the room — a Load,
        // a Set Room, an undo — flows through without needing to know autosave exists.
        App app = launchApp();

        onFxThread(() -> {
            app.state().items.add(roomWithOneBox("From a file").items.get(0));
            app.state().room.w = 20;
            return null;
        });

        waitForAutosaveToContain("From a file");
        assertTrue(Files.readString(autosaveFile()).contains("\"w\": 20"),
                "the room size change was not carried into the autosave");
    }

    // ------------------------------------------------------------------ helpers

    private void waitForAutosaveToContain(String fragment) throws IOException {
        long deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(autosaveFile()) && Files.readString(autosaveFile()).contains(fragment)) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("no autosave containing \"" + fragment + "\" appeared at "
                + autosaveFile() + " within " + WRITE_TIMEOUT_MS + " ms"
                + (Files.exists(autosaveFile())
                        ? "; the file holds: " + Files.readString(autosaveFile())
                        : "; the file was never created"));
    }
}
