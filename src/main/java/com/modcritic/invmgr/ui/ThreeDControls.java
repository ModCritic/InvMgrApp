package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.Verbose;
import com.modcritic.invmgr.model.Room;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.CameraRest;
import com.modcritic.invmgr.threed.Joystick;
import com.modcritic.invmgr.threed.Walk;
import com.modcritic.invmgr.threed.WalkGesture;
import com.modcritic.invmgr.threed.WalkInput;
import com.modcritic.invmgr.threed.WalkInput.Control;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.stage.Window;

/**
 * The keyboard, the mouse and the fingers while you are standing in the 3D room.
 *
 * <p>Port of the original's {@code bindControls3d} / {@code unbindControls3d} (original lines
 * 3075-3220) and the loop that drives them ({@code loop3d}, lines 3044-3066). <b>This class holds
 * only the JavaFX plumbing</b>: the event handlers, the per-frame timer, and which keys are
 * currently down. Every number and every piece of arithmetic lives in {@code threed.Walk},
 * {@code threed.WalkInput}, {@code threed.Joystick} and {@code threed.WalkGesture}, where it can be
 * checked without a window.
 *
 * <h2>What a finger has to be told apart from</h2>
 *
 * <p>Three facts about Android decide the shape of the touch half, and all three were read out of
 * the JavaFX SDK that ships in the APK rather than reasoned about:
 *
 * <ol>
 *   <li><b>Only the first finger is turned into a mouse.</b> Monocle's {@code MouseInputSynthesizer}
 *       asks the touch state for its {@code getPrimaryID()} and manufactures a press, a drag and a
 *       release from that one point. So a finger on the glass drives the mouse handlers below
 *       <em>and</em> the touch handlers, and without the {@code isSynthesized} guards on the mouse
 *       side every look would be applied twice, at two different sensitivities, and letting go would
 *       try to hide and warp a pointer that does not exist. <b>The guards return rather than
 *       consume</b>; see CLAUDE.md §5.8 item 5: those same synthesized events are what a finger
 *       scrolls the item list with, and swallowing them at the scene is how you freeze a phone.</li>
 *   <li><b>One touch event is fired per finger, per batch.</b> {@code Scene.processTouchEvent} loops
 *       over every current point and fires a separate {@code TouchEvent} for each, aimed at that
 *       point's own target, with the whole set attached to all of them. Two fingers moving therefore
 *       arrive as two {@code TOUCH_MOVED} events describing the same moment. Nothing here counts
 *       events or de-duplicates them; instead every handler reads the <em>positions</em> off
 *       {@link TouchEvent#getTouchPoints()} and the recognizer works from absolute positions, so the
 *       second event of a pair computes a movement of zero and changes nothing.</li>
 *   <li><b>A finger is delivered to the node it was pressed on, for its whole life.</b> The scene
 *       keeps a map from touch identifier to target and consults it before picking. That is what
 *       makes {@code TouchPoint.belongsTo} a reliable way to ask "is this the thumb on the
 *       joystick?" after the thumb has slid off it.</li>
 * </ol>
 *
 * <h2>Why the handlers are filters on the Scene rather than handlers on a node</h2>
 *
 * <p>An event <em>filter</em> runs on the way down from the scene root, before the event reaches
 * whatever it was aimed at. Three reasons that is the right choice here, and the first decides it:
 *
 * <ol>
 *   <li><b>Focus stops mattering.</b> A filter fires whatever has focus. {@link View3D} never
 *       focuses the drawing surface (a {@code SubScene} is not focus-traversable by default), and
 *       the return button only takes focus <em>after</em> the camera has landed. A handler-based
 *       design would therefore have nothing listening during the way in, which is exactly when
 *       CLAUDE.md §5.5 <b>D-11</b>'s "any input lands the transition" has to work.</li>
 *   <li><b>They see events aimed at nothing.</b> The 3D view does not pick on its bounds, the
 *       darkening and every room surface are mouse-transparent, and the 2D interface is hidden
 *       (and an invisible node is not pickable), so a press over empty room reaches the scene root
 *       with no target at all. A filter still sees it.</li>
 *   <li><b>They run before a button's own behavior</b>, which is what lets Space be taken away
 *       from the return button. See below; it is not optional.</li>
 * </ol>
 *
 * <p><b>There is no conflict with the 2D interface's keyboard, structurally rather than by luck.</b>
 * The whole of {@code src/main} has four key handlers (the layer slider's arrow keys and the name
 * fields in the three dialogs), and every one is on a focused control. There is no global 2D key
 * binding to collide with, and no dialog can be open while the 3D view is up. So there is no
 * "is a dialog open" guard here: it would be a guard against a state that cannot happen, which
 * CLAUDE.md §2 calls dead code.
 */
public final class ThreeDControls {

    /**
     * How far the pointer may travel during a press and still count as a click, in pixels.
     *
     * <p>The original's {@code clickWasDrag3d} threshold (original line 3130). Without it, letting
     * go at the end of a look-drag is also a click, and a click toggles the pointer capture, so
     * every drag would end by capturing or releasing the pointer.
     */
    public static final double CLICK_SLOP_PX = 4;

    /**
     * How close the pointer must land to where it was sent for capture to be considered working,
     * in pixels. See {@link #tryCapture}.
     */
    private static final double WARP_TOLERANCE_PX = 2;

