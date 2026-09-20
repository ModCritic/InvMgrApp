package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.Verbose;
import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.CameraTween;
import com.modcritic.invmgr.threed.FlightClock;
import com.modcritic.invmgr.threed.Joystick;
import com.modcritic.invmgr.threed.Perspective;
import com.modcritic.invmgr.threed.RenderScale;
import com.modcritic.invmgr.threed.Transitions;
import com.modcritic.invmgr.threed.jfx.JfxRenderer3D;
import com.modcritic.invmgr.threed.jfx.ScaledSurface;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The 3D view as it sits in the window: the drawn room, the darkening around the edges of the
 * screen, and the button that takes you back.
 *
 * <p><b>It sits outside the interface zoom, on purpose.</b> Ctrl+scroll scales the whole 2D
 * interface with a single transform, and anything inside that transform is scaled with it. The 3D
 * drawing surface must not be: it has to be exactly as many pixels as the window, or its shape
 * would not match the window's and the view would be subtly stretched. So this is added beside the
 * zoomed interface rather than inside it.
 *
 * <p>That also means the 3D view does not zoom, which is correct: it is a camera you walk behind,
 * not an interface you make bigger. The original had no zoom control over it either.
 *
 * <p>The alternative, putting it inside and canceling the zoom again, is the shape of a bug this
 * project has already had once: at M3.2 the tooltip and the drag ghost were each handed a
 * coordinate in one space and positioned themselves in another, so the zoom applied twice and the
 * error grew the further across the window you went. Sitting outside the scaled part means there
 * is no second scale to get wrong.
 */
public final class View3D extends StackPane {

    /** How far in from the corner the return button sits, in pixels. */
    public static final double BUTTON_INSET = 14;

    /** The return button is square, and this is its side. */
    public static final double BUTTON_SIZE = 44;

    /** The return button's corner rounding: one of the app's five permitted exceptions. */
    public static final double BUTTON_RADIUS = 10;

    /** The icon inside the return button. */
    public static final double BUTTON_ICON_SIZE = 22;

    /**
     * What the controls hint says.
     *
     * <p>CLAUDE.md §5.5 <b>D-14</b>. The original has no on-screen control legend, but it also
     * has a visible status bar, and here the status bar has slid away, so the equivalent
     * information has nowhere else to live. Decided by the user 2026-08-05.
     */
    public static final String CONTROLS_HINT =
            "WASD move · Space up · C or Ctrl down · Shift boost · drag to look · Esc exits";

    /**
     * What the controls hint says on a phone.
     *
     * <p><b>Not a translation of the desktop line: a different set of facts.</b> Six of the seven
     * things that one names are things a thumb cannot do, and it is also 582 pixels wide against a
     * screen that is 360, so on the phone it was hanging off both edges. Measured, in the app's own
     * bundled typeface, rather than guessed: Noto Sans Mono at 12 px is 7.2 px a character, so a
     * line here has room for about forty-six.
     *
     * <p>Two lines rather than one, chosen by the user 2026-08-07 from three options. The one-line
     * versions either dropped the two-finger pan and the pinch (the two gestures nothing else in
     * the app would ever mention) or fitted all five with twenty-three pixels to spare, which is
     * no margin at all for a phone with a slightly different idea of what 12 px means.
     */
    public static final String CONTROLS_HINT_TOUCH =
            "Joystick moves · drag to look · tap a box\nTwo fingers pan · pinch moves you forward";

    /** How long the hint stays up before fading, in milliseconds. */
    public static final double HINT_HOLD_MS = 4000;

    /** Where the screen-wide darkening starts, as a fraction of the way out. */
    public static final double VIGNETTE_START = 0.45;

    /** How dark the screen-wide darkening gets in the corners. */
    public static final double VIGNETTE_MAX_ALPHA = 0.45;

    private final JfxRenderer3D renderer = new JfxRenderer3D();
    private final CameraPose camera = new CameraPose();
    private final Pane subSceneHost = new Pane();

    /**
     * Draws the room at fewer pixels than the window when asked to, and is free when not.
     *
     * <p>Off unless {@code -Dinvmgr.renderscale} says otherwise; see {@link RenderScale} for
     * why it is off by default and why {@code auto} watches frame times rather than asking
     * which graphics driver is in use.
     */
    private final ScaledSurface surface = new ScaledSurface();

    private final RenderScale renderScale = RenderScale.current();

    /**
     * The factor the room is being drawn at, kept across closing and reopening the 3D view.
     *
     * <p><b>⚠ Not reset when the view opens, and that is the whole point of the field.</b> Auto
     * decides from the descent, so a factor that reset on every entry would put the oscillation
     * back one step further out: enter at full and crawl, drop to a quarter, leave, enter at full
     * and crawl again. The never-increase rule in {@link RenderScale#factorFor} is worthless if
     * something resets the input to it.
     *
     * <p>The cost is that a small room entered after a big one keeps the big room's factor. That
     * is the lesser of the two, and it lasts until the app is started again.
     */
    private double factorInUse = renderScale.startingFactor();
    private final Rectangle vignette = new Rectangle();
    private final Pane tooltipLayer = new Pane();
    private final Label controlsHint = buildControlsHint();
    private final Button returnButton;

    /**
     * The thumbstick, built always and shown only on a phone.
     *
     * <p>The original creates it inside {@code if (isTouch())} and this does not, for the reason
     * OD-5 gives: one interface with a switch in it can be run in both positions from this
     * container, and a node that is never added to the scene is not something a test can look at.
     * It reaches the window only through {@link #setTouchControls}, so a desktop still has no
     * joystick in it anywhere.
     */
    private final JoystickView joystick = new JoystickView();

