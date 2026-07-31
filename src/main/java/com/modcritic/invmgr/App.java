package com.modcritic.invmgr;

import com.modcritic.invmgr.engine.Items;
import com.modcritic.invmgr.engine.Stacking;
import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.engine.UndoHistory;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.persist.SaveFormat;
import com.modcritic.invmgr.ui.AddItemDialog;
import com.modcritic.invmgr.ui.DragGhost;
import com.modcritic.invmgr.ui.EditItemDialog;
import com.modcritic.invmgr.ui.ItemListPanel;
import com.modcritic.invmgr.ui.ItemTooltip;
import com.modcritic.invmgr.ui.LayerSliderDrawer;
import com.modcritic.invmgr.ui.Overlays;
import com.modcritic.invmgr.ui.PresetDialog;
import com.modcritic.invmgr.ui.RoomCanvasView;
import com.modcritic.invmgr.ui.StatusBar;
import com.modcritic.invmgr.ui.Tokens;
import com.modcritic.invmgr.ui.TopBar;
import com.modcritic.invmgr.ui.UiScale;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The InvMgr application window.
 *
 * <p>Assembles the interface and connects it: the top bar changes the room and the modes, the
 * canvas draws it, the layer slider slices it, the list panel indexes it, the dialogs edit it,
 * and the status bar says what happened. Each piece knows nothing about the others — they are
 * wired together here, so there is one place to look to see how an action propagates.
 *
 * <p><b>Why the wiring is all in one method.</b> Every one of these pieces could have been
 * handed a reference to the ones it needs, and each would then be a little simpler. The cost
 * would be that "what happens when a box is added" is scattered across six files and can only
 * be reconstructed by reading all of them. {@link #wire()} is deliberately the one place that
 * knows.
 */
public class App extends Application {

    /** The restored-down window size; the window opens filling the screen. */
    private static final int INITIAL_WIDTH = 1280;
    private static final int INITIAL_HEIGHT = 800;

    private AppState state;
    private RoomCanvasView canvas;
    private LayerSliderDrawer sliderDrawer;
    private TopBar topBar;
    private StatusBar statusBar;
    private ItemListPanel listPanel;
    private UndoHistory undoHistory;
    private Stage stage;

    /** The Ctrl+scroll interface zoom. Applied to {@link #root}; see {@link UiScale}. */
    private final Scale uiScale = new Scale(1, 1, 0, 0);

    /** Which rung of {@link UiScale#STEPS_PERCENT} the interface is currently drawn at. */
    private int uiScaleIndex = UiScale.defaultIndex();

    private Overlays root;
    private ItemTooltip tooltip;
    private DragGhost dragGhost;
    private AddItemDialog addDialog;
    private EditItemDialog editDialog;
    private PresetDialog presetDialog;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        state = loadStateFromArguments();

        undoHistory = new UndoHistory();
        canvas = new RoomCanvasView(state);
        sliderDrawer = new LayerSliderDrawer(state);
        topBar = new TopBar(state);
        statusBar = new StatusBar();

        HBox middle = new HBox(sliderDrawer, canvas);
        HBox.setHgrow(canvas, Priority.ALWAYS);
        VBox.setVgrow(middle, Priority.ALWAYS);

        VBox column = new VBox(topBar, middle, statusBar);
        column.setStyle("-fx-background-color: " + Tokens.hex(Tokens.BODY_BG) + ";");

        // Everything that floats — dialogs, the tooltip, the drag ghost — lives above the app
        // in this stack, in a fixed order. See Overlays for why that order matters.
        root = new Overlays(column);
        tooltip = new ItemTooltip(root.tooltipLayer());
        dragGhost = new DragGhost(root.ghostLayer());
        listPanel = new ItemListPanel(state, dragGhost);
        middle.getChildren().add(listPanel);

        addDialog = new AddItemDialog(root, state);
        editDialog = new EditItemDialog(root, state);
        presetDialog = new PresetDialog(root, state);

        wire();

        // The Ctrl+scroll zoom is one Scale transform on the whole interface, which is why every
        // control scales without a single measurement changing anywhere.
        //
        // It needs a Group between the scene and the interface. A scene resizes its root to the
        // window, and a scaled root would then draw a window's worth of content at 125% of a
        // window and overflow. A Group does not resize its child, so `root` is sized by hand to
        // the window divided by the zoom — its "logical" size — and the transform scales that
        // back up to fill the glass exactly.
        root.getTransforms().add(uiScale);
        // Unmanaged, or the Group would lay it out at its PREFERRED size on every layout pass and
        // silently undo the sizing below. That cost a real debugging round: the interface came up
        // at its preferred size, the room's scroll viewport measured zero, and Fit mode quietly
        // did nothing because it bails out when it cannot measure the viewport.
        root.setManaged(false);
        Group scaleHost = new Group(root);

        Scene scene = new Scene(scaleHost, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.widthProperty().addListener((observable, before, after) -> resizeToLogicalSize(scene));
        scene.heightProperty().addListener((observable, before, after) -> resizeToLogicalSize(scene));
        resizeToLogicalSize(scene);      // the listeners only fire on a change, so seed it here
        installUiScaleGestures(scene);
        stage.setTitle("InvMgr");
        stage.setScene(scene);
        // A room is 96 pixels to the foot, so even a modest 16 ft room needs 1536 px before
        // anything has to scroll. Starting windowed would mean scrollbars on first launch.
        stage.setMaximized(true);
        scene.getStylesheets().add(
                App.class.getResource("/com/modcritic/invmgr/ui/canvas.css").toExternalForm());
        stage.show();

        // Focus the room, not the first text field. JavaFX hands focus to the first traversable
        // control, which left the app opening with a caret blinking in the width box -- and a
        // focused width box means one stray keystroke changes the room size.
        canvas.requestFocus();

        reportRenderingPipeline();
    }

    /** Connects the pieces. Every cross-component effect in the app is in this one method. */
    // ------------------------------------------------------------- interface zoom

    /**
     * Ctrl+scroll to resize the interface, Ctrl+middle-click to put it back to 100%.
     *
     * <p>Both are registered as event <b>filters</b> on the scene, so they are seen before
     * anything else can act on them. That matters: the room is inside a scroll pane, and without
     * a filter a Ctrl+scroll over the room would be handled as a scroll and the room would move
     * instead of the interface resizing.
     *
     * <p>Only Ctrl. Shift+scroll and plain scroll are left alone for scrolling, and requiring
     * exactly Ctrl and nothing else keeps this from firing during some other modified gesture.
     */
    private void installUiScaleGestures(Scene scene) {
        scene.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown() || event.isShiftDown() || event.isAltDown()) {
                return;
            }
            // Some mice report tiny sub-notch deltas; anything non-zero is one step, since the
            // ladder has no finer resolution than a step anyway.
            if (event.getDeltaY() == 0) {
                return;
            }
            setUiScaleIndex(uiScaleIndex + (event.getDeltaY() > 0 ? 1 : -1));
            event.consume();
        });

        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.MIDDLE && event.isControlDown()) {
                setUiScaleIndex(UiScale.defaultIndex());
                event.consume();
            }
        });
    }

    /** Moves the interface to a rung of the zoom ladder, and reports it in the status bar. */
    private void setUiScaleIndex(int index) {
        int clamped = UiScale.clampIndex(index);
        if (clamped == uiScaleIndex) {
            return;                     // already at the end of the ladder: nothing to redraw
        }
        uiScaleIndex = clamped;
        applyUiScale();
        statusBar.show("Interface scale: " + UiScale.percentAt(uiScaleIndex) + "%");
    }

    /**
     * Pushes the current zoom into the transform, the layout, and the room's compensation.
     *
     * <p>Order matters only in that all three have to happen together — the room un-scales itself
     * by exactly this factor, so if it were told a different number from the transform the room
     * would visibly change size, which is the one thing this feature must not do.
     */
    private void applyUiScale() {
        double factor = UiScale.factorAt(uiScaleIndex);
        uiScale.setX(factor);
        uiScale.setY(factor);
        canvas.setUiScale(factor);
        if (stage.getScene() != null) {
            resizeToLogicalSize(stage.getScene());
        }
    }

    /**
     * Lays the interface out at the window size divided by the zoom.
     *
     * <p>The transform then scales that back up to exactly fill the window. Done by hand because
     * the interface sits in a {@link Group}, which does not size its child — see the comment where
     * that Group is created for why it has to be that way.
     */
    private void resizeToLogicalSize(Scene scene) {
        double factor = UiScale.factorAt(uiScaleIndex);
        root.resize(scene.getWidth() / factor, scene.getHeight() / factor);
    }

    /** The interface zoom, as a percentage. Exposed for tests. */
    public int uiScalePercent() {
        return UiScale.percentAt(uiScaleIndex);
    }

    private void wire() {
        canvas.setUndoHistory(undoHistory);
        canvas.setOnStatus(statusBar::show);
        sliderDrawer.setOnLayerChanged(() -> canvas.refreshVisibility());
        sliderDrawer.setOnStatus(statusBar::show);

        // A finished drag can change the tallest point in the room and re-sort the list, so
        // both are rebuilt rather than only the slider.
        canvas.setOnDragCommitted(() -> {
            sliderDrawer.rebuild();
            listPanel.rebuild();
            listPanel.setSelectedId(canvas.selectedId());
        });

        // Clicking a box opens its dialog, exactly as in the original — the room is the primary
        // way in, and selection comes from the list.
        canvas.setOnItemActivated(item -> editDialog.open(item));

        canvas.setOnItemHover((item, sceneX, sceneY) ->
                tooltip.show(TextFormat.tooltipText(state, item), sceneX, sceneY));
        canvas.setOnHoverEnded(tooltip::hide);

        topBar.setOnStatus(statusBar::show);

        topBar.setOnRoomChanged(() -> {
            canvas.rebuildRoom();
            sliderDrawer.rebuild();
            canvas.refreshItemAppearance();
        });

        topBar.setOnLayerCollisionChanged(() -> {
            // Turning it OFF is the moment everything falls into place, in one pass, using the
            // gravity ordering rather than the everyday one. Turning it on just freezes.
            if (!state.layerCollision) {
                Stacking.settleAllBaseHeights(state);
                sliderDrawer.rebuild();
            }
            canvas.refreshItemAppearance();
        });

        topBar.setOnFitChanged(() -> {
            canvas.setFitMode(!canvas.isFitMode());
            topBar.setFitActive(canvas.isFitMode());
            statusBar.show(canvas.isFitMode() ? "Fit mode on." : "Fit mode off.");
        });

        topBar.setOnUnitsChanged(() -> {
            // Metric changes only what is shown — no stored measurement moves. Two things on
            // screen do change: the grid, from one-foot squares to one-metre squares, and the
            // list, because a search like "w20" now means 20 centimetres.
            canvas.rebuildRoom();
            sliderDrawer.rebuild();
            listPanel.rebuild();
            listPanel.setSelectedId(canvas.selectedId());
            addDialog.refreshUnitLabels();
        });

        // Planning Mode changes nothing that is already on screen — only what the next Add
        // does — so the button's own colour is the entire effect.
        topBar.setOnPlanChanged(() -> { });

        topBar.setOnAdd(addDialog::show);
        topBar.setOnUndo(this::undo);
        topBar.setOnSave(this::save);
        topBar.setOnLoad(this::load);

        addDialog.setOnAdd(this::addItem);
        addDialog.setOnStatus(statusBar::show);
        addDialog.setOnDefinePreset(presetDialog::open);

        presetDialog.setOnSave((slot, preset) -> {
            state.presets.set(slot, preset);
            addDialog.refreshPresets();
            statusBar.show("Preset \"" + preset.name + "\" saved.");
        });

        editDialog.setOnApply(this::applyEdit);
        editDialog.setOnDelete(item -> canvas.deleteItem(item.id));
        editDialog.setOnSwap(this::swapItem);

        listPanel.setOnSelect(this::selectFromList);
        listPanel.setOnEdit(item -> editDialog.open(item));
        listPanel.setOnExport(this::exportItemList);
        listPanel.setOverRoomTest(canvas::isOverRoomArea);
        listPanel.setOnPlannedDropped(this::commitPlanned);
    }

    // ------------------------------------------------------------- item actions

    /** Puts a new box in the room, or a new ghost in the list if Planning Mode is on. */
    private void addItem(double w_in, double l_in, double h_in, String name, String customId) {
        Item added = Items.add(state, undoHistory, w_in, l_in, h_in, name, customId);

        canvas.rebuildItems();
        sliderDrawer.rebuild();
        listPanel.rebuild();

        // A ghost is never selected on creation, because there is nothing on the canvas to
        // select — it exists only as a row in the list until it is dropped into the room.
        if (!added.planned) {
            canvas.setSelectedId(added.id);
            listPanel.setSelectedId(added.id);
            canvas.scrollItemIntoView(added.id);
        }
        listPanel.scrollTo(added.id);
        statusBar.show("Added " + added.displayName() + (added.planned ? " (planned)" : ""));
    }

    private void applyEdit(Item item, String name, String customId, double w_in, double l_in,
            double h_in) {
        if (!Items.edit(state, undoHistory, item, name, customId, w_in, l_in, h_in)) {
            return;                        // nothing changed, so nothing to redraw
        }
        canvas.refreshItemAppearance();
        sliderDrawer.rebuild();
        // The name may have changed, which changes where the row sorts.
        listPanel.rebuild();
        listPanel.setSelectedId(canvas.selectedId());
    }

    private void swapItem(Item item) {
        Items.swap(state, undoHistory, item);
        canvas.refreshItemAppearance();
        sliderDrawer.rebuild();
    }

    /** Turns a ghost into a real box where it was dropped. */
    private void commitPlanned(Item item, double sceneX, double sceneY) {
        // The drop point is where the pointer is; the box should end up centred under it
        // rather than hanging off its bottom-right corner.
        Point2D inRoom = canvas.sceneToRoom(sceneX, sceneY);
        double preferredX = inRoom.getX() - com.modcritic.invmgr.model.Units.inchesToPx(item.w_in) / 2;
        double preferredY = inRoom.getY() - com.modcritic.invmgr.model.Units.inchesToPx(item.l_in) / 2;

        if (!Items.commit(state, undoHistory, item, preferredX, preferredY)) {
            return;
        }
        canvas.rebuildItems();
        canvas.setSelectedId(item.id);
        sliderDrawer.rebuild();
        listPanel.rebuild();
        listPanel.setSelectedId(item.id);
        canvas.scrollItemIntoView(item.id);
        statusBar.show("Committed " + item.displayName() + " to room.");
    }

    /**
     * Selects a box from its row in the list.
     *
     * <p>Note what this deliberately does <b>not</b> do: rebuild the list. Replacing the row
     * mid-click is what kills double-click-to-edit — see {@link ItemListPanel}.
     */
    private void selectFromList(Item item) {
        canvas.setSelectedId(item.id);
        listPanel.setSelectedId(item.id);
        canvas.scrollItemIntoView(item.id);
    }

    private void undo() {
        statusBar.show(canvas.undo());
        sliderDrawer.rebuild();
        listPanel.rebuild();
        listPanel.setSelectedId(canvas.selectedId());
    }

    // ------------------------------------------------------------ save / load

    /**
     * How much narrower than its screen a "maximised" window has to be before we conclude the
     * window manager has un-maximised it without telling JavaFX. A tenth is far outside anything
     * a panel or a rounding error could account for, and the real symptom is roughly half.
     */
    private static final double MAXIMIZED_WIDTH_FRACTION = 0.9;

    /**
     * Whether the window has been un-maximised behind JavaFX's back.
     *
     * <p>Split out from the window handling so the rule itself can be tested: the situation it
     * describes needs a window manager, and this container has none. See
     * {@link #showChooser} for what it is defending against.
     */
    static boolean needsMaximizeRepair(boolean maximizedFlag, double windowWidth,
            double screenWidth) {
        // No guard against a zero screen width, deliberately: a screen can measure 0 while the
        // stage is between monitors, and the comparison already covers it, since no positive
        // window width is less than zero. A `screenWidth > 0` guard was written here first and
        // removed when a deliberate mutation proved it could not change any answer.
        return maximizedFlag && windowWidth < screenWidth * MAXIMIZED_WIDTH_FRACTION;
    }

    /**
     * Shows a file dialog, and puts the window back if opening it shrank the window.
     *
     * <p><b>The bug this exists for is not ours.</b> On KDE/KWin, opening a window-modal dialog
     * over a maximised JavaFX stage makes the window manager restore the owner to its
     * un-maximised size — while JavaFX's own {@code maximized} property stays {@code true}. That
     * mismatch is the whole reason it feels broken rather than merely wrong: because JavaFX still
     * believes the window is maximised, the first click on the titlebar's maximise button only
     * sets the flag back to false, and it takes a second click to actually maximise. It is
     * OpenJFX <a href="https://bugs.openjdk.org/browse/JDK-8325549">JDK-8325549</a>, also filed as
     * JDK-8332352, reported against JavaFX 21, 22 and 23, and specific to KWin — it does not
     * happen under GNOME Shell. The user hit it on 2026-07-30 and narrowed it themselves to Save,
     * Load and Export, which are the app's only three owned modal dialogs.
     *
     * <p><b>We cannot take the upstream fix.</b> OD-2 pins the project to Java 17 so the Android
     * native image can build, and while JavaFX 22 still targets 17, the bug is present there too.
     * So the repair has to live here.
     *
     * <p><b>Prevention on Linux, because repairing it is not good enough.</b> The first attempt
     * kept the owner and re-maximised afterwards. The user tested it on 2026-07-30: the flag
     * desync was fixed — one click on the maximise button worked again — but <em>the window still
     * visibly shrank</em>, because {@code setMaximized(false)} followed immediately by
     * {@code setMaximized(true)} collapses, and only the {@code false} reached the window manager.
     * Splitting the two across event pulses would probably land the maximise, but the result would
     * still be a window that shrinks and then snaps back on every Save. What was asked for is that
     * it not move at all, and only prevention gives that.
     *
     * <p>So <b>on Linux the dialog is given no owner</b>, which means no transient-for relationship
     * for the window manager to react to. It costs nothing real: {@code showSaveDialog} blocks the
     * JavaFX thread either way, so the main window is unresponsive during the dialog whether it is
     * formally modal or not.
     *
     * <p><b>Windows and macOS keep the owner</b>, deliberately. Neither has the bug, and there an
     * unowned dialog would be a regression: the user could raise the main window in front of the
     * file dialog and find it frozen with no visible explanation. A platform check is worth more
     * than the symmetry here — the bug is one platform's, so the workaround is too.
     *
     * <p>The repair is kept as a safety net for anything this does not cover, now split across two
     * pulses so the re-assert can actually reach the window manager. It should never fire.
     */
    private File showChooser(FileChooser chooser, boolean saving) {
        // Owner-less only where the bug lives. `os.name` rather than JavaFX's own platform check,
        // which is in com.sun.* and not API.
        boolean linux = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("linux");
        Stage owner = linux ? null : stage;

        boolean wasMaximized = stage.isMaximized();
        File chosen = saving ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);

        if (wasMaximized && needsMaximizeRepair(stage.isMaximized(), stage.getWidth(),
                screenWidthFor(stage))) {
            // Two pulses. Setting `maximized` to the value it already holds is a no-op, so the
            // flag has to be cleared first — and the clear has to be processed before the set, or
            // the pair collapses and only the clear survives, which is exactly what was observed.
            Platform.runLater(() -> {
                stage.setMaximized(false);
                Platform.runLater(() -> stage.setMaximized(true));
            });
        }
        return chosen;
    }

    /**
     * The usable width of whichever screen the window is on — not simply the primary one, or the
     * check would misfire on a second monitor of a different size.
     */
    private static double screenWidthFor(Stage stage) {
        return Screen.getScreensForRectangle(stage.getX(), stage.getY(),
                        Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()))
                .stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds()
                .getWidth();
    }

    private void save() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save room");
        chooser.setInitialFileName("room_inventory.json");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Room inventory", "*.json"));

        File target = showChooser(chooser, true);
        if (target == null) {
            return;                                  // cancelled; say nothing
        }
        try {
            Files.writeString(target.toPath(), SaveFormat.save(state));
            statusBar.show("Saved.");
        } catch (IOException e) {
            // Reported, never thrown: a failed save must not take the window down with it.
            statusBar.show("Save error: " + e.getMessage());
        }
    }

    private void load() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open room");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Room inventory", "*.json"));

        File source = showChooser(chooser, false);
        if (source == null) {
            return;
        }
        try {
            SaveFormat.LoadResult result = SaveFormat.load(Files.readString(source.toPath()));
            if (!result.isSuccess()) {
                statusBar.show(result.error());
                return;
            }
            adopt(result.state());
            statusBar.show("Loaded.");
        } catch (IOException e) {
            statusBar.show("Load error: " + e.getMessage());
        }
    }

    /**
     * Writes the item list out as plain text.
     *
     * <p>The original hands the browser a download; here it is a save dialog, which is the
     * same thing with the destination made explicit. The file itself is byte-for-byte what the
     * original produces, right down to the trailing newline.
     */
    private void exportItemList() {
        if (state.items.isEmpty()) {
            statusBar.show("No items to export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export item list");
        chooser.setInitialFileName("item_list.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text file", "*.txt"));

        File target = showChooser(chooser, true);
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target.toPath(), TextFormat.exportAll(state));
            statusBar.show("Item list exported.");
        } catch (IOException e) {
            statusBar.show("Export error: " + e.getMessage());
        }
    }

    /** Replaces everything on screen with a freshly loaded room. */
    private void adopt(AppState loaded) {
        state = loaded;
        // A new room has no history, exactly as the original clears it on load.
        undoHistory.clear();
        canvas.setState(loaded);
        sliderDrawer.setState(loaded);
        topBar.setState(loaded);
        listPanel.setState(loaded);
        addDialog.setState(loaded);
        editDialog.setState(loaded);
        presetDialog.setState(loaded);
        canvas.rebuildRoom();
        sliderDrawer.rebuild();
        canvas.refreshItemAppearance();
    }

    // ------------------------------------------------------------------ misc

    /**
     * Opens a save file if one was named on the command line, otherwise starts with an empty
     * default room — the same 12 × 10 × 8 ft room the original opens with.
     */
    private AppState loadStateFromArguments() {
        // getParameters() is null unless JavaFX itself launched the class, which is not the case
        // when something constructs App directly — the UI tests do exactly that. Treat it as "no
        // arguments" rather than letting a NullPointerException take the window down before it
        // draws.
        Parameters parameters = getParameters();
        List<String> args = parameters == null ? List.of() : parameters.getRaw();
        if (args.isEmpty()) {
            return new AppState();
        }
        Path path = Path.of(args.get(0));
        try {
            SaveFormat.LoadResult result = SaveFormat.load(Files.readString(path));
            if (!result.isSuccess()) {
                System.err.println(result.error() + " (" + path + ")");
                return new AppState();
            }
            return result.state();
        } catch (IOException e) {
            System.err.println("Could not read " + path + ": " + e.getMessage());
            return new AppState();
        }
    }

    /**
     * Whether this machine can draw 3D at all.
     *
     * <p>If JavaFX falls back to its software renderer, 3D scenes draw <em>nothing</em> — no
     * exception, no warning, just black. On Android there is no software fallback at all, so if
     * this is ever false on a phone, the 3D view is simply gone.
     *
     * <p>M5 must turn a false result into a loud startup failure (OD-1 item 4). It only reports
     * for now, because the 2D view is the primary interface and must keep working on a machine
     * with no usable 3D.
     */
    public static boolean isScene3dSupported() {
        return Platform.isSupported(ConditionalFeature.SCENE3D);
    }

    private void reportRenderingPipeline() {
        System.out.println("InvMgr — java " + System.getProperty("java.version")
                + ", javafx " + System.getProperty("javafx.runtime.version")
                + ", scene3d " + isScene3dSupported());
    }

    public RoomCanvasView canvas() {
        return canvas;
    }

    public LayerSliderDrawer sliderDrawer() {
        return sliderDrawer;
    }

    public TopBar topBar() {
        return topBar;
    }

    public StatusBar statusBar() {
        return statusBar;
    }

    public ItemListPanel listPanel() {
        return listPanel;
    }

    public AddItemDialog addDialog() {
        return addDialog;
    }

    public EditItemDialog editDialog() {
        return editDialog;
    }

    public PresetDialog presetDialog() {
        return presetDialog;
    }

    public ItemTooltip tooltip() {
        return tooltip;
    }

    public DragGhost dragGhost() {
        return dragGhost;
    }

    public AppState state() {
        return state;
    }
}