    /**
     * How near an edge the pointer has to get, in pixels, before it is put back in the middle.
     *
     * <p><b>Re-centering after every single movement is the obvious way to do this and it is
     * fragile.</b> Each warp produces another movement event, so the stream is half real
     * movement and half echo, and anything that delays or overrides the warp turns the echoes
     * into enormous phantom deltas. Only re-centering when the pointer is actually about to run
     * out of room cuts that traffic to almost nothing: a normal look never triggers it at all.
     *
     * <p>Added 2026-08-06 after the user found capture unusable on Windows under VirtualBox;
     * see {@link #IMPLAUSIBLE_JUMP_FRACTION}.
     */
    private static final double RECENTER_MARGIN_PX = 100;

    /**
     * How much of the window a single movement event may claim to have crossed before it is
     * treated as an artifact rather than a hand.
     *
     * <p>A quarter of the window in one event is not a mouse being moved; it is the pointer being
     * put somewhere by something else. That happens for real: <b>VirtualBox's mouse integration
     * makes the guest's cursor a slave to the host's</b>, so a warp inside the guest is
     * immediately overridden and every following event reports a huge, consistently-signed delta
     * from a center the pointer never stayed at. The user's symptoms were exactly that: the view
     * jumping fifteen to thirty degrees at a time and sticking at the pitch limit. Disabling the
     * VM's mouse integration confirmed the cause.
     *
     * <p>This does not make that setup work; it makes it survivable, and the check below is what
     * actually notices and falls back.
     */
    private static final double IMPLAUSIBLE_JUMP_FRACTION = 0.25;

    /**
     * How many implausible jumps in a row mean capture is not really working here.
     *
     * <p>One is a glitch. Three in a row is an environment where the pointer is not ours to move,
     * and the honest response is to give it back and say so rather than to let the camera be
     * thrown around.
     */
    private static final int JUMPS_BEFORE_GIVING_UP = 3;

    private final WalkInput input = new WalkInput();
    private final Joystick joystick = new Joystick();
    private final WalkGesture gesture = new WalkGesture();
    private final CameraPose camera;
    private final Runnable drawOneFrame;

    private Room room = new Room();
    private Scene scene;

    /** The circles on screen, or null on a machine that has no joystick. */
    private JoystickView joystickView;

    /**
     * Whether this is a phone, in the original's own sense of {@code isTouch()} inside
     * {@code loop3d}.
     *
     * <p>It decides one thing and only one: how fast walking is. The original picks
     * {@code TOUCH_SPEED_3D} over {@code MOVE_SPEED_3D} on the platform rather than on whether the
     * joystick is the thing being held, and that is kept: the two differ only for someone using a
     * hardware keyboard on a phone, and inventing an answer for that case would be a divergence
     * nobody asked for.
     *
     * <p><b>Everything else about touch is wired unconditionally</b>, deliberately. A desktop never
     * delivers a {@code TouchEvent}, so listening for one costs nothing there, and it is what lets
     * the gestures be driven by hand-built events from a test, the arrangement M6.2 used for the
     * flat room's fingers.
     */
    private boolean touch;

    private EventHandler<KeyEvent> onKeyPressed;
    private EventHandler<KeyEvent> onKeyReleased;
    private EventHandler<MouseEvent> onMousePressed;
    private EventHandler<MouseEvent> onMouseDragged;
    private EventHandler<MouseEvent> onMouseReleased;
    private EventHandler<MouseEvent> onMouseMoved;
    private EventHandler<TouchEvent> onTouchPressed;
    private EventHandler<TouchEvent> onTouchMoved;
    private EventHandler<TouchEvent> onTouchReleased;
    private ChangeListener<Boolean> onWindowFocus;
    private Window watchedWindow;

    /** Asks what item is under a point given in scene coordinates. */
    @FunctionalInterface
    public interface Picker {
        /** @return the item's id, or null if the point is empty room */
        String itemAt(double sceneX, double sceneY);
    }

    /** Told which item the pointer is resting on, and where the pointer is. */
    @FunctionalInterface
    public interface HoverHandler {
        void hovered(String itemId, double sceneX, double sceneY);
    }

    /**
     * Told which item a finger tapped, and where.
     *
     * <p>Separate from {@link HoverHandler} because the two ask for different things: a hover wants
     * a label beside the pointer that follows it and goes away when the pointer leaves, and a tap
     * wants a label <em>above</em> the box (a fingertip covers what it is touching), which takes
     * itself away, because a finger just lifts and nothing would ever tell it to go.
     */
    @FunctionalInterface
    public interface TapHandler {
        void tapped(String itemId, double sceneX, double sceneY);
    }

    /** Nodes a press on must be left alone: the return button, so clicking it does not look. */
    private Node ignoreTarget;

    private Picker picker = (x, y) -> null;
    private HoverHandler onHover = (id, x, y) -> { };
    private Runnable onHoverEnded = () -> { };
    private TapHandler onTap = (id, x, y) -> { };

    /**
     * Told whenever a deliberate 3D input arrives, so a running transition can land at once.
     *
     * <p>Fired on a <b>fresh</b> key press and on a mouse press or drag. Not on the repeats an
     * operating system sends while a key is held (the first edge has already done the job), and
     * <b>not on bare pointer movement</b>, which is the user's own decision: sweeping the mouse
     * across the window while watching the way in must not cut it short.
     */
    private Runnable onAnyInput = () -> { };