    /** Whether this window is being driven by fingers. See {@link #setTouchControls}. */
    private boolean touchControls;

    /** What the phone has taken of the screen. Held, because the hint's position depends on it. */
    private SystemInsets safeArea = SystemInsets.NONE;

    /**
     * Everything drawn <em>over</em> the 3D picture: the return button, the controls hint and the
     * tooltip layer.
     *
     * <p><b>These follow the Ctrl+scroll interface zoom; the picture itself does not.</b> That
     * split is the whole point. The drawing surface has to be exactly as many pixels as the window
     * or the view comes out stretched (which is why this class sits outside the zoom in the first
     * place), but a tooltip you cannot read is precisely what the zoom exists to fix. So the
     * picture stays at one-to-one and the interface on top of it scales.
     *
     * <p>Reported by the user 2026-08-06 against the M5.3 jar. The 3D tooltip and the hint had
     * silently inherited this class's exemption from the zoom, which nothing noticed because the
     * only test of the tooltip's position ran at 100%.
     *
     * <p>Built the same way {@code App} builds the 2D interface's zoom, including the two traps
     * that came with it: a {@link Group} between the scale and the content, because a scaled node
     * laid out to the window's size overflows it; and {@code setManaged(false)} on the content, or
     * the group re-lays it out at its preferred size every pass and silently undoes the sizing.
     */
    private final StackPane chrome = new StackPane();

    private final javafx.scene.Group chromeHost = new javafx.scene.Group(chrome);

    private final javafx.scene.transform.Scale chromeScale =
            new javafx.scene.transform.Scale(1, 1, 0, 0);

    /** The interface zoom, as a factor. 1 until {@code App} says otherwise. */
    private double uiScaleFactor = 1;

    /**
     * The keyboard and mouse while you are in the room.
     *
     * <p>Handed the live camera and a way to draw, and nothing else: it never learns what a room
     * inventory is. Attached when the room is built and detached when it is torn down, which is
     * the port of the original's {@code bindControls3d} / {@code unbindControls3d} pair.
     */
    private final ThreeDControls controls = new ThreeDControls(camera, this::render);

    private Runnable onReturn = () -> { };

    /**
     * What to hide once the 3D picture is solid, and show again before it starts fading out.
     *
     * <p>Set by {@code App} to the 2D interface. A {@link Runnable} pair rather than a reference to
     * the interface itself, because this class has managed not to know anything about the rest of
     * the app so far and there is no reason to start now.
     */
    private Runnable hideWhatIsBehind = () -> { };

    private Runnable showWhatIsBehind = () -> { };

    private boolean open;

    /**
     * True while the camera is on its way in or out.
     *
     * <p>The original's {@code busy3d}, and it guards the <b>transition</b>, not the view. That
     * distinction is the M5.1 bug 3 trap: a flag that guards the view instead ends up never being
     * cleared and the 3D view can be opened exactly once per run.
     */
    private boolean animating;

    /**
     * True from the moment the 3D button is pressed until the camera has landed.
     *
     * <p>Wider than {@link #animating}, and that width is the whole reason it exists. For the
     * first 990 ms of an entry (the chrome sliding, the picture fading up, the pause before
     * anything moves), there is no flight running at all, so {@code animating} is the only thing
     * that would say "a transition is under way" and it does not. Without this flag a control
     * pressed in that first second would be ignored, which is exactly the wait the skip is for.
     */
    private boolean entering;

    /**
     * Set when something asks for the transition to be over now. Read once per frame in
     * {@link #fly}, where it turns the progress into a flat 1.
     *
     * <p>See CLAUDE.md §5.5 <b>D-11</b>. <b>It is deliberately not cleared in
     * {@link #stopFlight()}</b>, which looks like the tidiest home and is a trap: {@code
     * stopFlight} runs at the top of {@code fly}, so clearing there would wipe a request made
     * during the fade a fraction of a second before the descent's timer starts, which is
     * precisely the case that has to work.
     */
    private boolean skipping;

    private javafx.animation.SequentialTransition hintSequence;

    private AnimationTimer flight;

    /** The fade and pause before the descent, held so a skip can cut them short. */
    private FadeTransition entryFade;

    private PauseTransition entryPause;

    /** What to run once the camera has landed, kept because the skip may jump the queue. */
    private Runnable pendingLanding = () -> { };

    public View3D() {
        setVisible(false);
        setManaged(false);
        setPickOnBounds(false);

        // Not pickable, or the darkening would swallow every click meant for the room behind it.
        vignette.setMouseTransparent(true);

        returnButton = buildReturnButton();
        // Pressing the return button must not also start a look-drag or toggle the pointer
        // capture on the way past. Set here rather than in a field initializer, which would run
        // before the button exists.
        controls.setIgnoreTarget(returnButton);
        controls.setPicker(this::pickItemAtScene);
        controls.setOnAnyInput(this::skipTransition);
        controls.setOnExitRequested(() -> onReturn.run());
        controls.setJoystickView(joystick);
        StackPane.setAlignment(returnButton, javafx.geometry.Pos.TOP_RIGHT);
        StackPane.setAlignment(controlsHint, javafx.geometry.Pos.BOTTOM_CENTER);
        StackPane.setAlignment(joystick, javafx.geometry.Pos.BOTTOM_LEFT);
        setSafeArea(SystemInsets.NONE);

        // Never in the way of a click, and always on top of everything else in here.
        tooltipLayer.setMouseTransparent(true);
        tooltipLayer.setPickOnBounds(false);

        // Everything drawn OVER the picture goes in one scaled group; the picture itself and the
        // darkening over it do not. See setUiScale.
        chrome.setPickOnBounds(false);
        chrome.setManaged(false);
        chrome.getTransforms().add(chromeScale);
        chrome.getChildren().addAll(returnButton, controlsHint, tooltipLayer);

        getChildren().addAll(subSceneHost, vignette, chromeHost);
    }

