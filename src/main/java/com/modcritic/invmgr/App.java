package com.modcritic.invmgr;

import com.modcritic.invmgr.engine.Items;
import com.modcritic.invmgr.engine.Stacking;
import com.modcritic.invmgr.engine.TextFormat;
import com.modcritic.invmgr.engine.UndoHistory;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import com.modcritic.invmgr.persist.AppDataDir;
import com.modcritic.invmgr.persist.Autosave;
import com.modcritic.invmgr.persist.AutosavePolicy;
import com.modcritic.invmgr.persist.SaveFormat;
import com.modcritic.invmgr.threed.RenderScale;
import com.modcritic.invmgr.threed.Transitions;
import com.modcritic.invmgr.ui.AddItemDialog;
import com.modcritic.invmgr.ui.AndroidBridge;
import com.modcritic.invmgr.ui.Clips;
import com.modcritic.invmgr.ui.Device;
import com.modcritic.invmgr.ui.DragGhost;
import com.modcritic.invmgr.ui.EditItemDialog;
import com.modcritic.invmgr.ui.Fonts;
import com.modcritic.invmgr.ui.ItemListPanel;
import com.modcritic.invmgr.ui.ItemTooltip;
import com.modcritic.invmgr.ui.LayerSliderDrawer;
import com.modcritic.invmgr.ui.Overlays;
import com.modcritic.invmgr.ui.PresetDialog;
import com.modcritic.invmgr.ui.RoomCanvasView;
import com.modcritic.invmgr.ui.StatusBar;
import com.modcritic.invmgr.ui.SystemInsets;
import com.modcritic.invmgr.ui.Tokens;
import com.modcritic.invmgr.ui.TopBar;
import com.modcritic.invmgr.ui.TopWrap;
import com.modcritic.invmgr.ui.TouchDrawers;
import com.modcritic.invmgr.ui.TouchIsland;
import com.modcritic.invmgr.ui.UiScale;
import com.modcritic.invmgr.ui.View3D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The InvMgr application window.
 *
 * <p>Assembles the interface and connects it: the top bar changes the room and the modes, the
 * canvas draws it, the layer slider slices it, the list panel indexes it, the dialogs edit it,
 * and the status bar says what happened. Each piece knows nothing about the others: they are
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

    /** The frame the bar sits in: its color, its bottom rule, and the phone's clock inset. */
    private TopWrap topWrap;

    /** The six-button strip under the bar. Null on a desktop, which has no need of one. */
    private TouchIsland island;

    /** The room with both panels sliding over it. Null on a desktop, where they are columns. */
    private TouchDrawers drawers;
    private StatusBar statusBar;
    private ItemListPanel listPanel;
    private View3D view3d;
    private UndoHistory undoHistory;
    private Stage stage;

    // ------------------------------------------------------ the 2D view, while 3D has it (M5.2)
    //
    // The original's `prev2dState`. Entering 3D forces Fit on so the room sits whole underneath
    // the view, and leaving has to undo that; otherwise you come back to a room fitted to the
    // window and scrolled to its top-left corner, having left it zoomed in on the one shelf you
    // were working on. Scroll is kept as ScrollPane's own 0..1 fractions rather than the
    // original's pixels, which is the same position expressed in the units JavaFX uses.

    private boolean previousFit;
    private double previousScrollX;
    private double previousScrollY;

    /** The Ctrl+scroll interface zoom. Applied to {@link #root}; see {@link UiScale}. */
    private final Scale uiScale = new Scale(1, 1, 0, 0);

    /** Which rung of {@link UiScale#STEPS_PERCENT} the interface is currently drawn at. */
    private int uiScaleIndex = UiScale.defaultIndex();

    // ------------------------------------------------------------------ autosave (M4)

    /** Where the app keeps its own copy of the room. Null switches autosave off entirely. */
    private Autosave autosave;

    private final AutosavePolicy autosavePolicy = new AutosavePolicy();

    /**
     * The last text handed to the autosave, and the entire change detector.
     *
     * <p>A change is noticed by serializing the state on a timer and comparing it to this, rather
     * than by every mutation calling something. A dirty flag would be cheaper and is the obvious
     * design, but there are a dozen places that change the state today and a thirteenth arrives
     * with every feature; one that forgets to raise the flag loses the user's work and nothing
     * announces it. Comparing the text cannot be forgotten by code that has not been written yet.
     * {@code SerializationCostTest} measures the price at the 500-item cap (0.63 ms, once a
     * second) and fails if that ever stops being negligible.
     *
     * <p>Set to {@code null} to force the next tick to treat the state as changed, which is how
     * a failed write gets retried.
     */
    private String lastAutosaved;

    /** One thread, so writes never overlap and never block the interface. */
    private ExecutorService autosaveWriter;

    /** The newest text waiting to be written; older ones are dropped rather than queued. */
    private final AtomicReference<String> pendingAutosave = new AtomicReference<>();

    /** Whether the last write failed, so the status bar reports it once and not every tick. */
    private volatile boolean autosaveFailing;

    private Timeline autosaveTimer;

    /** What to tell the user about the restore, held until the status bar exists to say it in. */
    private String startupNote;

    private Overlays root;
    private ItemTooltip tooltip;

    /** The 3D view's own tooltip. See wireThreeD for why it is a second instance. */
    private ItemTooltip tooltip3d;
    private DragGhost dragGhost;
    private AddItemDialog addDialog;
    private EditItemDialog editDialog;
    private PresetDialog presetDialog;

    @Override
    public void start(Stage stage) {
        // First, before any control exists. The fonts load themselves the moment anything reads
        // Tokens, so this is not strictly required, but pinning it to a known line means a badly
        // built jar fails here, with a stack trace and no window, instead of part-way through
        // building the interface. M6 also needs somewhere to set java.io.tmpdir before this runs;
        // Fonts explains why.
        Fonts.ensureLoaded();

        this.stage = stage;

        // Autosave is prepared before the state is chosen, because the state may come out of it.
        //
        // When nothing has already pointed it somewhere, the real app-data directory is used,
        // but only if JavaFX launched this App. A directly-constructed App is a UI test, and a
        // dozen test classes sharing one autosave would restore each other's rooms and fail in
        // ways that look nothing like the cause. AutosaveTest calls useAutosaveDirectory with a
        // temporary folder and so drives this same path deliberately, because a shipped path no
        // test ever takes is how M3.5 shipped a fix that could never have worked.
        if (autosave == null && getParameters() != null) {
            useAutosaveDirectory(AppDataDir.current());
        }
        backUpPreviousSession();
        state = chooseStartingState();

        undoHistory = new UndoHistory();
        canvas = new RoomCanvasView(state);
        sliderDrawer = new LayerSliderDrawer(state);
        topBar = new TopBar(state);
        statusBar = new StatusBar();

        topWrap = new TopWrap(topBar);
        if (Device.isTouch()) {
            island = new TouchIsland(topBar);
            topWrap.addIsland(island);
        }

        // The middle of the window is the same three pieces on both platforms, arranged two
        // different ways. On a desktop they are three columns side by side. On a phone the room
        // takes the whole width and the two panels hang off the edges, out of the layout; see
        // TouchDrawers for why 238 px of columns is not an option on a 360 px screen.
        HBox desktopMiddle = null;
        Region middle;
        if (Device.isTouch()) {
            drawers = new TouchDrawers(sliderDrawer, canvas);
            middle = drawers;
        } else {
            desktopMiddle = new HBox(sliderDrawer, canvas);
            HBox.setHgrow(canvas, Priority.ALWAYS);
            middle = desktopMiddle;
            // #main is `overflow: hidden` on a desktop too, and for the same reason: a window
            // dragged short enough leaves the layer slider taller than the row it is in, and
            // what spills out of the bottom would otherwise be drawn over the status bar and
            // take its clicks. See Clips.
            Clips.toOwnBox(desktopMiddle);
        }
        VBox.setVgrow(middle, Priority.ALWAYS);

        column = new VBox(topWrap, middle, statusBar);
        column.setStyle("-fx-background-color: " + Tokens.hex(Tokens.BODY_BG) + ";");

        // ⚠ THE INTERFACE MUST BE ALLOWED TO BE SHORTER THAN IT WOULD LIKE, or a phone on its
        // side loses both ends of it.
        //
        // A phone in landscape is 360 design pixels tall where portrait is 740, and the open
        // top bar is most of that on its own. Without these, the column's MINIMUM height came
        // to 540: Overlays is a StackPane, a StackPane cannot give a child less than its
        // minimum, so it handed the column 540 and CENTERED it: 90 pixels off the top and 90
        // off the bottom. The user reported exactly that, as "cut off in two directions", with
        // the status bar pushed off the end.
        //
        // The room is what yields, which is the right thing to give up: it already grows to
        // fill whatever is left, and it can be panned and zoomed. The bars cannot be panned.
        column.setMinHeight(0);
        middle.setMinHeight(0);

        // Everything that floats (dialogs, the tooltip, the drag ghost) lives above the app
        // in this stack, in a fixed order. See Overlays for why that order matters.
        root = new Overlays(column);
        tooltip = new ItemTooltip(root.tooltipLayer());
        dragGhost = new DragGhost(root.ghostLayer());
        listPanel = new ItemListPanel(state, dragGhost);
        if (drawers != null) {
            drawers.addItemList(listPanel);
        } else {
            desktopMiddle.getChildren().add(listPanel);
        }

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
        // the window divided by the zoom (its "logical" size), and the transform scales that
        // back up to fill the glass exactly.
        root.getTransforms().add(uiScale);
        // Unmanaged, or the Group would lay it out at its PREFERRED size on every layout pass and
        // silently undo the sizing below. That cost a real debugging round: the interface came up
        // at its preferred size, the room's scroll viewport measured zero, and Fit mode quietly
        // did nothing because it bails out when it cannot measure the viewport.
        root.setManaged(false);
        // The 3D view goes BESIDE the zoomed interface, not inside it. Its drawing surface has to
        // be exactly as many pixels as the window or the view comes out subtly stretched, and
        // anything inside `root` is scaled by the Ctrl+scroll zoom. See View3D for the longer
        // version, including why canceling the zoom again would be the M3.2 bug over.
        view3d = new View3D();
        // The 3D view's own tooltip, over its own layer; see wireThreeD for why it cannot be the
        // one built above. One line, no new interface code, and identical styling by
        // construction. It has to be built here rather than beside its 2D twin, because the view
        // that owns its layer does not exist until this line.
        tooltip3d = new ItemTooltip(view3d.tooltipLayer());
        Group scaleHost = new Group(root, view3d);

        Scene scene = new Scene(scaleHost, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.widthProperty().addListener((observable, before, after) -> resizeToLogicalSize(scene));
        scene.heightProperty().addListener((observable, before, after) -> resizeToLogicalSize(scene));
        scene.widthProperty().addListener((observable, before, after) -> resize3d(scene));
        scene.heightProperty().addListener((observable, before, after) -> resize3d(scene));
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
        // control, which left the app opening with a caret blinking in the width box, and a
        // focused width box means one stray keystroke changes the room size.
        canvas.requestFocus();

        wireThreeD(scene);

        // Last, so it reports over a built interface rather than into a status bar that does not
        // exist yet.
        startAutosave();
        if (startupNote != null) {
            statusBar.show(startupNote);
        }

        reportRenderingPipeline();
        SystemInsets insets = reserveSpaceForSystemBars();
        reportScreenGeometry(scene, insets);
        watchForRotation(scene);
        // One watcher for every text box in the window, so a new field cannot be forgotten.
        AndroidBridge.followTheFocusedField(scene);
        // Last of all, and only under -Dinvmgr.verbose=latency: nothing is installed otherwise.
        LatencyClock.installIfAsked(scene);
    }

    /**
     * Re-reads what the phone has taken whenever the screen changes shape.
     *
     * <p>Turning the phone on its side moves the navigation bar from the bottom to one of the
     * sides, so the four numbers reserved at startup stop being the right four. Without this the
     * room drew underneath the navigation buttons in landscape, which is what the phone showed on
     * 2026-09-03.
     *
     * <p>Width rather than height, and only on Android. The width of the scene changes on
     * rotation and does not change for anything else on a phone, where the window is the screen.
     * On a desktop the window is resized constantly and there is nothing to reserve anyway.
     */
    private void watchForRotation(Scene scene) {
        if (!Device.isAndroid()) {
            return;
        }
        scene.widthProperty().addListener((observable, was, now) -> reserveSpaceForSystemBars());
    }

    /**
     * Keeps the interface out from under the phone's own clock and navigation buttons.
     *
     * <p>Does nothing at all on a desktop, where there is nothing to keep out of the way of.
     *
     * <h2>Where the space goes, and why it is not one padding around everything</h2>
     *
     * <p>The obvious move is to pad the whole interface, and it is wrong in a way you can see. The
     * padding would show the window's own background behind it ({@code #1a1a1a}), and the top bar
     * is {@code #252525}, so a visibly different strip appears above the top bar with a seam across
     * it. That is exactly what the user reported off the first Android build.
     *
     * <p>So the space is given to <b>the bars themselves</b>. The top bar takes the top inset into
     * its own padding, which makes its own color extend up behind the clock and the two read as
     * one piece. The status bar takes the bottom inset the same way, and it was already the same
     * color as the window background, which is precisely why the user said the bottom edge
     * already looked right and only the top did not.
     *
     * <p><b>The 3D view is deliberately left full-bleed.</b> The picture keeps every pixel,
     * including the ones under the system bars, because a room drawn to the edges of the glass is
     * the whole point and matches the OD-1 spike the user liked. Only the things floating on top of
     * it (the return button, the controls hint, and the joystick when it arrives) move inward, so
     * they can still be reached and read.
     *
     * @return what was reserved, so the caller can report it
     */
    /**
     * Whether the insets above came from Android or from the old arithmetic.
     *
     * <p>Reported at startup because the two agree exactly on a phone held upright (24 and 48),
     * so the numbers alone cannot say which path ran, and knowing which one did is the whole
     * question OD-3 was open on.
     */
    private boolean insetsWereMeasured;

    /**
     * The whole 2D interface in one column, kept so the side insets can be applied to it.
     *
     * <p>Only landscape needs that: held upright a phone puts nothing down either side, which
     * is why this was a local variable until M6.5c-a.
     */
    private VBox column;

    private SystemInsets reserveSpaceForSystemBars() {
        if (!Device.isAndroid()) {
            return SystemInsets.NONE;
        }
        Screen primary = Screen.getPrimary();

        // Ask Android first. It knows, and until M6.5c there was no way to ask; see
        // ui/AndroidBridge and OD-3. The old arithmetic stays as the fallback for the two
        // cases the bridge cannot answer: a desktop forced into the phone layout with
        // -Dinvmgr.platform=android, and the moment before Android has attached the window.
        SystemInsets insets = AndroidBridge.systemInsets(primary.getOutputScaleY());
        insetsWereMeasured = insets != null;
        if (insets == null) {
            insets = SystemInsets.forAndroid(
                    primary.getBounds().getHeight(), primary.getVisualBounds().getHeight());
        }

        topWrap.reserveTop(insets.top());
        statusBar.reserveBottom(insets.bottom());
        view3d.setSafeArea(insets);

        // ⚠ THE SIDES, which nothing applied until M6.5c-a and which only landscape needs.
        //
        // Held upright a phone puts its bars top and bottom and both of these are zero, which
        // is why their absence was invisible for four milestones. Turned on its side the
        // navigation bar moves to one edge, and the room drew underneath it, reported by the
        // user as "landscape does not pad for the navigation bar".
        //
        // Applied to the column rather than to each bar, because all three of its rows need
        // clearing and the window's own background is the right thing to show beside the bar.
        // The 3D view is NOT in this column and stays full-bleed, which is deliberate: it gets
        // the same insets through setSafeArea above and moves only its floating controls.
        if (column != null) {
            column.setPadding(new javafx.geometry.Insets(0, insets.right(), 0, insets.left()));
        }

        // Printed on every read rather than only the first, because this runs again on every
        // rotation and the landscape numbers are the ones that were wrong before M6.5c.
        System.out.println("InvMgr: reserving " + insets
                + (insetsWereMeasured ? " (measured)" : " (standards)"));
        return insets;
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
     * <p>Order matters only in that all three have to happen together: the room un-scales itself
     * by exactly this factor, so if it were told a different number from the transform the room
     * would visibly change size, which is the one thing this feature must not do.
     */
    private void applyUiScale() {
        double factor = UiScale.factorAt(uiScaleIndex);
        uiScale.setX(factor);
        uiScale.setY(factor);
        canvas.setUiScale(factor);
        // The 3D view sits outside the one transform above, so it has to be told. Only the
        // chrome over the picture uses this: the picture stays at one pixel to one pixel.
        view3d.setUiScale(factor);
        if (stage.getScene() != null) {
            resizeToLogicalSize(stage.getScene());
        }
    }

    /**
     * Lays the interface out at the window size divided by the zoom.
     *
     * <p>The transform then scales that back up to exactly fill the window. Done by hand because
     * the interface sits in a {@link Group}, which does not size its child; see the comment where
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

        // Clicking a box opens its dialog, exactly as in the original: the room is the primary
        // way in, and selection comes from the list.
        canvas.setOnItemActivated(item -> editDialog.open(item));

        canvas.setOnItemHover((item, sceneX, sceneY) ->
                tooltip.show(TextFormat.tooltipText(state, item), sceneX, sceneY));
        canvas.setOnHoverEnded(tooltip::hide);

        // A finger has no hover, so tapping a box is how you ask what it is. Same text as the
        // desktop tooltip, placed above the box and timed out instead of following a pointer.
        canvas.setOnItemTapped((item, sceneX, sceneY) ->
                tooltip.showForTouch(TextFormat.tooltipText(state, item), sceneX, sceneY));

        // setSelectedId, not rebuild: replacing the rows mid-gesture is what kills
        // double-click-to-edit, which is the rule in CLAUDE.md §6.
        canvas.setOnSelectionChanged(() -> listPanel.setSelectedId(canvas.selectedId()));

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
            // Metric changes only what is shown; no stored measurement moves. Two things on
            // screen do change: the grid, from one-foot squares to one-meter squares, and the
            // list, because a search like "w20" now means 20 centimeters.
            canvas.rebuildRoom();
            sliderDrawer.rebuild();
            listPanel.rebuild();
            listPanel.setSelectedId(canvas.selectedId());
            addDialog.refreshUnitLabels();
        });

        // Planning Mode changes nothing that is already on screen (only what the next Add
        // does), so the button's own color is the entire effect.
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
        listPanel.setOverRoomTest(this::isOverTheRoomAndNothingElse);
        listPanel.setOnPlannedDropped(this::commitPlanned);
    }

    /**
     * Is this point over the room, and over <em>nothing that is covering it</em>?
     *
     * <p>Dropping a planned item decides between committing it to the room and putting the row back,
     * and the original decides with a <b>hit test</b>: {@code elementFromPoint(x, y)}, then "is that
     * element inside the canvas?" (original line 2005). {@code RoomCanvasView.isOverRoomArea} is a
     * <b>bounds</b> test, which is a different question the moment anything sits on top of the room.
     *
     * <p>On a desktop the two always agree, because the item list is a column <em>beside</em> the
     * room. <b>M6.4 made it a drawer that slides over the room</b>, and from that moment a point on
     * the open list was geometrically inside the canvas and the app committed the item: the user's
     * "dragging a planned item back to the item list should cancel, but it places it anyway". The
     * regression was created by the drawers and landed in code the drawers never touched, which is
     * why nothing caught it.
     *
     * <p>Only the open drawer is subtracted, because it is the only thing that can be over the room
     * while a drag is in progress: a dialog is modal and a tooltip is not pickable. That keeps this
     * a rule about the drag rather than a general-purpose picking routine JavaFX does not expose.
     */
    private boolean isOverTheRoomAndNothingElse(double sceneX, double sceneY) {
        if (drawers != null) {
            Region open = drawers.openDrawer();
            if (open != null && open.localToScene(open.getBoundsInLocal()).contains(sceneX, sceneY)) {
                return false;
            }
        }
        return canvas.isOverRoomArea(sceneX, sceneY);
    }

    // ------------------------------------------------------------- item actions

    /** Puts a new box in the room, or a new ghost in the list if Planning Mode is on. */
    private void addItem(double w_in, double l_in, double h_in, String name, String customId) {
        Item added = Items.add(state, undoHistory, w_in, l_in, h_in, name, customId);

        canvas.rebuildItems();
        sliderDrawer.rebuild();
        listPanel.rebuild();

        // A ghost is never selected on creation, because there is nothing on the canvas to
        // select: it exists only as a row in the list until it is dropped into the room.
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
        // The drop point is where the pointer is; the box should end up centered under it
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
     * mid-click is what kills double-click-to-edit; see {@link ItemListPanel}.
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
     * How much narrower than its screen a "maximized" window has to be before we conclude the
     * window manager has un-maximized it without telling JavaFX. A tenth is far outside anything
     * a panel or a rounding error could account for, and the real symptom is roughly half.
     */
    private static final double MAXIMIZED_WIDTH_FRACTION = 0.9;

    /**
     * Whether the window has been un-maximized behind JavaFX's back.
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
     * over a maximized JavaFX stage makes the window manager restore the owner to its
     * un-maximized size, while JavaFX's own {@code maximized} property stays {@code true}. That
     * mismatch is the whole reason it feels broken rather than merely wrong: because JavaFX still
     * believes the window is maximized, the first click on the titlebar's maximize button only
     * sets the flag back to false, and it takes a second click to actually maximize. It is
     * OpenJFX <a href="https://bugs.openjdk.org/browse/JDK-8325549">JDK-8325549</a>, also filed as
     * JDK-8332352, reported against JavaFX 21, 22 and 23, and specific to KWin; it does not
     * happen under GNOME Shell. The user hit it on 2026-07-30 and narrowed it themselves to Save,
     * Load and Export, which are the app's only three owned modal dialogs.
     *
     * <p><b>We cannot take the upstream fix.</b> OD-2 pins the project to Java 17 so the Android
     * native image can build, and while JavaFX 22 still targets 17, the bug is present there too.
     * So the repair has to live here.
     *
     * <p><b>Prevention on Linux, because repairing it is not good enough.</b> The first attempt
     * kept the owner and re-maximized afterwards. The user tested it on 2026-07-30: the flag
     * desync was fixed (one click on the maximize button worked again), but <em>the window still
     * visibly shrank</em>, because {@code setMaximized(false)} followed immediately by
     * {@code setMaximized(true)} collapses, and only the {@code false} reached the window manager.
     * Splitting the two across event pulses would probably land the maximize, but the result would
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
     * than the symmetry here: the bug is one platform's, so the workaround is too.
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
            // flag has to be cleared first, and the clear has to be processed before the set, or
            // the pair collapses and only the clear survives, which is exactly what was observed.
            Platform.runLater(() -> {
                stage.setMaximized(false);
                Platform.runLater(() -> stage.setMaximized(true));
            });
        }
        return chosen;
    }

    /**
     * The usable width of whichever screen the window is on, not simply the primary one, or the
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
        if (AndroidBridge.isAvailable()) {
            // The phone has no FileChooser at all; see saveOnAndroid.
            saveOnAndroid("room_inventory.json", AndroidBridge.JSON,
                    SaveFormat.save(state), "Saved.", "Save error: ");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save room");
        chooser.setInitialFileName("room_inventory.json");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Room inventory", "*.json"));

        File target = showChooser(chooser, true);
        if (target == null) {
            return;                                  // canceled; say nothing
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
        if (AndroidBridge.isAvailable()) {
            AndroidBridge.openFile(AndroidBridge.JSON);
            AndroidBridge.awaitFile(this::adoptLoadedText,
                    why -> statusBar.show("Load error: " + why));
            return;
        }
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
        if (AndroidBridge.isAvailable()) {
            saveOnAndroid("item_list.txt", AndroidBridge.PLAIN_TEXT,
                    TextFormat.exportAll(state), "Item list exported.", "Export error: ");
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

    /**
     * Writes a file through Android's own picker, which is the only way on a phone.
     *
     * <p><b>JavaFX's {@code FileChooser} does not merely look wrong here, it throws</b>: the
     * platform underneath implements no file chooser, so Save, Load and Export were dead at
     * three separate call sites. Android's picker needs no permissions, which is why it is
     * this rather than writing somewhere fixed: the app asks for nothing today.
     *
     * <p><b>The text is handed over before the user has chosen anywhere.</b> That looks
     * backwards and is forced: once the picker is up, only the Android side can act, because
     * nothing outside this app can call into it. So the room is serialized now and Android
     * writes it wherever it is pointed.
     *
     * <p>Backing out of the picker says nothing at all, matching what a dismissed file dialog
     * does on a desktop.
     */
    private void saveOnAndroid(String suggestedName, String mimeType, String content,
                               String done, String failedPrefix) {
        AndroidBridge.saveFile(suggestedName, mimeType, content);
        AndroidBridge.awaitFile(ignored -> statusBar.show(done),
                why -> statusBar.show(failedPrefix + why));
    }

    /**
     * Takes on a room that Android has just read out of a file the user picked.
     *
     * <p>Same checks as the desktop path, deliberately: a damaged file has to be reported
     * rather than adopted, and the message is the one {@code SaveFormat} produces.
     */
    private void adoptLoadedText(String text) {
        if (text == null) {
            statusBar.show("Load error: nothing was read.");
            return;
        }
        SaveFormat.LoadResult result = SaveFormat.load(text);
        if (!result.isSuccess()) {
            statusBar.show(result.error());
            return;
        }
        adopt(result.state());
        statusBar.show("Loaded.");
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

    // ------------------------------------------------------------------ autosave (M4)

    /**
     * Points the autosave at a directory, creating it if need be.
     *
     * <p>Must be called before {@link #start}, since the starting state can come from what is
     * already there. The app itself calls this with the real app-data directory; the autosave
     * test calls it with a temporary one so it drives exactly this code and not a copy of it.
     */
    public void useAutosaveDirectory(Path directory) {
        autosave = new Autosave(directory);
    }

    /** The autosave in use, or {@code null} when there is none. Exposed for the tests. */
    public Autosave autosave() {
        return autosave;
    }

    /**
     * Copies the previous session's autosave aside before this session can overwrite it.
     *
     * <p>Runs before the restore, and regardless of whether the file can be parsed. A file that
     * fails to load is the one most worth keeping a copy of, and it is about to be replaced.
     */
    private void backUpPreviousSession() {
        if (autosave == null) {
            return;
        }
        try {
            autosave.takeSessionBackup();
        } catch (IOException e) {
            // Not fatal and not worth a status line: the backups are a convenience, and the
            // autosave itself still works. Losing the app over a full disk would be worse.
            System.err.println("Could not back up the previous autosave: " + e.getMessage());
        }
    }

    /**
     * Where the room comes from on launch.
     *
     * <p>A file named on the command line wins, because that is the user asking for a specific
     * file by name and nothing should quietly override it. Otherwise the autosave is restored
     * <b>silently</b> (the user's decision, 2026-08-01): the app reopens where it was left, the
     * way a desktop application is expected to, with no prompt to answer every launch.
     *
     * <p>The undo history is deliberately not restored, matching what a Load already does.
     */
    private AppState chooseStartingState() {
        AppState fromArguments = stateFromArguments();
        if (fromArguments != null) {
            return fromArguments;
        }
        if (autosave == null) {
            return new AppState();
        }
        Autosave.Restored restored = autosave.restore();
        if (restored.problem() != null) {
            // Say so. The room coming up empty when it should not is exactly the moment a user
            // needs to know a backup exists rather than assuming their work is gone.
            System.err.println(restored.problem());
            startupNote = restored.problem() + " A backup is in " + autosave.backupDir() + ".";
            return new AppState();
        }
        if (!restored.hasState()) {
            return new AppState();
        }
        startupNote = "Restored your last session.";
        return restored.state();
    }

    /**
     * Starts the timer that notices changes and writes them.
     *
     * <p>One tick a second. The tick itself decides nothing about timing ({@link AutosavePolicy}
     * does), and it never writes on the FX thread, because a slow or network disk would freeze
     * the interface for as long as the write took.
     */
    private void startAutosave() {
        if (autosave == null) {
            return;
        }
        // Seed the comparison with what is already on screen, so a launch that changes nothing
        // writes nothing. After a restore this text is the file's own contents, since a load
        // followed by a save is byte-identical (M1).
        lastAutosaved = SaveFormat.save(state);

        autosaveWriter = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "invmgr-autosave");
            // Daemon: this thread must never be the reason the app fails to exit. The final
            // write in stop() is synchronous and does not depend on it.
            thread.setDaemon(true);
            return thread;
        });

        autosaveTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> autosaveTick()));
        autosaveTimer.setCycleCount(Animation.INDEFINITE);
        autosaveTimer.play();
    }

    /** Looks for a change, and writes if one is due. */
    private void autosaveTick() {
        long now = System.currentTimeMillis();
        String json = SaveFormat.save(state);
        if (!json.equals(lastAutosaved)) {
            lastAutosaved = json;
            autosavePolicy.noteChange(now);
        }
        if (autosavePolicy.isDue(now)) {
            autosavePolicy.noteWrite();
            queueAutosaveWrite(json);
        }
    }

    /**
     * Hands text to the writing thread, replacing anything still waiting.
     *
     * <p>Replacing rather than queueing matters on a slow disk: a queue would spend its time
     * writing states the user has already moved past, and the newest one (the only one that
     * matters) would be last in line.
     */
    private void queueAutosaveWrite(String json) {
        if (pendingAutosave.getAndSet(json) == null) {
            autosaveWriter.execute(this::drainAutosaveWrites);
        }
    }

    private void drainAutosaveWrites() {
        String json;
        while ((json = pendingAutosave.getAndSet(null)) != null) {
            try {
                autosave.write(json);
                autosaveFailing = false;
            } catch (IOException e) {
                reportAutosaveFailure(e);
            }
        }
    }

    private void reportAutosaveFailure(IOException failure) {
        boolean firstFailure = !autosaveFailing;
        autosaveFailing = true;
        System.err.println("Autosave failed: " + failure.getMessage());
        Platform.runLater(() -> {
            // Clearing this makes the next tick see a change and try again. Without it the state
            // matches what we believe was written, so nothing would ever retry.
            lastAutosaved = null;
            if (firstFailure && statusBar != null) {
                statusBar.show("Autosave failed: " + failure.getMessage());
            }
        });
    }

    /**
     * Writes one last time on the way out, on this thread.
     *
     * <p>Synchronous on purpose. Handing the final write to the background thread and returning
     * would race the JVM shutting down, and the work lost would be everything since the last
     * tick, the most recent thing the user did.
     */
    @Override
    public void stop() {
        if (autosaveTimer != null) {
            autosaveTimer.stop();
        }
        if (autosave != null) {
            try {
                String json = SaveFormat.save(state);
                if (!json.equals(lastAutosaved) || autosavePolicy.isDirty() || autosaveFailing) {
                    autosave.write(json);
                }
            } catch (IOException e) {
                System.err.println("Final autosave failed: " + e.getMessage());
            }
        }
        if (autosaveWriter != null) {
            autosaveWriter.shutdown();
            try {
                autosaveWriter.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ misc

    /**
     * Opens a save file if one was named on the command line.
     *
     * @return the loaded room, or {@code null} if no file was named or it could not be read
     */
    private AppState stateFromArguments() {
        // getParameters() is null unless JavaFX itself launched the class, which is not the case
        // when something constructs App directly; the UI tests do exactly that. Treat it as "no
        // arguments" rather than letting a NullPointerException take the window down before it
        // draws.
        Parameters parameters = getParameters();
        List<String> args = parameters == null ? List.of() : parameters.getRaw();
        if (args.isEmpty()) {
            return null;                             // nothing asked for; the autosave decides
        }
        Path path = Path.of(args.get(0));
        try {
            SaveFormat.LoadResult result = SaveFormat.load(Files.readString(path));
            if (!result.isSuccess()) {
                System.err.println(result.error() + " (" + path + ")");
                return failedToOpen();
            }
            return result.state();
        } catch (IOException e) {
            System.err.println("Could not read " + path + ": " + e.getMessage());
            return failedToOpen();
        }
    }

    /**
     * An empty room, for when a file was named on the command line and would not open.
     *
     * <p>Deliberately not {@code null}, which would fall through to the autosave. Someone who
     * names a file is asking for that file; quietly showing them a different room instead
     * (their last session, which looks plausible and is not what they asked for) would be worse
     * than an empty one and an error on the console.
     */
    private AppState failedToOpen() {
        return new AppState();
    }

    /**
     * Whether this machine can draw 3D at all.
     *
     * <p>If JavaFX falls back to its software renderer, 3D scenes draw <em>nothing</em>: no
     * exception, no warning, just black. On Android there is no software fallback at all, so if
     * this is ever false on a phone, the 3D view is simply gone.
     *
     * <p>A false result is reported loudly at startup and does not stop the app (OD-1 item 4,
     * built in M5.1). {@code reportRenderingPipeline} prints the unmissable lines and the 3D
     * button is grayed out; the 2D view is the primary interface and must keep working on a
     * machine with no usable 3D. That trade is §5.5 D-10.
     */
    public static boolean isScene3dSupported() {
        return Platform.isSupported(ConditionalFeature.SCENE3D);
    }

    private void reportRenderingPipeline() {
        boolean forced = "true".equalsIgnoreCase(System.getProperty(Launcher.PRISM_FORCE_GPU));
        // Touching Verbose here is what makes a mistyped -Dinvmgr.verbose report itself at
        // startup rather than never; see Verbose.summary.
        String verbose = Verbose.summary();
        // And the same for a mistyped -Dinvmgr.renderscale, for the same reason: reading it here
        // is what prints the complaint. It is in the line rather than only in the complaint so
        // that a bug report says what the room was drawn at.
        String renderScale = RenderScale.current().describe();
        System.out.println("InvMgr: java " + System.getProperty("java.version")
                + ", javafx " + System.getProperty("javafx.runtime.version")
                + ", scene3d " + isScene3dSupported()
                + ", forcegpu " + forced
                + ", renderscale " + renderScale
                + ", fonts " + Fonts.TEXT_FAMILY + " + " + Fonts.SYMBOL_FAMILY
                + (verbose.isEmpty() ? "" : ", verbose " + verbose));

        // Loud, and on its own lines, because the consequence is otherwise invisible: with no 3D
        // pipeline a 3D scene renders NOTHING and reports no error. One field buried in the line
        // above was enough while there was no 3D view to open; it is not enough now.
        if (!isScene3dSupported()) {
            System.out.println("InvMgr: ⚠ NO 3D PIPELINE ON THIS MACHINE.");
            System.out.println("InvMgr:   The 3D button is disabled. Everything else works.");
            System.out.println("InvMgr:   Prism fell back to software rendering, which cannot "
                    + "draw 3D at all.");
            // The old line here suggested -Dprism.forceGPU=true. The app now does that for itself
            // (see Launcher), so by the time anyone reads this the suggestion has already been
            // taken and failed, and repeating it would send somebody off to try what just did not
            // work.
            System.out.println(forced
                    ? "InvMgr:   Asking for the graphics card anyway was already tried, "
                            + "automatically, and did not help. This machine really has no 3D."
                    : "InvMgr:   Asking for the graphics card anyway is switched off here (-D"
                            + Launcher.FORCE_GPU_OVERRIDE + "=false). Drop that to let it try.");
        }
        // Nothing is printed on the healthy path beyond the `forcegpu` field above. A paragraph
        // about a switch that changed nothing, on every machine with a working graphics card, is
        // how startup output stops being read.
    }

    /**
     * Prints everything JavaFX is willing to say about the screen, so the system-bar sizes can be
     * checked against a real phone instead of assumed.
     *
     * <p>This exists because the layer that puts JavaFX on Android reports the bars to nobody:
     * its own source recommends a GPL-licensed library this project may not link (OD-3). So
     * {@link SystemInsets#forAndroid} works the navigation bar out from the gap between the
     * screen's full height and its "visual" height, and falls back to Android's published standard
     * when that gap is not believable. <b>Whether that subtraction actually yields anything on a
     * phone is the open question</b>, and these two lines are how it gets answered: on the user's
     * device, by reading them.
     *
     * <p>Printed on every platform, not only Android. A desktop's numbers are the control: there,
     * the visual bounds genuinely do exclude the taskbar, so seeing a sensible difference on a
     * desktop and none on the phone is itself the answer.
     */
    private void reportScreenGeometry(Scene scene, SystemInsets insets) {
        Screen primary = Screen.getPrimary();
        System.out.println("InvMgr: screen bounds " + primary.getBounds()
                + ", visual " + primary.getVisualBounds()
                + ", output scale " + primary.getOutputScaleX() + "x" + primary.getOutputScaleY()
                + ", dpi " + primary.getDpi());
        System.out.println("InvMgr: scene " + scene.getWidth() + "x" + scene.getHeight()
                + ", android " + Device.isAndroid()
                + ", reserving " + insets);
    }

    /**
     * Connects the 3D button to the 3D view, or, on a machine that cannot draw 3D, disables it and
     * says why.
     *
     * <p>Checked once here rather than when the button is pressed, so the answer is visible before
     * you commit to anything rather than after.
     */
    private void wireThreeD(Scene scene) {
        if (!isScene3dSupported()) {
            topBar.setThreeDUnavailable(
                    "3D unavailable: this machine has no 3D graphics pipeline");
            return;
        }

        topBar.setOnThreeD(this::enterThreeD);
        view3d.setOnReturn(this::leaveThreeD);
        // `root` is the whole 2D interface. Once the 3D picture is solid it covers every pixel of
        // the window, and JavaFX will happily go on drawing the room and every item node
        // underneath it forever; it does no occlusion culling. See View3D.descend.
        view3d.setBehind(() -> root.setVisible(false), () -> root.setVisible(true));

        // Resting the pointer on a box in the room names it, the same way resting on one in the
        // flat plan does: same class, same wording, same 14/10 offset from the cursor.
        //
        // A SECOND ItemTooltip rather than the existing one, and it has to be. The 2D tooltip is
        // drawn on `root`'s layer, and the line above hides `root` for exactly as long as the 3D
        // view is up, so that instance is invisible precisely when it would be wanted. Reusing
        // the class over View3D's own layer is the reuse; sharing the instance is not on offer.
        view3d.controls().setOnHover(
                (itemId, sceneX, sceneY) -> {
                    Item item = findItem(itemId);
                    if (item == null) {
                        tooltip3d.hide();
                        return;
                    }
                    tooltip3d.show(TextFormat.tooltipText(state, item), sceneX, sceneY);
                },
                tooltip3d::hide);

        // A finger has no hover, so on a phone tapping a box is the only way to ask what it is.
        // Same tooltip, same wording, different placement and a clock on it; see
        // ItemTooltip.showForTouch for the three reasons a fingertip needs all three.
        view3d.controls().setOnTap((itemId, sceneX, sceneY) -> {
            Item item = findItem(itemId);
            if (item != null) {
                tooltip3d.showForTouch(TextFormat.tooltipText(state, item), sceneX, sceneY);
            }
        });

        // The joystick, the touch wording of the controls hint and the 6 ft/s walking speed, all
        // from one answer. Read here rather than inside the view, so the view can be laid out
        // either way from a test without a system property the whole JVM would see.
        view3d.setTouchControls(Device.isTouch());
    }

    /** The item with this id, or null. A plain scan; see {@code ThreeDControls.updateHover}. */
    private Item findItem(String id) {
        for (Item item : state.items) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Everything that happens when the 3D button is pressed, in the order the original does it
     * ({@code enter3D}, original line 2947).
     *
     * <p>The 2D interface leaves first and the camera moves second, which is why this is two
     * stages with a pause between them rather than one block of code.
     */
    private void enterThreeD() {
        if (view3d.isOpen() || view3d.isAnimating()) {
            return;
        }
        Scene scene = topBar.getScene();
        if (scene == null) {
            return;
        }

        // Remembered before anything is changed, and put back on the way out. Dropping this is not
        // subtle: you come back from 3D to find the room fitted to the window and scrolled to the
        // top left, having left it zoomed in on the corner you were working on.
        previousFit = canvas.isFitMode();
        previousScrollX = canvas.getHvalue();
        previousScrollY = canvas.getVvalue();

        tooltip.hide();

        // Built NOW, before anything moves, and left invisible. It is the most expensive thing in
        // the whole transition (about 300 ms the first time in a run, while the graphics pipeline
        // compiles its shaders and uploads five wall textures), and it used to happen 370 ms in,
        // right on top of the chrome sliding and the picture fading up. Same total time either
        // way; this way it reads as the button taking a moment rather than the animation
        // stuttering. See View3D.prepare.
        view3d.prepare(state, scene);

        slideChromeAway();

        PauseTransition untilTheBarsAreGone =
                new PauseTransition(Duration.millis(Transitions.BARS_LEAVE_LAYOUT_MS));
        untilTheBarsAreGone.setOnFinished(event -> {
            setChromeInLayout(false);

            // Fit is forced on so the room is whole and centered underneath the 3D view, which is
            // what the ascent comes back to. Already on is not a no-op: the room has just grown
            // into the space the bars were using, so it has to be re-fitted to the new size.
            canvas.setFitMode(true);
            topBar.setFitActive(true);

            view3d.openWithDescent(state, scene,
                    () -> statusBar.show("3D view. Press the button top-right to come back."));
        });
        untilTheBarsAreGone.play();
    }

    /** The way back: the camera rises, then the 2D interface comes back exactly as it was. */
    private void leaveThreeD() {
        if (!view3d.isOpen() || view3d.isAnimating()) {
            return;
        }
        Scene scene = topBar.getScene();
        if (scene == null) {
            return;
        }

        view3d.closeWithAscent(state, scene, () -> {
            // Back into layout while still translated off-screen, so they slide in from the edge
            // rather than appearing in place. The original does exactly this, and for the same
            // reason: it removes `mode-3d-collapsed` first and `mode-3d` a frame later.
            setChromeInLayout(true);

            canvas.setFitMode(previousFit);
            topBar.setFitActive(previousFit);

            // Laid out NOW rather than left to the next pulse, and the order is the whole point.
            // A ScrollPane clamps the value it is given against the room it currently believes it
            // has; at this instant it still believes the room is the fitted, chrome-less one, so
            // setting the scroll first quietly lands somewhere else entirely. Measured: asking for
            // 0.6 gave back 0.248.
            root.applyCss();
            root.layout();

            if (!previousFit) {
                canvas.setHvalue(previousScrollX);
                canvas.setVvalue(previousScrollY);
            }

            Platform.runLater(this::slideChromeBack);
            canvas.requestFocus();
            statusBar.show("Back to 2D.");
        });
    }

    /**
     * Slides the four pieces of 2D chrome off their own edges of the screen.
     *
     * <p>{@link Transitions#SLIDE_FRACTION} of each one's own size, not a fixed number of pixels,
     * so a taller top bar still clears the edge, and 105% rather than 100% so borders and shadows
     * go with it instead of being left as a bright line along the edge.
     *
     * <p><b>On a phone the two panels are not slid at all</b>, and that is not an oversight. They
     * already live off the edges of the screen and their own translate is what holds them there;
     * sliding them here would fight it, and sliding them back to zero on the way out would leave
     * whichever drawer happened to be shut hanging open over the room. So they are simply closed,
     * and their tabs hidden, which is what the original does with {@code body.mode-3d}.
     */
    private void slideChromeAway() {
        slide(topWrap, 0, -topWrap.getHeight() * Transitions.SLIDE_FRACTION, Transitions.BAR_SLIDE_MS);
        slide(statusBar, 0, statusBar.getHeight() * Transitions.SLIDE_FRACTION,
                Transitions.BAR_SLIDE_MS);
        if (drawers != null) {
            drawers.closeAny();
            drawers.setTabsVisible(false);
            return;
        }
        slide(sliderDrawer, -sliderDrawer.getWidth() * Transitions.SLIDE_FRACTION, 0,
                Transitions.PANEL_SLIDE_MS);
        slide(listPanel, listPanel.getWidth() * Transitions.SLIDE_FRACTION, 0,
                Transitions.PANEL_SLIDE_MS);
    }

    private void slideChromeBack() {
        slide(topWrap, 0, 0, Transitions.BAR_SLIDE_MS);
        slide(statusBar, 0, 0, Transitions.BAR_SLIDE_MS);
        if (drawers != null) {
            drawers.setTabsVisible(true);
            return;
        }
        slide(sliderDrawer, 0, 0, Transitions.PANEL_SLIDE_MS);
        slide(listPanel, 0, 0, Transitions.PANEL_SLIDE_MS);
    }

    private static void slide(Node node, double toX, double toY, double millis) {
        TranslateTransition move = new TranslateTransition(Duration.millis(millis), node);
        move.setToX(toX);
        move.setToY(toY);
        move.play();
    }

    /**
     * Whether the 2D chrome takes up space.
     *
     * <p>Separate from sliding it, and it happens {@link Transitions#BARS_LEAVE_LAYOUT_MS} after
     * the slide starts rather than with it. The moment these stop being managed the room resizes
     * to fill the space they were using, and doing that while they are still visibly on their way
     * out would show the room jumping outward behind them.
     */
    private void setChromeInLayout(boolean inLayout) {
        for (Node piece : new Node[] {topWrap, statusBar, sliderDrawer, listPanel}) {
            piece.setManaged(inLayout);
            piece.setVisible(inLayout);
        }
    }

    private void resize3d(Scene scene) {
        view3d.fitToWindow(Math.max(1, scene.getWidth()), Math.max(1, scene.getHeight()));
    }

    /** The 3D view, for tests. */
    public View3D view3d() {
        return view3d;
    }

    /** The 3D view's own tooltip, for tests. Not the same object as {@link #tooltip()}. */
    public ItemTooltip tooltip3d() {
        return tooltip3d;
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

    /** The frame around the bar, what actually slides away when the 3D view opens. */
    public TopWrap topWrap() {
        return topWrap;
    }

    /** The six-button island, or null on a desktop. */
    public TouchIsland island() {
        return island;
    }

    /** The room with both panels sliding over it, or null on a desktop. */
    public TouchDrawers drawers() {
        return drawers;
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