    /**
     * Asked to leave the 3D view. Wired to the same thing the return button does.
     *
     * <p>Escape is CLAUDE.md §5.5 <b>D-12</b>, a key the original does not bind at all. It costs
     * one line and reuses every guard the button already has.
     */
    private Runnable onExitRequested = () -> { };

    /** The item the pointer was last resting on, so leaving one can be noticed. */
    private String hoveredId;

    private boolean pressing;
    private double lastPointerX;
    private double lastPointerY;
    private double pressTravelPx;

    /** True while the pointer is hidden and being warped back to the middle every move. */
    private boolean capturing;

    private javafx.scene.robot.Robot robot;

    /** Null until capture has been tried once; then true or false for the rest of the session. */
    private Boolean captureWorks;

    /** How many movement events in a row have reported an impossible jump. */
    private int implausibleJumps;

    /**
     * Where the pointer was last seen while captured, in SCREEN pixels.
     *
     * <p>Deliberately not the same pair the drag path uses: that one holds SCENE coordinates, and
     * a press while captured would otherwise leave the next captured movement measuring a screen
     * position against a scene one. The two spaces differ by the window position, so the mistake
     * would look like a sudden jump of exactly the window offset, which is precisely the kind of
     * artifact the guard above now blames on the environment.
     */
    private double lastCaptureX;

    private double lastCaptureY;

    /**
     * The per-frame loop that actually moves the camera.
     *
     * <p><b>A second timer, separate from {@link View3D}'s flight timer, and they never run at
     * once.</b> The flight timer plays a known journey ("put the camera a given fraction of the
     * way along") and stops itself on arrival. This one does the opposite: it integrates input
     * nobody knew about in advance. Folding them together would put a mode flag inside a
     * {@code handle} method and destroy the one property that makes the transitions testable, which
     * is that the clock is the only thing the flight knows and {@code CameraTween} knows none of
     * it.
     *
     * <p>Shaped exactly like {@code DragGhost}'s, including clearing {@code lastNanos} in
     * {@code start()} so the first frame after a start is not handed the length of the pause since
     * the last one.
     */
    /** Notices when the camera stops, so the {@code 3d} topic can say where it ended up. */
    private final CameraRest rest = new CameraRest();

    private final AnimationTimer walking = new AnimationTimer() {
        private long lastNanos;

        @Override
        public void handle(long nowNanos) {
            double seconds = Walk.frameSeconds(nowNanos, lastNanos);
            lastNanos = nowNanos;

            // The thumb wins over the keys while it is on the stick, exactly as the original's
            // `useJoy` does, and the vertical is read from the keys either way, because the
            // joystick has no up or down on it. On a phone that means the stick walks and the
            // two-finger pan is what climbs.
            boolean onTheStick = touch && joystick.isActive();
            Walk.step(camera,
                    onTheStick ? joystick.forward() : input.forward(),
                    onTheStick ? joystick.strafe() : input.strafe(),
                    input.vertical(),
                    touch ? Walk.TOUCH_SPEED_FT_PER_S : Walk.MOVE_SPEED_FT_PER_S,
                    input.isBoosting(), seconds, room);

            // Drawn every frame whether or not anything moved, as the original draws it. Skipping
            // the draw when standing still is the obvious saving and it is not taken: it would be
            // an optimization with no measurement behind it, and CLAUDE.md §2 asks for the
            // measurement first.
            drawOneFrame.run();

            // One line each time the camera stops, and nothing on the frames in between. Asked
            // for by the user 2026-09-19, after a device test spent a round hunting for boxes
            // that were in front of the camera and below the frame. CameraRest watches the pose
            // rather than the controls, so a drag, a pinch and the joystick are all covered
            // without asking any of them whether they have finished; see its class notes.
            if (rest.update(camera)) {
                Verbose.log(Verbose.Topic.THREE_D, rest::line);
            }
        }

        @Override
        public void start() {
            lastNanos = 0;
            super.start();
        }
    };

    /**
     * @param camera       the live camera, moved in place, sixty times a second
     * @param drawOneFrame draws the room from wherever the camera now is
     */
    public ThreeDControls(CameraPose camera, Runnable drawOneFrame) {
        this.camera = camera;
        this.drawOneFrame = drawOneFrame;
    }

    /** The room the camera is confined to, plus its slack. Set again whenever the room changes. */
    public void setRoom(Room room) {
        this.room = room == null ? new Room() : room;
    }

    /**
     * Starts listening. The port of {@code bindControls3d}.
     *
     * <p>Called when the room is built, so the controls exist from the moment the 3D button is
     * pressed rather than from the moment the camera lands, which is what lets an early keypress
     * land the way in.
     */
    public void attach(Scene scene) {
        if (this.scene != null) {
            detach();
        }
        this.scene = scene;
        if (scene == null) {
            return;
        }

        onKeyPressed = event -> handleKey(event, true);
        onKeyReleased = event -> handleKey(event, false);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, onKeyPressed);
        scene.addEventFilter(KeyEvent.KEY_RELEASED, onKeyReleased);