    /** What to run when the return button is pressed. */
    public void setOnReturn(Runnable handler) {
        this.onReturn = handler == null ? () -> { } : handler;
    }

    /**
     * What to hide while the 3D picture covers it, and what to show again before it uncovers.
     *
     * <p>Both must be given together or neither; hiding without showing would leave the app
     * looking at an empty window after the ascent.
     */
    public void setBehind(Runnable hide, Runnable show) {
        this.hideWhatIsBehind = hide == null ? () -> { } : hide;
        this.showWhatIsBehind = show == null ? () -> { } : show;
    }

    /** Whether the 3D view is currently showing. */
    public boolean isOpen() {
        return open;
    }

    /** Whether a descent or an ascent is running right now. */
    public boolean isAnimating() {
        return animating;
    }

    /**
     * Whether a request to land the transition is outstanding.
     *
     * <p>Exists so that "a request never outlives the journey it was made for" can be asserted.
     * Without it the flag is invisible from outside, and the mutation sweep duly showed that
     * deleting the clear in {@link #fly}'s arrival changed nothing any test could see; the clear
     * at the start of {@link #closeWithAscent} happened to cover the one path that was checked.
     * Two clears that each hide the other's absence are two clears nobody is testing.
     */
    public boolean isSkipRequested() {
        return skipping;
    }

    /** The camera, so the transitions in M5.2 can move it. */
    public CameraPose camera() {
        return camera;
    }

    /** The keyboard and mouse, so a test can ask what it thinks is held. */
    public ThreeDControls controls() {
        return controls;
    }

    /** The return button, so a test can find it and press it. */
    /**
     * Moves the things floating over the 3D picture inward, clear of the phone's system bars.
     *
     * <p><b>The picture itself is deliberately untouched.</b> The room keeps every pixel of the
     * screen, including the ones behind the clock and the navigation buttons, because a room drawn
     * right to the edges of the glass is the whole appeal of the 3D view and is what the OD-1 spike
     * looked like on the user's phone. Losing 216 pixels of a 2220-pixel screen to gray strips
     * would be a worse picture in exchange for nothing: nothing in the room needs to be reachable,
     * only looked at.
     *
     * <p>What does move is everything you have to reach or read: the return button, the controls
     * hint, and, since M6.3, the joystick. A return button under the clock is a return button that
     * opens the notification shade instead, and a joystick over the navigation bar is a thumb that
     * goes back to the home screen.
     *
     * <p>Called with nothing reserved from the constructor, so one place sets these margins.
     *
     * @param insets what the phone has taken, in design pixels
     */
    public void setSafeArea(SystemInsets insets) {
        this.safeArea = insets == null ? SystemInsets.NONE : insets;
        layOutOverlays();
    }

    /**
     * Puts the return button, the hint and the joystick where the phone leaves room for them.
     *
     * <p>Called from both {@link #setSafeArea} and {@link #setTouchControls}, because the hint's
     * position depends on both (see below), and one method that reads two fields cannot be got out
     * of step the way two methods each writing part of the answer can.
     */
    private void layOutOverlays() {
        StackPane.setMargin(returnButton, new javafx.geometry.Insets(
                BUTTON_INSET + safeArea.top(), BUTTON_INSET + safeArea.right(), 0, 0));

        // The joystick is the one of these you actually put a thumb on, so it is the one that most
        // needs to be clear of the navigation bar: 26 px above the usable area rather than 26 px
        // above the glass, or half of it sits over the phone's own back button.
        StackPane.setMargin(joystick, new javafx.geometry.Insets(
                0, 0, JoystickView.INSET_BOTTOM + safeArea.bottom(),
                JoystickView.INSET_LEFT + safeArea.left()));

        // ⚠ On a phone the hint is lifted clear over the top of the joystick. Found by looking at
        // the picture rather than by reasoning about it: the hint is 316 px wide on a 360 px screen
        // and centered, so its left end lands squarely on the stick, and it is on screen for the
        // first four seconds, which is exactly when somebody is working out what the stick is for.
        // The reference screenshots cannot show this, because the original has no hint at all.
        double aboveTheStick = touchControls
                ? JoystickView.INSET_BOTTOM + Joystick.BASE_DIAMETER_PX + BUTTON_INSET
                : BUTTON_INSET;
        StackPane.setMargin(controlsHint, new javafx.geometry.Insets(
                0, 0, aboveTheStick + safeArea.bottom(), 0));
    }

    /**
     * Lays the 3D view out for fingers instead of for a mouse and keyboard.
     *
     * <p>Three things change together, which is why they are one switch rather than three: the
     * joystick appears, the controls hint stops naming keys nobody has, and walking slows from
     * {@code Walk.MOVE_SPEED_FT_PER_S}'s 9 ft/s to {@code Walk.TOUCH_SPEED_FT_PER_S}'s 6.
     *
     * <p><b>Handed in rather than asked for.</b> {@code App} reads {@code Device.isTouch()} once and
     * tells the view; the view never asks. That is the arrangement {@code TopBar.reserveTop} already
     * uses for the system bars, and the reason is the same: a test can drive both positions in one
     * run without touching a system property that every other test in the JVM would also see.
     *
     * <p>The touch gestures themselves are listened for either way. A desktop never delivers a
     * {@code TouchEvent}, so there is nothing to switch off, and a Windows tablet is touch without
     * being Android; the distinction {@code Device} exists to keep.
     */
    public void setTouchControls(boolean touch) {
        this.touchControls = touch;
        controls.setTouch(touch);
        controlsHint.setText(touch ? CONTROLS_HINT_TOUCH : CONTROLS_HINT);

        boolean present = chrome.getChildren().contains(joystick);
        if (touch && !present) {
            // Before the tooltip layer, so a tooltip is never drawn underneath the stick.
            chrome.getChildren().add(chrome.getChildren().indexOf(tooltipLayer), joystick);
        } else if (!touch && present) {
            chrome.getChildren().remove(joystick);
        }

        // The hint sits above the stick on a phone and at the bottom edge without one, so switching
        // has to move it.
        layOutOverlays();
    }

    /** Whether the view is laid out for fingers. */
    public boolean isTouchControls() {
        return touchControls;
    }

    /** The thumbstick, so a test can find it and press it. */
    public JoystickView joystick() {
        return joystick;
    }

    public Button returnButton() {
        return returnButton;
    }

    /**
     * The drawing surface, so a test can look at what is actually in the room rather than infer it
     * from the pixels. Every surface and every box carries an id.
     */
    public javafx.scene.SubScene subScene() {
        return renderer.node();
    }

    /**
     * The id of the item under this point on the drawing surface, or {@code null} for none.
     *
     * <p>Asks with the live camera rather than a copy, deliberately: the answer has to be for
     * where you are looking <em>now</em>, and a pick taken during a movement frame would otherwise
     * use the pose from before the frame moved.
     */
    public String pickItem(double xPx, double yPx) {
        if (!open) {
            return null;
        }
        return renderer.pickItem(xPx, yPx, camera);
    }

    /**
     * The id of the item under a point given in <b>scene</b> coordinates: what a
     * {@code MouseEvent} reports.
     *
     * <p>Converts through {@code subSceneHost.sceneToLocal} rather than using the numbers as they
     * arrive. Today the two are the same, because this view is relocated to the window's origin
     * and sits outside the interface zoom. Converting anyway is the M3.2 lesson: the tooltip and
     * the drag ghost were each handed a coordinate in one space and used it in another, and the
     * error grew the further across the window you went. {@code sceneToLocal} undoes whatever
     * transforms exist, including ones nobody has added yet.
     */
    public String pickItemAtScene(double sceneX, double sceneY) {
        if (!open) {
            return null;
        }
        javafx.geometry.Point2D local = subSceneHost.sceneToLocal(sceneX, sceneY);
        return pickItem(local.getX(), local.getY());
    }

    /**
     * The layer the 3D view's own tooltip is drawn on.
     *
     * <p><b>Why the 3D view needs a tooltip layer of its own.</b> The 2D one is built over
     * {@link Overlays}'s tooltip layer, and the 3D view hides that whole node while it is up, so the
     * existing tooltip is invisible for exactly as long as you are in the room. Reusing
     * {@code ItemTooltip} therefore means reusing the <em>class</em>, over a layer that lives in
     * here.
     *
     * <p>Mouse-transparent and not picking on its bounds, so it can never swallow a click meant
     * for the room or for the return button, and added last so it draws over both.
     */
    public Pane tooltipLayer() {
        return tooltipLayer;
    }

    /**
     * Builds the room and shows it immediately, with the camera already standing in it.
     *
     * <p><b>The app does not use this: it uses {@link #openWithDescent}.</b> This is the way in
     * for tests that are checking what the room <em>looks like</em> and have no interest in
     * spending 2.5 seconds watching a camera fall first.
     *
     * <p>That is only safe because the pose it produces is <em>exactly</em> the pose the descent
     * lands on: middle of the room, eye height, facing north, level. {@code Room3dAppearanceTest}
     * pins that the two agree, because the moment they drift every appearance test would be
     * describing a view the app never actually shows, which is the M3.5 failure, where a fix was
     * green for a week because the tests drove a path the running app never took.
     */
    public void open(AppState state, Scene scene) {
        double width = Math.max(1, scene.getWidth());
        double height = Math.max(1, scene.getHeight());

        renderer.init(width, height);
        renderer.build(state);
        surface.attach(renderer.node(), width, height);
        surface.setFactor(factorInUse);
        subSceneHost.getChildren().setAll(surface.node());

        camera.x = state.room.w / 2;
        camera.z = state.room.l / 2;
        camera.y = CameraPose.EYE_HEIGHT_FT;
        camera.yaw = 0;
        camera.pitch = 0;
        paint();

        fitToWindow(width, height);
        open = true;
        entering = false;
        skipping = false;
        setVisible(true);
        returnButton.requestFocus();
        // Part of the same "do what the descent's arrival does" below: the stick is hidden on the
        // way in and shown on landing, so a way in that skips the landing has to show it by hand or
        // this path would be standing in a room with no joystick in it.
        joystick.setVisible(true);
        joystick.setOpacity(1);

        // This way in skips the descent, so it also has to do what the descent's arrival does:
        // start listening and start walking. Without it every test using this path would be
        // standing in a room it cannot move around, which is the M3.5 shape: tests driving a
        // path the app never takes, and passing.
        controls.setRoom(state.room);
        controls.attach(scene);
        controls.startWalking();
    }