        onMousePressed = this::handlePress;
        onMouseDragged = this::handleDrag;
        onMouseReleased = this::handleRelease;
        onMouseMoved = this::handleMove;
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, onMousePressed);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, onMouseDragged);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, onMouseReleased);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, onMouseMoved);

        onTouchPressed = this::handleTouchPressed;
        onTouchMoved = this::handleTouchMoved;
        onTouchReleased = this::handleTouchReleased;
        scene.addEventFilter(TouchEvent.TOUCH_PRESSED, onTouchPressed);
        scene.addEventFilter(TouchEvent.TOUCH_MOVED, onTouchMoved);
        scene.addEventFilter(TouchEvent.TOUCH_RELEASED, onTouchReleased);

        watchWindowFocus(scene);
    }

    /** Whether to walk at the touch speed. Set by the view from {@code Device.isTouch()}. */
    public void setTouch(boolean touch) {
        this.touch = touch;
    }

    /** The joystick's circles, so a thumb on them can be told from a finger looking around. */
    public void setJoystickView(JoystickView joystickView) {
        this.joystickView = joystickView;
    }

    /** What to do when a finger taps a box. Set by the view to put its own tooltip up. */
    public void setOnTap(TapHandler handler) {
        this.onTap = handler == null ? (id, x, y) -> { } : handler;
    }

    /** Where the thumb is, so a test can ask without synthesizing a gesture. */
    public Joystick joystick() {
        return joystick;
    }

    /** What the other fingers are doing, for the same reason. */
    public WalkGesture gesture() {
        return gesture;
    }

    /** A node that presses should be left alone on, set by the view to its return button. */
    public void setIgnoreTarget(Node node) {
        this.ignoreTarget = node;
    }

    /** What to do when a deliberate 3D input arrives. Set by the view to land its transition. */
    public void setOnAnyInput(Runnable handler) {
        this.onAnyInput = handler == null ? () -> { } : handler;
    }

    /** What to do when Escape is pressed. Set by the view to whatever its return button does. */
    public void setOnExitRequested(Runnable handler) {
        this.onExitRequested = handler == null ? () -> { } : handler;
    }

    /** How to find out what is under the pointer. Set by the view to its own ray pick. */
    public void setPicker(Picker picker) {
        this.picker = picker == null ? (x, y) -> null : picker;
    }

    /**
     * What to do when the pointer comes to rest on an item, and when it leaves.
     *
     * <p>A pair, like {@code View3D.setBehind}'s, and for the same reason: showing without hiding
     * leaves a tooltip stuck on screen naming something you are no longer pointing at.
     */
    public void setOnHover(HoverHandler onHover, Runnable onHoverEnded) {
        this.onHover = onHover == null ? (id, x, y) -> { } : onHover;
        this.onHoverEnded = onHoverEnded == null ? () -> { } : onHoverEnded;
    }

    /** Stops listening and forgets everything. The port of {@code unbindControls3d}. */
    public void detach() {
        stopWalking();
        input.clear();
        releaseFingers();
        releaseCapture();
        endHover();

        if (scene != null) {
            if (onKeyPressed != null) {
                scene.removeEventFilter(KeyEvent.KEY_PRESSED, onKeyPressed);
            }
            if (onKeyReleased != null) {
                scene.removeEventFilter(KeyEvent.KEY_RELEASED, onKeyReleased);
            }
            if (onMousePressed != null) {
                scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, onMousePressed);
                scene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, onMouseDragged);
                scene.removeEventFilter(MouseEvent.MOUSE_RELEASED, onMouseReleased);
                scene.removeEventFilter(MouseEvent.MOUSE_MOVED, onMouseMoved);
            }
            if (onTouchPressed != null) {
                scene.removeEventFilter(TouchEvent.TOUCH_PRESSED, onTouchPressed);
                scene.removeEventFilter(TouchEvent.TOUCH_MOVED, onTouchMoved);
                scene.removeEventFilter(TouchEvent.TOUCH_RELEASED, onTouchReleased);
            }
        }
        if (watchedWindow != null && onWindowFocus != null) {
            watchedWindow.focusedProperty().removeListener(onWindowFocus);
        }
        onKeyPressed = null;
        onKeyReleased = null;
        onMousePressed = null;
        onMouseDragged = null;
        onMouseReleased = null;
        onMouseMoved = null;
        onTouchPressed = null;
        onTouchMoved = null;
        onTouchReleased = null;
        onWindowFocus = null;
        watchedWindow = null;
        pressing = false;
        scene = null;
    }

    /** Begins moving the camera once a frame. Called when the camera has landed in the room. */
    public void startWalking() {
        walking.start();
    }

    /** Stops moving the camera, and forgets which keys were down. */
    public void stopWalking() {
        walking.stop();
        input.clear();
        // Cleared so the next visit reports the pose it lands in. Left alone, reopening onto the
        // same pose would read as "unchanged" and the landing would go unlogged.
        rest.clear();
    }

    /** Which movement controls are held. Exposed so a test can ask without synthesizing events. */
    public WalkInput input() {
        return input;
    }

    /**
     * Turns one key event into a change of state, and decides whether to swallow it.
     *
     * <p><b>Space is consumed on the release as well as the press, and that is not tidiness.</b> A
     * focused JavaFX {@code Button} arms on Space pressed and <em>fires on Space released</em>, and
     * the return button takes focus the instant the camera lands. Consume only the press and
     * holding Space to rise would still leave the 3D view. This is the same set the original calls
     * {@code preventDefault} on (W, A, S, D and Space, and deliberately not C or Shift), so the
     * faithful port happens to fix it.
     */
    private void handleKey(KeyEvent event, boolean pressed) {
        if (event.getCode() == KeyCode.ESCAPE) {
            if (pressed) {
                // Escape is not a special case: it lands a running transition like anything else,
                // and then asks to leave. During the way in the second half does nothing, because
                // leaveThreeD refuses while a transition is running, so the first Escape lands
                // you and a second takes you out, which is one rule rather than an exception, and
                // the second press arrives almost immediately since the first removed the wait.
                onAnyInput.run();
                onExitRequested.run();
            }
            event.consume();
            return;
        }

        Control control = controlFor(event.getCode());
        if (control == null) {
            return;
        }
        boolean changed = input.set(control, pressed);

        // Only a fresh press, and WalkInput's own edge answer is what says so: no second copy of
        // the key state kept here to compare against. A key held from before the way in began
        // reaches us as its first auto-repeat, which is an up-to-down edge like any other, and
        // counts: somebody holding W while they click the 3D button meant it.
        //
        // `changed` is a statement of intent rather than a rule with teeth, and the mutation sweep
        // says so: dropping it, so that every auto-repeat asks again, changes no outcome, because
        // skipTransition on an already-landed view returns immediately. It is the project's one
        // documented survivor for this milestone. Kept because "a fresh press lands the way in"
        // is what D-11 says, and code that says it is worth more than code that merely does not
        // contradict it.
        if (changed && pressed) {
            onAnyInput.run();
        }

        if (consumes(event.getCode())) {
            event.consume();
        }
    }

    private static Control controlFor(KeyCode code) {
        switch (code) {
            case W: return Control.FORWARD;
            case S: return Control.BACK;
            case A: return Control.LEFT;
            case D: return Control.RIGHT;
            case SPACE: return Control.UP;
            // C is the original's own choice, made because Ctrl+W closes a browser tab. That is
            // not a constraint in a desktop application, so Ctrl works as well (CLAUDE.md §5.5
            // D-13). C is kept, so nothing is taken away from anyone used to the original.
            case C:
            case CONTROL: return Control.DOWN;
            case SHIFT: return Control.BOOST;
            default: return null;
        }
    }

    private static boolean consumes(KeyCode code) {
        return code == KeyCode.W || code == KeyCode.A || code == KeyCode.S
                || code == KeyCode.D || code == KeyCode.SPACE;
    }

    // ------------------------------------------------------------- looking around
    //
    // Two ways to look, and SPEC-3D-VIEW.md §4 defines both: with the pointer captured, at
    // 0.0022 rad/px, and "if pointer lock is unavailable or denied" by dragging, at 1.4 times that
    // to make up for the shorter travel a window allows.
    //
    // JavaFX has no Pointer Lock API, so capture is emulated: hide the cursor, and after every
    // movement warp it back to the middle of the window so it can never reach an edge. That is the
    // standard trick and it comes with the standard hazard: the warp itself produces another
    // movement event, and feeding that back in makes the camera jitter or drift. It is discarded
    // by comparing against the exact place the pointer was sent, rather than by a re-entrancy flag,
    // so a genuine movement that happens to land on the center pixel is not the failure case.

    private void handlePress(MouseEvent event) {
        if (isFinger(event) || event.getButton() != MouseButton.PRIMARY
                || isIgnored(event.getTarget())) {
            return;
        }
        pressing = true;
        pressTravelPx = 0;
        lastPointerX = event.getSceneX();
        lastPointerY = event.getSceneY();
        onAnyInput.run();
        // Once you start dragging you are turning your head, not pointing at anything, so a
        // tooltip left up would be naming whatever happened to be under the cursor when the drag
        // began, which after a few degrees is nowhere near where it is pointing.
        endHover();
    }

    private void handleDrag(MouseEvent event) {
        if (isFinger(event) || !pressing) {
            return;
        }
        // Redundant with the press that must have preceded it, and kept anyway so that "dragging
        // to look lands the transition" is true on its own terms rather than by implication.
        onAnyInput.run();

        double dx = event.getSceneX() - lastPointerX;
        double dy = event.getSceneY() - lastPointerY;
        lastPointerX = event.getSceneX();
        lastPointerY = event.getSceneY();
        pressTravelPx += Math.hypot(dx, dy);

        // While captured the pointer does not move, so a drag can only be the fallback path.
        if (!capturing) {
            Walk.look(camera, dx, dy, Walk.MOUSE_SENS_RAD_PER_PX * Walk.DRAG_LOOK_MULT);
            drawOneFrame.run();
        }
    }

    private void handleRelease(MouseEvent event) {
        if (isFinger(event) || !pressing) {
            return;
        }
        pressing = false;

        // A press that barely moved is a click, and a click toggles the capture. Without the
        // travel test the release that ends a look-drag would toggle it too, so every drag would
        // finish by capturing or letting go of the pointer.
        if (event.getButton() == MouseButton.PRIMARY && pressTravelPx < CLICK_SLOP_PX) {
            if (capturing) {
                releaseCapture();
            } else {
                tryCapture();
            }
        }
    }

    private void handleMove(MouseEvent event) {
        if (isFinger(event)) {
            return;
        }
        if (!capturing) {
            // Pointing rather than looking. The original does exactly this split: it ray-picks
            // "when the pointer is neither locked nor dragging" and does not otherwise, so with
            // the pointer captured there is no hovering at all, which is right, because a
            // captured pointer is pinned to the middle and has stopped meaning "here".
            updateHover(event);
            return;
        }
        if (robot == null || scene == null) {
            return;
        }
        double dx = event.getScreenX() - lastCaptureX;
        double dy = event.getScreenY() - lastCaptureY;
        lastCaptureX = event.getScreenX();
        lastCaptureY = event.getScreenY();

        // The warp's own event. Discarded by position rather than by a flag, so the only thing
        // ignored is a movement that ended up exactly where the pointer was just sent.
        if (dx == 0 && dy == 0) {
            return;
        }

        // A quarter of the window in one event is not a hand; it is the pointer being moved by
        // something that is not us. Three of those in a row and capture is not ours to have here.
        if (Math.abs(dx) > scene.getWidth() * IMPLAUSIBLE_JUMP_FRACTION
                || Math.abs(dy) > scene.getHeight() * IMPLAUSIBLE_JUMP_FRACTION) {
            implausibleJumps++;
            if (implausibleJumps >= JUMPS_BEFORE_GIVING_UP) {
                captureWorks = false;
                releaseCapture();
                System.out.println("InvMgr: something else is moving the pointer (a virtual "
                        + "machine's mouse integration will do this); mouse capture is off for "
                        + "this session. Drag with the left button to look around.");
            }
            return;
        }
        implausibleJumps = 0;

        Walk.look(camera, dx, dy, Walk.MOUSE_SENS_RAD_PER_PX);
        drawOneFrame.run();

        // Put the pointer back only when it is running out of room, NOT after every movement.
        // Every warp makes another movement event, so re-centering constantly means half the
        // stream is echo, which is fine until something delays or overrides the warp, at which
        // point the echoes become enormous phantom deltas. This way a normal look never warps at
        // all, and the deltas above are read between consecutive real positions rather than
        // against a center the pointer may not have stayed at.
        double x = event.getScreenX();
        double y = event.getScreenY();
        if (x < screenLeft() + RECENTER_MARGIN_PX || x > screenRight() - RECENTER_MARGIN_PX
                || y < screenTop() + RECENTER_MARGIN_PX
                || y > screenBottom() - RECENTER_MARGIN_PX) {
            recenter();
        }
    }

    /** Puts the pointer in the middle of the window and remembers that that is where it is. */
    private void recenter() {
        lastCaptureX = centerScreenX();
        lastCaptureY = centerScreenY();
        robot.mouseMove(lastCaptureX, lastCaptureY);
    }

    /**
     * Works out what the pointer is resting on and says so.
     *
     * <p>Called on every plain pointer movement. <b>The item is looked up fresh each time rather
     * than cached against the last id</b>, and that is deliberate: caching would be an
     * optimization with no measurement behind it, which CLAUDE.md §2 rules out. The work is one
     * ray against at most five hundred boxes, only on real pointer movements (not per frame), and
     * the boxes are already in a list the renderer holds.
     */
    private void updateHover(MouseEvent event) {
        String id = picker.itemAt(event.getSceneX(), event.getSceneY());
        hoveredId = id;
        if (id == null) {
            onHoverEnded.run();
        } else {
            onHover.hovered(id, event.getSceneX(), event.getSceneY());
        }
    }

    /** Forgets what was being pointed at and takes the tooltip away. */
    private void endHover() {
        if (hoveredId != null) {
            hoveredId = null;
        }
        onHoverEnded.run();
    }

    /**
     * Whether this mouse event was manufactured from a finger rather than reported by a mouse.
     *
     * <p>Android makes a mouse press, drag and release out of the <em>first</em> finger on the glass
     * and flags every one of them: Monocle's {@code MouseInputSynthesizer} ends with
     * {@code MouseInput.setState(state, true)}, and that {@code true} is this flag. So on a phone
     * every one-finger look would otherwise be applied twice, once at 0.006 rad/px by the touch path
     * and once at 0.00308 by the drag fallback, and the release at the end of it would try to hide
     * and warp a pointer that is not there.
     *
     * <p><b>Ignored, never consumed.</b> Those synthesized events are exactly what JavaFX's own
     * {@code ScrollPaneSkin} pans the item list with, so swallowing them at the scene freezes every
     * list on the phone and nowhere else (CLAUDE.md §5.8 item 5, the trap M6.2 documented).
     */
    private static boolean isFinger(MouseEvent event) {
        return event.isSynthesized();
    }

    // ------------------------------------------------------------------- fingers
    //
    // The port of the original's touch branch (original lines 3152-3210) and of wireJoystick3d's
    // three document-level listeners. The recognizer is threed.WalkGesture and the thumbstick is
    // threed.Joystick; what is left here is deciding which finger is which.

    private void handleTouchPressed(TouchEvent event) {
        TouchPoint point = event.getTouchPoint();
        if (isIgnored(point.getTarget())) {
            return;
        }
        onAnyInput.run();

        claimTheStick(event);

        if (joystick.owns(point.getId())) {
            // The thumb on the stick is not one of the fingers that look, and the original stops
            // this press reaching the recognizer at all, so a look already under way with another
            // finger carries on undisturbed rather than restarting from here.
            return;
        }

        gesture.began(otherFingers(event), nowMs());
        // Once you start looking around you are turning your head, not pointing at anything, so a
        // tooltip left up would name whatever happened to be under the finger when it landed.
        endHover();
    }

    /**
     * Takes hold of the joystick from whichever finger in this batch is on it, if any.
     *
     * <p><b>Done for the whole batch rather than only for the finger this event is about</b>, and
     * that is not tidiness. Two fingers can land within the same frame and JavaFX fires a separate
     * event for each of them (see this class's note 2), in an order nobody chooses. If the finger on
     * the room were handled first, the finger about to take the stick would still look like an
     * ordinary second finger, so the recognizer would enter a two-finger pan, and then never leave
     * it, because from the next event onward there is only one finger it owns and a pan wants two.
     * One finger sits dead until it is lifted.
     *
     * <p>The original has the identical race for the identical reason, so this is a bug fix rather
     * than a §5.5 divergence, the same category as the keys a window never hears released.
     *
     * <p>Idempotent: once the stick is held, {@link #grabsTheStick} is false for everybody.
     */
    private void claimTheStick(TouchEvent event) {
        for (TouchPoint candidate : event.getTouchPoints()) {
            if (candidate.getState() != TouchPoint.State.RELEASED && grabsTheStick(candidate)) {
                deflectStick(candidate);
                return;
            }
        }
    }

    private void handleTouchMoved(TouchEvent event) {
        TouchPoint point = event.getTouchPoint();
        if (joystick.owns(point.getId())) {
            deflectStick(point);
            return;
        }
        if (isIgnored(point.getTarget())) {
            return;
        }
        onAnyInput.run();
        gesture.moved(otherFingers(event), camera, room);
        drawOneFrame.run();
    }

    private void handleTouchReleased(TouchEvent event) {
        TouchPoint point = event.getTouchPoint();
        if (joystick.owns(point.getId())) {
            joystick.release();
            if (joystickView != null) {
                joystickView.release();
            }
            return;
        }
        if (isIgnored(point.getTarget())) {
            return;
        }

        WalkGesture.Tap tap = gesture.ended(otherFingers(event), nowMs());
        if (tap == null) {
            return;
        }
        String id = picker.itemAt(tap.x(), tap.y());
        if (id != null) {
            onTap.tapped(id, tap.x(), tap.y());
        }
        // A tap on empty room deliberately does nothing at all, as the original's `if (item)` does.
        // Taking an existing tooltip away instead would be a second way of hiding it, competing with
        // the one that counts itself out.
    }

    /** Whether this press is a thumb landing on the joystick, rather than a finger on the room. */
    private boolean grabsTheStick(TouchPoint point) {
        // A stick that is already held is not up for grabs: the original's `if (joy3d.active)
        // return`. A second thumb on the base is left to the look recognizer, exactly as it is
        // there, because otherTouches3d filters out only the finger that owns the stick.
        return joystickView != null && !joystick.isActive() && point.belongsTo(joystickView);
    }

    /** Moves the stick to wherever the thumb is, and draws the knob there. */
    private void deflectStick(TouchPoint point) {
        Point2D offset = joystickView.fromCenter(point.getSceneX(), point.getSceneY());
        if (joystick.isActive()) {
            joystick.moveTo(offset.getX(), offset.getY());
        } else {
            joystick.grab(point.getId(), offset.getX(), offset.getY());
        }
        joystickView.deflect(joystick.knobX(), joystick.knobY());
    }

    /**
     * The fingers the look-and-pan recognizer owns: everything on the glass except the one holding
     * the joystick, anything on the return button, and any that are in the act of lifting.
     *
     * <p>The port of the original's {@code otherTouches3d}, with two things it does not need to say
     * and this does.
     *
     * <p><b>A lifting finger is dropped.</b> A browser's {@code e.touches} on {@code touchend}
     * already excludes the finger that ended; JavaFX's {@code getTouchPoints()} includes it, marked
     * {@code RELEASED}. Keeping it would mean a two-finger pan carried on being a pan for one more
     * event after a finger left, computing a midpoint from a finger that is no longer on the glass.
     *
     * <p><b>They are sorted by identifier.</b> Nothing promises the platform reports them in a
     * stable order, and with three fingers down the recognizer reads the first two, so an order
     * that changed between events would swap which pair is being followed and jump the camera by the
     * distance between the two midpoints. Sorting is not the original's (a browser's list is in the
     * order the fingers landed) but it is what makes that guarantee true here rather than assumed.
     */
    private double[] otherFingers(TouchEvent event) {
        java.util.List<TouchPoint> mine = new java.util.ArrayList<>();
        for (TouchPoint point : event.getTouchPoints()) {
            if (point.getState() == TouchPoint.State.RELEASED
                    || joystick.owns(point.getId())
                    || isIgnored(point.getTarget())) {
                continue;
            }
            mine.add(point);
        }
        mine.sort(java.util.Comparator.comparingInt(TouchPoint::getId));

        double[] xy = new double[mine.size() * 2];
        for (int i = 0; i < mine.size(); i++) {
            xy[2 * i] = mine.get(i).getSceneX();
            xy[2 * i + 1] = mine.get(i).getSceneY();
        }
        return xy;
    }

    /** Lets go of every finger. Safe to call when none was ever down. */
    private void releaseFingers() {
        joystick.release();
        gesture.canceled();
        if (joystickView != null) {
            // reset() rather than release(): the whole thing is about to be hidden, and an ease
            // playing against a hidden node is a timer nobody stops.
            joystickView.reset();
        }
    }

    private static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }

    /**
     * Hides the pointer and starts warping it back to the middle, or gives up on capture for the
     * rest of the session and leaves you with drag-to-look.
     *
     * <p><b>The warp is checked rather than assumed.</b> Synthetic pointer movement is commonly
     * refused outright under Wayland, and some compositors rate-limit it. A refusal is silent: the
     * call returns normally and the pointer simply does not move, and the symptom in the app is a
     * camera that will not turn with no error anywhere, which is the exact failure mode CLAUDE.md
     * §5.7 item 8 warns about. So the first attempt sends the pointer to the middle and reads back
     * where it actually went; if it is not there, capture is written off, said out loud once, and
     * the drag path carries on working.
     */
    private void tryCapture() {
        if (Boolean.FALSE.equals(captureWorks) || scene == null) {
            return;
        }
        double centerX = centerScreenX();
        double centerY = centerScreenY();

        if (captureWorks == null) {
            try {
                robot = new javafx.scene.robot.Robot();
                robot.mouseMove(centerX, centerY);
                captureWorks = Math.abs(robot.getMouseX() - centerX) <= WARP_TOLERANCE_PX
                        && Math.abs(robot.getMouseY() - centerY) <= WARP_TOLERANCE_PX;
            } catch (Throwable refused) {
                // Throwable rather than Exception on purpose: a platform without a Robot at all
                // fails at class loading, which is an Error, and that must land here too rather
                // than take the 3D view down with it.
                captureWorks = false;
            }
            if (!captureWorks) {
                robot = null;
                System.out.println("InvMgr: mouse capture is not available on this display; "
                        + "drag with the left button to look around instead.");
                return;
            }
        }

        capturing = true;
        implausibleJumps = 0;
        recenter();
        scene.setCursor(Cursor.NONE);
    }

    private double screenLeft() {
        return scene.getWindow().getX() + scene.getX();
    }

    private double screenTop() {
        return scene.getWindow().getY() + scene.getY();
    }

    private double screenRight() {
        return screenLeft() + scene.getWidth();
    }

    private double screenBottom() {
        return screenTop() + scene.getHeight();
    }

    /**
     * Gives the pointer back. Safe to call when it was never taken.
     *
     * <p>Public for the same reason {@link #stopWalking()} is: the exit path calls it as the
     * climb starts, so the cursor comes back where you left it rather than two seconds later
     * in the middle of the window. See {@code View3D.closeWithAscent}.
     */
    public void releaseCapture() {
        if (!capturing) {
            return;
        }
        capturing = false;
        if (scene != null) {
            scene.setCursor(Cursor.DEFAULT);
        }
    }

    /** Whether the pointer is currently hidden and locked to the middle of the window. */
    public boolean isCapturing() {
        return capturing;
    }

    /**
     * The middle of the window in screen pixels, <b>rounded to a whole one</b>.
     *
     * <p>The rounding is the whole point and it is not cosmetic. A window with an odd width puts
     * the true center on a half pixel; the pointer can only sit on a whole one, so the warp lands
     * half a pixel off, every single move reports that same half pixel as movement, and the camera
     * turns slowly and forever with the mouse sitting still. Rounding first means the place the
     * pointer is sent and the place it can actually be are the same place, so a move with no hand
     * on the mouse is exactly zero.
     */
    private double centerScreenX() {
        Window window = scene.getWindow();
        return Math.round(window.getX() + scene.getX() + scene.getWidth() / 2);
    }

    /** @see #centerScreenX */
    private double centerScreenY() {
        Window window = scene.getWindow();
        return Math.round(window.getY() + scene.getY() + scene.getHeight() / 2);
    }

    private boolean isIgnored(EventTarget target) {
        if (ignoreTarget == null || !(target instanceof Node)) {
            return false;
        }
        for (Node node = (Node) target; node != null; node = node.getParent()) {
            if (node == ignoreTarget) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forgets every held key when the window stops being the active one.
     *
     * <p>Alt-tab away with a key held and the key-up happens in whatever window you moved to, so
     * this one never hears about it and the camera walks off by itself until you come back and
     * press that key again. The original has the same bug, inherited from the browser; it is a bug
     * rather than a behavior, so fixing it is not a divergence.
     */
    private void watchWindowFocus(Scene scene) {
        onWindowFocus = (observable, wasFocused, isFocused) -> {
            if (!isFocused) {
                input.clear();
                // The same for fingers, and for a stronger reason than the keys: a touch that ends
                // outside the window never reports at all, so a thumb left on the stick would walk
                // the camera into a wall for as long as the app was in the background.
                releaseFingers();
                // And give the pointer back, or it stays invisible and pinned to the middle of a
                // window you are no longer using.
                releaseCapture();
            }
        };
        watchedWindow = scene.getWindow();
        if (watchedWindow != null) {
            watchedWindow.focusedProperty().addListener(onWindowFocus);
            return;
        }
        // The scene can be handed to us before it is in a window. Catch the window when it
        // arrives rather than silently going without the guard.
        scene.windowProperty().addListener(new javafx.beans.value.ChangeListener<Window>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Window> observable,
                    Window was, Window is) {
                scene.windowProperty().removeListener(this);
                if (is != null && onWindowFocus != null) {
                    watchedWindow = is;
                    is.focusedProperty().addListener(onWindowFocus);
                }
            }
        });
    }
}