    /**
     * Draws one frame from wherever the camera currently is.
     *
     * <p>Separate from moving the camera, because M5.2's entry and exit animations move it many
     * times a second and want to draw once per frame rather than once per change.
     */
    public void render() {
        if (open) {
            paint();
        }
    }

    /**
     * Draws one frame: puts the camera where it belongs, then takes the reduced picture of it if
     * that is switched on.
     *
     * <p><b>Every path that draws goes through here</b>, and that is the point of the method. A
     * path that called the renderer directly would move the camera and leave the picture on screen
     * showing where it used to be, which is a bug that only appears when the setting is on and so
     * would not be found by anybody running the default.
     */
    private void paint() {
        renderer.render(camera);
        if (surface.refresh()) {
            // ⚠ The swap to the reduced picture happens HERE, a frame or more after the reduction
            // was switched on, and not when it was switched on. A room that has never been drawn
            // cannot be photographed, so ScaledSurface stays on the live SubScene until it has a
            // real picture in hand and says so by returning true. Ignoring this return is what
            // made the whole descent come up empty.
            subSceneHost.getChildren().setAll(surface.node());
        }
    }

    /**
     * Lets {@code auto} decide from the flight that just finished.
     *
     * <p>Does nothing at all unless {@code -Dinvmgr.renderscale=auto}. <b>It can only ever make the
     * room coarser</b>, never sharper again, because {@link RenderScale#factorFor} refuses to
     * increase: scaling makes the next flight faster, a faster flight reads as healthy, and healthy
     * would turn scaling back off, so the picture would change sharpness every time you entered or
     * left the room.
     */
    private void adoptRenderScale(FlightClock clock) {
        if (!renderScale.isAuto() || clock.frames() == 0) {
            return;
        }
        double wanted = renderScale.factorFor(clock.medianMs(), surface.factor());
        if (wanted == surface.factor()) {
            return;
        }
        factorInUse = wanted;
        if (surface.setFactor(wanted)) {
            subSceneHost.getChildren().setAll(surface.node());
        }
        paint();
        Verbose.log(Verbose.Topic.THREE_D, () -> String.format(
                "drawing the room at %.4g of the window from now on, after a flight at %.1f ms a "
                        + "frame", surface.factor(), clock.medianMs()));
    }

    /**
     * Builds the room overhead, fades the picture in, and flies the camera down into it.
     *
     * <p>The order matters and is the original's ({@code enter3D} → {@code startDescent3d}).
     * Everything is drawn and rendered <em>before</em> anything is shown, so the first thing that
     * appears is a finished picture rather than a half-built one; then it fades up over
     * {@link Transitions#OVERLAY_FADE_MS}; then there is a deliberate pause of
     * {@link Transitions#DESCENT_DELAY_MS} (slightly longer than the fade), so you see the room
     * from above, still and complete, for a moment before it starts to move. Only then does the
     * camera fall.
     *
     * @param onLanded run once the camera is standing in the room, for whatever should only be
     *                 possible from down there
     */
    public void openWithDescent(AppState state, Scene scene, Runnable onLanded) {
        // Only `animating` is checked, deliberately: {@link #prepare} has usually already run and
        // left `open` true, so bailing out on that would mean the camera never moved. Pressing the
        // button twice is stopped in App, before either of these is called.
        if (animating) {
            return;
        }
        animating = true;

        // A no-op when prepare() has already done it, which is the normal path.
        buildRoom(state, scene);

        // Hidden rather than merely transparent: a transparent button is still clickable, and
        // pressing "back to 2D" during the descent would leave the app half-way between views.
        returnButton.setVisible(false);
        returnButton.setOpacity(0);
        // The joystick keeps the same company. The original hides it too ({@code joyWrap.style
        // .display = 'none'} until the descent finishes), and here it matters for a second reason:
        // an invisible node is not pickable, so a thumb during the way in cannot start walking the
        // camera against the animation that is flying it.
        joystick.setVisible(false);
        joystick.setOpacity(0);

        setOpacity(0);
        setVisible(true);

        entering = true;
        pendingLanding = onLanded;

        // Something was pressed before this even got started, during App's chrome slide, which is
        // the first 370 ms and has no flight to interrupt. The request was latched rather than
        // lost; honor it now by never building the fade or the pause at all.
        if (skipping) {
            collapseEntry();
            return;
        }

        entryFade = new FadeTransition(Duration.millis(Transitions.OVERLAY_FADE_MS), this);
        entryFade.setFromValue(0);
        entryFade.setToValue(1);
        entryFade.play();

        entryPause = new PauseTransition(Duration.millis(Transitions.DESCENT_DELAY_MS));
        entryPause.setOnFinished(event -> {
            entryFade = null;
            entryPause = null;
            descend(pendingLanding);
        });
        entryPause.play();
    }

    /**
     * Lands the way in or the way out immediately, wherever it has got to.
     *
     * <p>CLAUDE.md §5.5 <b>D-11</b>, and the user's own idea: a 2230 ms descent is a good first
     * impression and an obstacle on the fiftieth entry, so touching any control during it puts you
     * where the animation was going to put you anyway.
     *
     * <p><b>Nothing here duplicates an arrival.</b> All it does is arrange for the flight's next
     * frame to compute a progress of 1, after which {@link #fly} runs its ordinary ending: the
     * same tween applied at 1, the same per-frame callback (which for the ascent is what fades the
     * picture out), and the <em>same</em> arrival closure, so {@code App}'s chrome, Fit and scroll
     * restoration all happen exactly as they would have. A second code path that "finished things
     * off" is what would make skipping the way out dangerous, and there is not one.
     *
     * <p>Three cases, and only this class knows which applies:
     *
     * <ul>
     *   <li><b>Nothing has started yet</b>: the chrome is still sliding. The request is
     *       remembered, and {@link #openWithDescent} skips the fade and the pause when it runs.</li>
     *   <li><b>The fade or the pause is running.</b> Both are stopped and the descent starts now,
     *       arriving on its first frame.</li>
     *   <li><b>A flight is running</b>, in or out. Its next frame is its last.</li>
     * </ul>
     */
    public void skipTransition() {
        if (!entering && !animating) {
            return;
        }
        skipping = true;
        if (flight == null && (entryFade != null || entryPause != null)) {
            collapseEntry();
        }
    }

    /** Cuts the fade and the pause short and falls straight into the room. */
    /**
     * The line of text that appears at the bottom when you land, telling you the controls.
     *
     * <p>Styled by <b>reusing the tooltip's own tokens</b> rather than inventing any: the same
     * near-black fill, the same gray hairline, the same 12 px type and 5-by-10 padding. That is
     * deliberate: a new piece of interface that the original does not have should at least look
     * like something the original does, and the design system's own rule is that a value invented
     * rather than transcribed is a visual bug.
     */
    private static Label buildControlsHint() {
        Label hint = new Label(CONTROLS_HINT);
        hint.setFont(javafx.scene.text.Font.font(Tokens.FONT_FAMILY, Tokens.FONT_TOOLTIP));
        hint.setTextFill(Tokens.TEXT_INPUT);
        // The touch wording is two lines. A Label honors a newline in its text without any
        // wrapping being turned on, but it left-aligns the shorter line by default, which reads as
        // a ragged edge under a centered box. Set once here rather than in setTouchControls, so the
        // one-line desktop wording cannot be the reason it looks right.
        hint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        hint.setPadding(new javafx.geometry.Insets(Tokens.TOOLTIP_PADDING_V,
                Tokens.TOOLTIP_PADDING_H, Tokens.TOOLTIP_PADDING_V, Tokens.TOOLTIP_PADDING_H));
        hint.setStyle("-fx-background-color: " + Tokens.hex(Tokens.TOOLTIP_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.TOOLTIP_BORDER) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;");
        // It sits over the room, so it must never eat a click meant for what is behind it.
        hint.setMouseTransparent(true);
        hint.setVisible(false);
        hint.setOpacity(0);
        return hint;
    }

    /**
     * Fades the controls hint in, holds it, and fades it away.
     *
     * <p>Shown on <b>every</b> landing, including a skipped one. Suppressing it after a skip is
     * tempting (somebody who pressed W plainly knows W walks), and it is not done, because the
     * point is discoverability and a rule with an exception in it is a rule that will be subtly
     * wrong the first time somebody skips with the mouse instead.
     */
    private void showControlsHint() {
        controlsHint.setVisible(true);
        controlsHint.setOpacity(0);

        FadeTransition appear =
                new FadeTransition(Duration.millis(Transitions.RETURN_BUTTON_FADE_MS),
                        controlsHint);
        appear.setFromValue(0);
        appear.setToValue(1);

        PauseTransition hold = new PauseTransition(Duration.millis(HINT_HOLD_MS));

        FadeTransition vanish =
                new FadeTransition(Duration.millis(Transitions.RETURN_BUTTON_FADE_MS),
                        controlsHint);
        vanish.setFromValue(1);
        vanish.setToValue(0);
        vanish.setOnFinished(event -> controlsHint.setVisible(false));

        hintSequence = new javafx.animation.SequentialTransition(appear, hold, vanish);
        hintSequence.play();
    }

    /**
     * Brings the thumbstick in with the return button, on the same fade.
     *
     * <p>Its own {@code FadeTransition} rather than one shared with the button, because they are
     * different nodes and JavaFX fades a node rather than a set of them; the two are the same length
     * and started in the same frame, so they arrive together.
     */
    private void showJoystick() {
        joystick.setVisible(true);
        FadeTransition fade = new FadeTransition(
                Duration.millis(Transitions.RETURN_BUTTON_FADE_MS), joystick);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    private void hideControlsHint() {
        if (hintSequence != null) {
            hintSequence.stop();
            hintSequence = null;
        }
        controlsHint.setVisible(false);
        controlsHint.setOpacity(0);
    }

    /** The controls hint, so a test can find it. */
    public Label controlsHintNode() {
        return controlsHint;
    }

    /**
     * Tells the view what the Ctrl+scroll interface zoom is currently set to.
     *
     * <p>Only the chrome drawn over the picture uses it (see {@link #chrome}). Called by
     * {@code App} whenever the zoom changes, and it must be, because this class is deliberately
     * outside the one transform that scales everything else.
     */
    public void setUiScale(double factor) {
        this.uiScaleFactor = factor <= 0 ? 1 : factor;
        layOutChrome(getWidth(), getHeight());
    }

    /**
     * Lays the overlay chrome out at the window's size <em>divided by</em> the zoom, and lets the
     * transform scale it back up to fill the glass exactly.
     *
     * <p>That division is the part that is easy to get wrong: laying it out at the window's real
     * size and then scaling would draw a window's worth of interface at 125% of a window, and the
     * return button would end up off the right-hand edge at anything above 100%.
     */
    private void layOutChrome(double widthPx, double heightPx) {
        double width = Math.max(1, widthPx);
        double height = Math.max(1, heightPx);
        chromeScale.setX(uiScaleFactor);
        chromeScale.setY(uiScaleFactor);
        chrome.resizeRelocate(0, 0, width / uiScaleFactor, height / uiScaleFactor);
    }

    private void collapseEntry() {
        stopEntryAnimations();
        setOpacity(1);
        setVisible(true);
        descend(pendingLanding);
    }

    private void stopEntryAnimations() {
        if (entryFade != null) {
            entryFade.stop();
            entryFade = null;
        }
        if (entryPause != null) {
            entryPause.stop();
            entryPause = null;
        }
    }

    /**
     * Builds the room, points the camera straight down at it from overhead, and draws one frame.
     *
     * <p><b>Separated out so it can be done before any animation starts</b>: see
     * {@link #prepare}. Building is the single most expensive thing that happens in the whole
     * transition and it is not smooth: measured here at <b>55 ms warm and about 300 ms the first
     * time in a run</b>, because the first 3D scene of a session makes the graphics pipeline
     * compile its shaders and upload every texture. Where that lands is the difference between a
     * stutter and a pause.
     */
    private void buildRoom(AppState state, Scene scene) {
        if (open) {
            return;
        }
        double width = Math.max(1, scene.getWidth());
        double height = Math.max(1, scene.getHeight());

        renderer.init(width, height);
        renderer.build(state);
        surface.attach(renderer.node(), width, height);
        surface.setFactor(factorInUse);
        subSceneHost.getChildren().setAll(surface.node());

        camera.x = state.room.w / 2;
        camera.z = state.room.l / 2;
        camera.y = Perspective.overheadHeightFt(state.room, width, height);
        camera.yaw = 0;
        camera.pitch = -Math.PI / 2;
        paint();

        fitToWindow(width, height);
        open = true;

        // Listening starts here rather than when the camera lands, so a control pressed during
        // the way in is heard (see D-11).
        controls.setRoom(state.room);
        controls.attach(scene);
    }

    /**
     * Builds the room now, while nothing is moving yet, and leaves it invisible.
     *
     * <p>Called the instant the 3D button is pressed, before the 2D chrome starts sliding.
     * {@link #openWithDescent} then finds the work already done and goes straight to the fade.
     *
     * <p><b>Why it is worth the extra method.</b> Building used to happen 370 ms in, which put its
     * 300 ms first-run cost squarely on top of the chrome sliding away and the picture fading up,
     * so the first thing you saw after pressing the button was a stutter. Doing it first turns that
     * into a short pause <em>before</em> anything moves, which is the same total time and reads as
     * the button responding rather than the animation struggling. Nothing about the movement
     * changes; only where the unavoidable cost sits.
     *
     * <p>Safe to do this early because none of it depends on the chrome: the drawing surface is the
     * whole window and the overhead height is measured from the scene, neither of which moves when
     * the bars leave the layout.
     */
    public void prepare(AppState state, Scene scene) {
        if (animating || open) {
            return;
        }
        // The entry starts HERE, not when the camera does. Everything from this moment on is part
        // of the way in as far as the user is concerned, so a control pressed during the chrome
        // slide has something to interrupt (see skipTransition).
        entering = true;
        skipping = false;
        buildRoom(state, scene);
        setOpacity(0);
        setVisible(false);
    }

    private void descend(Runnable onLanded) {
        // The picture is solid by now, so whatever is behind it is drawn every frame and then
        // completely painted over. JavaFX does no occlusion culling (it has no idea this covers
        // the whole window), so the 2D interface, room, and up to five hundred item nodes are
        // rendered for nothing, sixty times a second, for the whole of the flight and all the time
        // you spend in 3D. Hiding it is free and saves all of that.
        hideWhatIsBehind.run();

        CameraTween tween = Transitions.descent(camera);
        fly("descent", tween, Transitions.DESCENT_MS, progress -> { }, () -> {
            animating = false;
            entering = false;
            returnButton.setVisible(true);
            FadeTransition fade = new FadeTransition(
                    Duration.millis(Transitions.RETURN_BUTTON_FADE_MS), returnButton);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.play();
            showJoystick();
            returnButton.requestFocus();
            controls.startWalking();
            showControlsHint();
            onLanded.run();
        });
    }

    /**
     * Flies the camera back up to overhead, fading the picture out on the way, then tears the room
     * down.
     *
     * <p>The fade is driven from the camera's own progress rather than by a separate fade of its
     * own, because two clocks running the same length of time still drift apart by a frame or two;
     * the whole point is that the picture is gone at the exact moment the camera arrives.
     *
     * @param onDone run after the room has been disposed of and the view hidden
     */
    public void closeWithAscent(AppState state, Scene scene, Runnable onDone) {
        if (animating || !open) {
            return;
        }
        animating = true;
        hideControlsHint();
        // No `skipping = false` here, deliberately. It was written first and the mutation sweep
        // showed deleting it changes nothing: a flight always clears the flag as it arrives, and
        // prepare() and close() clear it on the other paths, so it is never still set by the time
        // anything gets here. Two clears that each hide the other's absence is one clear and one
        // piece of dead code (§2), and the one that stays is the one a test can see.
        returnButton.setVisible(false);
        // And the stick, for the same reason the button goes: a thumb on it during the ascent would
        // be walking a camera that a flight is already flying.
        joystick.setVisible(false);

        // Walking stops before the flight starts, so the two timers can never both be moving the
        // camera. A key still held at this moment would otherwise fight the ascent for it.
        controls.stopWalking();

        // And the pointer comes back now rather than when the climb lands, which is what the
        // original does (original line 3002). Holding it for the whole 1920 ms meant the cursor
        // stayed hidden for two seconds and then reappeared in the middle of the window instead
        // of where it was grabbed.
        //
        // detach() releases it too and that is NOT dead code: close() is reachable without an
        // ascent at all, from a test or an error path, and those still need the release. This is
        // the opposite case to the `skipping = false` note above, where the second clear really
        // was unreachable.
        controls.releaseCapture();

        // Back before the fade-out starts uncovering it, which is 70% of the way up, so this is
        // well ahead of anything showing through.
        showWhatIsBehind.run();

        double width = Math.max(1, scene.getWidth());
        double height = Math.max(1, scene.getHeight());
        double overheadY = Perspective.overheadHeightFt(state.room, width, height);

        CameraTween tween = Transitions.ascent(camera, state.room, overheadY);
        fly("ascent", tween, Transitions.ASCENT_MS,
                progress -> setOpacity(Transitions.overlayOpacity(progress)),
                () -> {
                    animating = false;
                    close();
                    setOpacity(1);      // ready for the next time it is opened
                    onDone.run();
                });
    }

    /**
     * Runs one camera journey, a frame at a time.
     *
     * <p><b>The clock lives here and nowhere else.</b> {@link CameraTween} is handed a fraction and
     * knows nothing about time, which is what lets every rule about the shape of these movements
     * be tested without a window (see {@code TransitionsTest}).
     */
    private void fly(String what, CameraTween tween, double durationMs,
            java.util.function.DoubleConsumer eachFrame, Runnable onArrival) {
        stopFlight();
        FlightClock clock = new FlightClock();
        flight = new AnimationTimer() {
            private long startedAt;

            @Override
            public void handle(long now) {
                if (startedAt == 0) {
                    startedAt = now;
                }
                clock.frame(now);
                // Nanoseconds, which is what an AnimationTimer deals in. A skip forces the
                // fraction to 1 and changes nothing else, so everything below runs exactly as it
                // does at the natural end of a journey (see skipTransition).
                double progress = skipping
                        ? 1
                        : Math.min(1, (now - startedAt) / (durationMs * 1_000_000));
                tween.applyTo(camera, progress);
                paint();
                eachFrame.accept(progress);
                if (progress >= 1) {
                    stopFlight();
                    clock.report(what);
                    adoptRenderScale(clock);
                    // Cleared here, before the arrival runs, so a skipped descent cannot be
                    // inherited by the ascent that follows it.
                    skipping = false;
                    onArrival.run();
                }
            }
        };
        flight.start();
    }

    private void stopFlight() {
        if (flight != null) {
            flight.stop();
            flight = null;
        }
    }

    /** Tears the room down and hides the view. */
    public void close() {
        stopFlight();
        stopEntryAnimations();
        hideControlsHint();
        // Ready for the next time it opens. Deliberately NOT also re-centering the knob here:
        // detach() below lets go of every finger, and that already stops the knob's ease and puts
        // it back; two clears where one will do is one clear and one piece of dead code, and it is
        // the pair that each hide the other's absence that this project has already been caught by
        // once (see closeWithAscent's missing `skipping = false`).
        joystick.setVisible(true);
        joystick.setOpacity(1);
        // Unconditional, like stopFlight above and for the same reason: no error path may leave a
        // timer running or a filter listening on a scene the 3D view has finished with.
        controls.detach();
        animating = false;
        entering = false;
        skipping = false;
        open = false;
        // Unconditional, so a close() from anywhere (a test, an error path) cannot leave the
        // 2D interface hidden with nothing drawn over it.
        showWhatIsBehind.run();
        setVisible(false);
        subSceneHost.getChildren().clear();
        surface.dispose();
        renderer.dispose();
    }

    /**
     * Follows the window.
     *
     * <p><b>Not called {@code resize}</b>, which would have been the obvious name and is a trap: a
     * {@link javafx.scene.layout.Region} already has a {@code resize(double, double)}, so naming it
     * that silently overrides JavaFX's own, and the {@code resizeRelocate} call below then calls
     * straight back into it. That is not a subtle failure (it is a stack overflow the instant the
     * window is laid out), but it is an <em>invisible</em> one at the point of writing, because
     * nothing marks the method as an override and the compiler is perfectly happy. Found by the
     * existing interface tests within a minute of the class being wired up.
     */
    public void fitToWindow(double widthPx, double heightPx) {
        relocate(0, 0);
        super.resize(widthPx, heightPx);
        subSceneHost.resizeRelocate(0, 0, widthPx, heightPx);
        layOutChrome(widthPx, heightPx);

        vignette.setWidth(widthPx);
        vignette.setHeight(heightPx);
        // Proportional, so the darkening keeps its shape as the window changes, unlike the
        // per-surface vignette baked into the walls, which is a circle measured in pixels.
        vignette.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(VIGNETTE_START, Color.rgb(0, 0, 0, 0)),
                new Stop(1, Color.rgb(0, 0, 0, VIGNETTE_MAX_ALPHA))));

        if (open) {
            renderer.resize(widthPx, heightPx);
            surface.resize(widthPx, heightPx);
            paint();
        }
    }

    private Button buildReturnButton() {
        Button button = new Button();
        button.setGraphic(Icons.twoD(BUTTON_ICON_SIZE));
        button.setMinSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setPrefSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setMaxSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setStyle("-fx-background-color: rgba(48,56,56,0.92);"
                + " -fx-border-color: #667;"
                + " -fx-border-width: 1;"
                + " -fx-background-radius: " + BUTTON_RADIUS + ";"
                + " -fx-border-radius: " + BUTTON_RADIUS + ";"
                + " -fx-padding: 0;");
        Hints.attach(button, "Back to 2D view");
        button.setOnAction(event -> onReturn.run());
        return button;
    }

}
