package com.modcritic.invmgr.ui;

import javafx.scene.paint.Color;

/**
 * Every color, size and duration the interface uses, in one place.
 *
 * <p>These are not preferences; they are transcribed from {@code SPEC-DESIGN-SYSTEM.md},
 * which was in turn read out of the original app's stylesheet. The Java app is meant to look
 * like the original, not merely similar to it, and a value invented here is a visual bug.
 *
 * <p>Named after what each value is <em>for</em> rather than what color it is, so that
 * {@code #1a1a1a} and {@code #181818} (indistinguishable by eye and both in use) cannot be
 * swapped by accident. Two names sometimes hold the same color; that is on purpose, because
 * the same gray serving two roles can stop serving one of them without the other changing.
 */
public final class Tokens {

    private Tokens() {
    }

    // ---------------------------------------------------------------- surfaces

    /** Body and status bar. */
    public static final Color BODY_BG = Color.web("#1a1a1a");

    /** The area the room floats in. Deliberately darker than the body. */
    public static final Color CANVAS_WRAP_BG = Color.web("#181818");

    /** The room floor itself. */
    public static final Color ROOM_FILL = Color.web("#3a3a3a");

    /** The layer slider drawer down the left edge. */
    public static final Color DRAWER_BG = Color.web("#202020");

    /** The top bar across the head of the window. */
    public static final Color TOP_BAR_BG = Color.web("#252525");

    /** Number-entry fields in the top bar. Note this is NOT the dialog input color. */
    public static final Color TOP_BAR_INPUT_BG = Color.web("#333");

    /** The item list panel down the right edge. */
    public static final Color LIST_PANEL_BG = Color.web("#1e1e1e");

    /** The search box in that panel, darker still than the panel around it. */
    public static final Color SEARCH_BG = Color.web("#161616");

    /** A dialog's panel, and the planned-item drag ghost. */
    public static final Color DIALOG_BG = Color.web("#2a2a2a");

    /** Text and number fields inside a dialog. Same value as the body, a different role. */
    public static final Color DIALOG_INPUT_BG = Color.web("#1a1a1a");

    /** The hover tooltip's background, the darkest surface in the app. */
    public static final Color TOOLTIP_BG = Color.web("#111");

    // ------------------------------------------------------------------- lines

    /** Panel and drawer borders, and the rule under a dialog's title. */
    public static final Color BORDER = Color.web("#444");

    /** Grid lines on the floor, 2 px, one per foot. */
    public static final Color GRID_LINE = Color.rgb(0, 0, 0, 0.5);

    /** The 2 px border around every item, drawn inside its footprint. */
    public static final Color ITEM_BORDER = Color.rgb(0, 0, 0, 0.4);

    /** The outline around the selected item. */
    public static final Color SELECTION_OUTLINE = Color.WHITE;

    /** The hairline under each row of the item list. */
    public static final Color LIST_ROW_BORDER = Color.web("#2a2a2a");

    /** The tooltip's one-pixel edge. */
    public static final Color TOOLTIP_BORDER = Color.web("#666");

    // -------------------------------------------------------------------- text

    /**
     * The "Layer" label above the slider.
     *
     * <p>Note: {@code SPEC-DESIGN-SYSTEM.md}'s token table lists section headers as
     * {@code #aaa}, but the original's stylesheet sets this particular label to {@code #888}
     * at 10 px. The stylesheet wins, per CLAUDE.md §7.
     */
    public static final Color DRAWER_LABEL_TEXT = Color.web("#888");

    /** Whole-foot tick labels beside the slider. */
    public static final Color TICK_TEXT = Color.web("#777");

    /** Half-foot tick marks, deliberately much dimmer than the whole feet. */
    public static final Color TICK_HALF_TEXT = Color.web("#444");

    /**
     * The {@code 1m 2m 3m} labels overlaid on the tick column in metric mode.
     *
     * <p>The same blue as {@link #SLIDER_ACCENT}, but a separate token because it is a separate
     * job: the original gives it its own {@code .meter-label} rule. Blue rather than gray so the
     * meter labels read as a different scale from the foot dots they sit between.
     */
    public static final Color METER_TICK_TEXT = Color.web("#7ab");

    /** The layer slider's own accent color. */
    public static final Color SLIDER_ACCENT = Color.web("#7ab");

    /** Button labels and general interface text. */
    public static final Color TEXT_PRIMARY = Color.web("#ccc");

    /** Text typed into an input, and dialog and tooltip text. */
    public static final Color TEXT_INPUT = Color.web("#ddd");

    /** Captions beside a dialog's fields. */
    public static final Color TEXT_DIALOG_LABEL = Color.web("#bbb");

    /** Panel headings, the "Items" above the list. */
    public static final Color TEXT_SECTION_HEADER = Color.web("#aaa");

    /** Small icon buttons at rest: the export arrow and the search clear cross. */
    public static final Color TEXT_QUIET = Color.web("#999");

    /** The {@code ID:} caption in a dialog's button row. */
    public static final Color TEXT_ID_LABEL = Color.web("#888");

    /** The {@code | Presets:} caption in the Add Item dialog's title. */
    public static final Color TEXT_PRESET_LABEL = Color.web("#777");

    /** The status bar's text, deliberately quiet. Also the empty-list message. */
    public static final Color TEXT_STATUS = Color.web("#666");

    /** Borders on buttons and inputs. One pixel, everywhere. */
    public static final Color CONTROL_BORDER = Color.web("#555");

    /** The status bar's top border, and the search row's. */
    public static final Color SEPARATOR = Color.web("#333");

    /**
     * How much of the text color a grayed-out placeholder keeps.
     *
     * <p>Browsers draw placeholder text at 54% of the field's own text color, blended into the
     * field's background, which is why the search box's placeholder measures {@code #828282}
     * and the dialogs' measures {@code #838383} despite both fields using {@code #ddd} text.
     * Two different grays for one rule, so the rule is what is stored here rather than the
     * grays.
     */
    private static final double PLACEHOLDER_STRENGTH = 0.54;

    /** The placeholder color for a field with the given background. */
    public static Color placeholderOver(Color background) {
        return TEXT_INPUT.interpolate(background, 1 - PLACEHOLDER_STRENGTH);
    }

    // -------------------------------------------------- button identities
    //
    // Each button that carries meaning has its own color, so a glance tells you which modes
    // are on. Keeping them distinct is a stated design principle, not an accident.

    public static final Color BUTTON_BG = Color.web("#383838");
    public static final Color BUTTON_BG_HOVER = Color.web("#484848");
    public static final Color BUTTON_SET_ROOM_BG = Color.web("#3a4a3a");

    /** Undo: a warm brown, distinct from every mode toggle. */
    public static final Color BUTTON_UNDO_BG = Color.web("#4a3a2e");

    /** The Add button's cool blue-gray. */
    public static final Color BUTTON_ADD_BG = Color.web("#2e3e4e");

    /**
     * The 3D button's color: <b>the plain default</b>, deliberately the same value as
     * {@link #BUTTON_BG} rather than an identity color of its own; see CLAUDE.md §5.5
     * <b>D-9</b>. Decided by the user 2026-08-05.
     *
     * <p>The original's stylesheet (line 426) gives this button {@code #2e3a4e}, a cool blue-gray
     * <em>one digit</em> from the Add button's {@code #2e3e4e}. The two sit side by side in the
     * bar, so on screen they read as one block: the distinction is real in the stylesheet and
     * invisible in the window, which means it was never doing the job the section above asks
     * button color to do. <b>Gold {@code #A67F30} was tried on 2026-08-05 and rejected by the
     * user the same day</b>, so the answer is not a louder identity color; it is to stop
     * claiming this button has one. Save and Load do not, and neither does this.
     *
     * <p><b>It stays a separate constant even though it now equals {@link #BUTTON_BG}.</b> The
     * value is a recorded divergence from a documented original value; collapsing the two would
     * lose the record and make the next change to the default silently drag this button along
     * with it.
     *
     * <p>The hover was always the shared gray {@link #BUTTON_BG_HOVER} and now genuinely is
     * shared, with nothing special about this button at all. The white cube icon reads at about
     * 9.9:1 here, better than it did on the gold; {@code assets/icons/btn-3d.svg} is untouched.
     */
    public static final Color BUTTON_3D_BG = Color.web("#383838");

    /** Fit, Plan and Units share a quieter base than the plain buttons. */
    public static final Color BUTTON_TOGGLE_BG = Color.web("#303030");

    /**
     * The touch island's {@code ▼} menu button, which folds the top bar away.
     *
     * <p>Its own faintly blue-green gray, from {@code #top-island button#island-menu}. Nothing else
     * in the app is this color, which is the point: it is the only button in the island that does
     * not do something to the room.
     */
    public static final Color BUTTON_ISLAND_MENU_BG = Color.web("#303838");

    /** Confirm, OK and Save, plus the preset row's green {@code +}. */
    public static final Color BUTTON_CONFIRM_BG = Color.web("#2e4e3e");
    public static final Color BUTTON_CONFIRM_BG_HOVER = Color.web("#3e6e5e");

    /** Delete, and only Delete. */
    public static final Color BUTTON_DANGER_BG = Color.web("#5a2020");
    public static final Color BUTTON_DANGER_BG_HOVER = Color.web("#7a2a2a");
    public static final Color BUTTON_DANGER_BORDER = Color.web("#833");

    /** Fit, when on: blue. */
    public static final Color TOGGLE_FIT_BG = Color.web("#2a4a5a");
    public static final Color TOGGLE_FIT_BORDER = Color.web("#5af");
    public static final Color TOGGLE_FIT_TEXT = Color.web("#9df");

    /** Plan, when on: purple. */
    public static final Color TOGGLE_PLAN_BG = Color.web("#4a2e50");
    public static final Color TOGGLE_PLAN_BORDER = Color.web("#b06dc8");
    public static final Color TOGGLE_PLAN_TEXT = Color.web("#d9a0ee");

    /** Units, when metric: green. */
    public static final Color TOGGLE_UNITS_BG = Color.web("#2e5a30");
    public static final Color TOGGLE_UNITS_BORDER = Color.web("#6dc87a");
    public static final Color TOGGLE_UNITS_TEXT = Color.web("#a0eeae");

    /** Layer Collision, when on: amber. */
    public static final Color TOGGLE_LAYER_BG = Color.web("#4a3620");
    public static final Color TOGGLE_LAYER_BORDER = Color.web("#c88a3a");
    public static final Color TOGGLE_LAYER_TEXT = Color.web("#f0b060");

    // ------------------------------------------------------ list and presets

    /** A row under the pointer. */
    public static final Color LIST_ROW_HOVER = Color.web("#2a2a2a");

    /** A <em>planned</em> row under the pointer, quieter, because the row is already faded. */
    public static final Color LIST_ROW_HOVER_PLANNED = Color.web("#252525");

    /** The selected row, and a filled preset slot: the same blue-gray. */
    public static final Color LIST_ROW_SELECTED = Color.web("#2e3e4e");
    public static final Color PRESET_FILLED_BG = Color.web("#2e3e4e");

    /** How faded a planned item's row is, since it is not really in the room. */
    public static final double PLANNED_ROW_OPACITY = 0.45;

    // ------------------------------------------------------------------- sizes

    /** Width of the layer slider drawer, in pixels. Fixed, never flexible. */
    public static final double SLIDER_DRAWER_WIDTH = 58;

    /** The slider control itself within that drawer. */
    public static final double SLIDER_WIDTH = 22;

    /** Width of the item list panel on desktop. Touch widens it to 200. */
    public static final double LIST_PANEL_WIDTH = 180;

    /**
     * And on touch, where it is an overlay rather than a column beside the room.
     *
     * <p>Wider than the desktop's 180 even though the screen is a fifth of the size, and that is
     * the original's own number. It reads as backwards until you notice the drawer is no longer
     * <em>taking</em> that width from the room: closed it costs nothing, so it can afford to be
     * comfortable when it is open, and its rows are set in bigger type for a finger.
     */
    public static final double LIST_PANEL_WIDTH_TOUCH = 200;

    /** Space around the room when Fit mode is off. */
    public static final double CANVAS_MARGIN = 20;

    /** Minimum space around the room when Fit mode is on. */
    public static final double FIT_PADDING = 10;

    public static final double ITEM_BORDER_WIDTH = 2;
    public static final double GRID_LINE_WIDTH = 2;

    /** Selection outline: 3 px thick, sitting 1 px clear of the item's edge. */
    public static final double SELECTION_OUTLINE_WIDTH = 3;
    public static final double SELECTION_OUTLINE_OFFSET = 1;

    /** How dimmed an item gets when it is above and overlapping the selected one. */
    public static final double DIM_OPACITY = 0.5;

    /**
     * How far the pointer must move before a press counts as a drag rather than a click.
     * Coarser for touch, because a fingertip is never perfectly still.
     */
    public static final double DRAG_THRESHOLD_MOUSE_PX = 3;
    public static final double DRAG_THRESHOLD_TOUCH_PX = 6;

    /**
     * How far a finger or pointer must move before dragging a <em>row out of the item list</em>
     * counts as a drag.
     *
     * <p>Six for mouse and touch alike, the original's one {@code DRAG_ROW_THRESHOLD}. That is
     * why this is its own constant rather than a second name for
     * {@link #DRAG_THRESHOLD_TOUCH_PX}: a box in the room uses 3 for a mouse and 6 for a finger,
     * a row uses 6 for both, and the two numbers being equal today is a coincidence rather than
     * a rule. Pointing this at the touch constant would quietly drag the row rule along with any
     * future retune of the finger one.
     */
    public static final double DRAG_THRESHOLD_ROW_PX = 6;

    // ------------------------------------------------------- hold-to-delete ring

    /**
     * The red square that closes in on a box while a finger is held on it, warning that letting
     * go of nothing is about to delete it.
     *
     * <p><b>It does not change size and it does not sweep round like a progress dial.</b> The
     * original's keyframe is only <em>named</em> {@code holdGrow}; what it animates is opacity,
     * from {@link #HOLD_RING_MIN_OPACITY} to 1 over the 1.5 seconds, at a fixed size (original
     * CSS lines 210-215 and 359-363). The red gets stronger, the ring does not grow. Confirmed
     * with the user 2026-08-07 before any of it was written.
     *
     * <p>Touch only; see {@code CLAUDE.md} §5.5 <b>D-1</b>. A mouse deletes by right-clicking.
     */
    public static final Color HOLD_RING = Color.web("#e55");
    public static final double HOLD_RING_WIDTH = 3;

    /**
     * How far outside the thing it is warning about the ring sits: 5 px around a box in the room,
     * 4 px around a preset. Two numbers because the original uses two: {@code inset: -5px} on
     * {@code .hold-ring} and {@code inset: -4px} on {@code .preset-hold-ring}.
     */
    public static final double HOLD_RING_INSET_ITEM = 5;
    public static final double HOLD_RING_INSET_PRESET = 4;

    /** Where the fade starts. Faint enough to read as a warning rather than a change. */
    public static final double HOLD_RING_MIN_OPACITY = 0.2;

    /**
     * How long the tooltip stays up after a tap, in milliseconds.
     *
     * <p>A finger has no hover, so nothing tells the tooltip when to go away; it has to time
     * out. This is the original's value for a name short enough not to need scrolling; one that
     * does scroll gets longer, so that it is never taken away mid-pass; see
     * {@link #TOUCH_TOOLTIP_MARQUEE_TAIL_MS}.
     */
    public static final double TOUCH_TOOLTIP_HOLD_MS = 2500;

    /**
     * How close a tapped tooltip may come to the edge of the window, in pixels. The original's
     * {@code TOOLTIP_MARGIN}, and the same eight {@link Hints#SCREEN_MARGIN} keeps a dialog off it.
     */
    public static final double TOUCH_TOOLTIP_MARGIN = 8;

    /**
     * The narrowest a tapped tooltip is ever squeezed to, in pixels. The original's floor of 60.
     *
     * <p>It bites for a box hard against a wall, where the budget below works out at almost
     * nothing. Sixty pixels of scrolling name is not much, but it is readable, and the alternative
     * is a tooltip that has been shrunk to the width of its own border.
     */
    public static final double TOUCH_TOOLTIP_MIN_WIDTH = 60;

    /**
     * How fast a name too long to fit slides past, in pixels per second. The original's
     * {@code TOOLTIP_MARQUEE_PX_PER_SEC}.
     */
    public static final double TOUCH_TOOLTIP_MARQUEE_PX_PER_SEC = 55;

    /**
     * How long the name sits still before it starts moving, in milliseconds. The original's
     * {@code TOOLTIP_MARQUEE_DELAY_MS}, long enough to read the first few characters, which are
     * usually the ones that identify the box.
     */
    public static final double TOUCH_TOOLTIP_MARQUEE_DELAY_MS = 700;

    /**
     * The shortest a single pass may take, in seconds. The original's {@code Math.max(2, ...)}.
     *
     * <p>Without it a name only slightly too long would whip past in a fraction of a second,
     * because the distance traveled is what sets the duration.
     */
    public static final double TOUCH_TOOLTIP_MARQUEE_MIN_PASS_S = 2;

    /**
     * How long a scrolling tooltip lingers after its first full pass, in milliseconds. The
     * original's trailing {@code + 600}.
     */
    public static final double TOUCH_TOOLTIP_MARQUEE_TAIL_MS = 600;

    // -------------------------------------------------------------------- type

    /**
     * The interface's typeface, for every word in the app.
     *
     * <p>This used to be the string {@code "monospace"}, copied from the original's stylesheet.
     * That is not a font but a generic <em>alias</em>, which each system answers with a different
     * typeface, so the app looked different on every platform. It now names a real face that
     * ships inside the jar; see {@link Fonts}, and {@code CLAUDE.md} §5.5 <b>D-6</b> for why
     * departing from the original here was a deliberate decision.
     *
     * <p><b>Do not replace this with the literal {@code "Noto Sans Mono"}.</b> It would compile
     * and it would run, but a {@code static final String} set to a literal is a compile-time
     * constant, and reading one never runs the code that loads the fonts. The interface would
     * fall back to a proportional system face without reporting anything. {@link Fonts} explains
     * the mechanism in full; {@code FontsTest} fails if it is ever broken.
     */
    public static final String FONT_FAMILY = Fonts.TEXT_FAMILY;

    /**
     * The typeface for the two button glyphs the main face has no drawing for: {@code ⤓} and
     * {@code ↻}.
     *
     * <p>The design system says one family and no exceptions, and that still holds for text. This
     * is not a second design choice; it is a patch over a gap in the first font's character set.
     * See {@link Fonts#SYMBOL_FAMILY}.
     */
    public static final String FONT_FAMILY_SYMBOL = Fonts.SYMBOL_FAMILY;

    public static final double FONT_DRAWER_LABEL = 10;
    public static final double FONT_TICK = 9;
    public static final double FONT_TICK_HALF = 8;

    /** Top bar labels, buttons and inputs; also dialog labels and inputs. */
    public static final double FONT_CONTROL = 13;

    /** Dialog headings. */
    public static final double FONT_DIALOG_TITLE = 14;

    /** List rows, the list header, the search box and tooltips. */
    public static final double FONT_LIST_ROW = 12;
    public static final double FONT_LIST_HEADER = 12;
    public static final double FONT_SEARCH = 12;
    public static final double FONT_TOOLTIP = 12;
    public static final double FONT_ID_LABEL = 12;

    /**
     * The tooltip's type on a touch screen, in pixels. The original's
     * {@code @media (pointer: coarse) { .item-tooltip { font-size: 14px; } }}.
     *
     * <p>Bigger than the desktop's 12 because it is read at arm's length rather than at a desk, and
     * because a fingertip has just been over the thing it describes.
     */
    public static final double FONT_TOOLTIP_TOUCH = 14;

    /** The square Add button's ■, which is drawn larger than the text beside it. */
    public static final double FONT_ADD_BUTTON = 18;

    /**
     * The 3D button's cube, in pixels: {@code 1.3em} of the top bar's own 13 px, which is 16.9.
     *
     * <p><b>Not {@link #FONT_ADD_BUTTON}, which is what it was at first and is 18.</b> That number
     * is the Add button's alone: the original gives {@code #add-item-btn} its own
     * {@code font-size: 18px}, while the 3D button carries no such rule and so inherits the bar's
     * 13 px. Since the icon is sized in {@code em}, it follows whichever font it inherits, so
     * borrowing the neighbor's constant drew the cube 6.5% too large.
     *
     * <p>Caught by measuring the ink against {@code desktop-01-default.png}: the reference's cube
     * covers 13 x 15 px and ours covered 14 x 16.
     */
    public static final double ICON_3D_SIZE = 1.3 * FONT_CONTROL;

    /** Preset slots, and the status bar. */
    public static final double FONT_PRESET = 11;
    public static final double FONT_STATUS = 11;

    /** The green {@code +} that adds a preset slot. */
    public static final double FONT_PRESET_ADD = 15;

    // ------------------------------------------------- top bar geometry

    public static final double TOP_BAR_PADDING_V = 7;
    public static final double TOP_BAR_PADDING_H = 10;
    public static final double TOP_BAR_GAP = 10;

    public static final double BUTTON_PADDING_V = 4;
    public static final double BUTTON_PADDING_H = 10;

    /**
     * The Add button's box, measured off the reference at exactly 30 × 28.
     *
     * <p>Pinned for the same reason as {@link #ROOM_FIELD_HEIGHT}, and found the same way, by
     * measuring the screenshots the tests write against the reference ones. Left to JavaFX it came
     * out 33 × 31, because this is the one button drawn at 18 px instead of 13, so the bundled
     * font's line box is proportionally taller here than anywhere else in the bar.
     *
     * <p><b>Three pixels here moved the whole window.</b> The top bar's height is whatever its
     * tallest child needs, so an oversized Add button pushed the bar from the reference's 42 to
     * 46, and every single thing below it (canvas, item list, layer slider, status bar) down
     * with it. Every <em>other</em> top-bar button already measured 28, which is what made this
     * one identifiable as the cause rather than a general drift.
     */
    public static final double ADD_BUTTON_WIDTH = 30;
    public static final double ADD_BUTTON_HEIGHT = 28;

    /** Width of the room's W/L/H entry fields. */
    public static final double ROOM_FIELD_WIDTH = 62;
    public static final double INPUT_PADDING_V = 3;
    public static final double INPUT_PADDING_H = 5;

    /**
     * Height of the room's W/L/H entry fields, measured off the reference at exactly 26.
     *
     * <p>Pinned for the same reason as {@link #DIALOG_NUMBER_HEIGHT}, and added when the app
     * stopped borrowing the computer's typeface: a browser gives a number input a minimum height
     * of its own that owes nothing to the font, where JavaFX sizes it from the text. Left to
     * JavaFX these came out at 31 with the bundled font (five pixels too tall) and the stepper
     * block, which is centered inside them, slid down with them.
     */
    public static final double ROOM_FIELD_HEIGHT = 26;

    public static final double STATUS_PADDING_V = 3;
    public static final double STATUS_PADDING_H = 10;

    /** How long an action's message shows before the instructions return. */
    /**
     * How long after a box drag the room keeps ignoring scroll gestures.
     *
     * <p>Not a chosen number. Glass's {@code ScrollGestureRecognizer} declares
     * {@code SCROLL_INERTIA_MILLIS = 1500}, so its fling can keep arriving for a second and a half
     * after the finger has gone; this is that, with a little to spare. Shorter and the tail escapes,
     * which is exactly what the user saw and described as the room being given momentum.
     *
     * <p>See {@code RoomCanvasView.ignoreScrollWhileDragging} for why any of this is needed.
     */
    public static final double BOX_DRAG_INERTIA_GUARD_MS = 1600;

    public static final double STATUS_REVERT_MS = 3000;

    // ------------------------------------- the touch island and the folding bar

    /**
     * The island's own padding, from {@code #top-island { padding: 5px 10px }}.
     *
     * <p>Fixed, unlike everything else about the island, because it is the strip's own frame rather
     * than part of the type.
     */
    public static final double ISLAND_PADDING_V = 5;
    public static final double ISLAND_PADDING_H = 10;

    /**
     * The island's type, which scales with the island's own width.
     *
     * <p>Three numbers each: a floor in pixels, a percentage of the island's width, and a ceiling.
     * That is CSS's {@code clamp(12px, 4.5cqi, 16px)} written out, and {@link Fluid#size} is the
     * arithmetic. See {@code TouchIsland} for why the ratios between the three groups matter more
     * than any one of the nine numbers.
     *
     * <p>The ceiling is the original desktop design size, so a wide screen (a phone turned
     * sideways) stops the buttons shrinking rather than letting them grow past what they were
     * drawn to be.
     */
    public static final double ISLAND_FONT_MIN = 12;
    public static final double ISLAND_FONT_PERCENT = 4.5;
    public static final double ISLAND_FONT_MAX = 16;

    /** Add is the biggest thing in the island, as it is in the bar. */
    public static final double ISLAND_ADD_FONT_MIN = 15;
    public static final double ISLAND_ADD_FONT_PERCENT = 5.6;
    public static final double ISLAND_ADD_FONT_MAX = 20;

    /** Fit, Plan and Units are the smallest, so the six fit a narrow phone. */
    public static final double ISLAND_TOGGLE_FONT_MIN = 10;
    public static final double ISLAND_TOGGLE_FONT_PERCENT = 3.6;
    public static final double ISLAND_TOGGLE_FONT_MAX = 13;

    /** The gap between the island's buttons scales with it too. */
    public static final double ISLAND_GAP_MIN = 4;
    public static final double ISLAND_GAP_PERCENT = 2.5;
    public static final double ISLAND_GAP_MAX = 8;

    /**
     * The island's buttons are padded in <em>ems of their own type</em>: {@code 0.3em 0.6em}.
     *
     * <p>So the padding scales with the font without being listed as another fluid triple, and a
     * button that shrinks stays the same shape rather than turning into a thin sliver of text in a
     * fixed box.
     */
    public static final double ISLAND_BUTTON_PADDING_V_EM = 0.3;
    public static final double ISLAND_BUTTON_PADDING_H_EM = 0.6;

    /**
     * How tall a touch button's line of text is, in ems: <b>the browser's answer, not JavaFX's.</b>
     *
     * <p>A button's height is its line box plus its padding plus its border, and a line box is a
     * property of the <em>font</em>. Noto Sans Mono, which this app bundles because the original
     * only ever said {@code monospace} (§5.5 D-6), declares generous vertical metrics: JavaFX lays
     * one line out at about <b>1.62 em</b> where the browser the reference screenshots were taken
     * in used about <b>1.18</b>. That is four to seven pixels on every touch button, which is what
     * the user saw and reported as "elongated upward".
     *
     * <p><b>Measured off the original's own screenshots</b>, and the agreement is what makes it
     * trustworthy; three buttons at three different type sizes: Undo (29.3 tall, 15.30 px type)
     * gives 1.184, Fit (23.7 at 12.24) gives 1.173, Save (37.0 at 16) gives 1.188.
     *
     * <p>Pinning the height below what the font asks for is safe, and the original is the proof: a
     * capital and a descender together are about 0.95 em of actual ink, so 1.18 em of box still has
     * room to spare. It is only the font's declared <em>leading</em> being given back.
     */
    public static final double TOUCH_TEXT_LINE_EM = 1.18;

    /**
     * The same number for the two buttons whose label is a drawing rather than a word: the island's
     * {@code ■} and {@code ▼}.
     *
     * <p>They get their own figure because in the original they got their own <em>font</em>. Neither
     * character is in a typical monospace face, so the browser silently borrowed both from a
     * fallback with taller metrics, the same gap §5.5 D-6 records, seen from the other side. Two
     * buttons agree: the Add button (40.7 tall at 19.04 px type) gives 1.433 and the menu arrow
     * (33.3 at 15.30) gives 1.446.
     */
    public static final double TOUCH_GLYPH_LINE_EM = 1.44;

    /**
     * And how <em>wide</em> that fallback drew them: about {@code 0.95em}, against the 0.6 em
     * advance of a monospace face.
     *
     * <p>This is the other half of the same story and the reason the two glyph buttons came out
     * narrower than the original's rather than wider like every text button. Add wants 0.967 em and
     * the menu arrow 0.927; the difference between them is under a pixel at these sizes, so one
     * number serves both rather than inventing a table. Without it the Add button is 36 px wide
     * where the original is 43.
     */
    public static final double TOUCH_GLYPH_ADVANCE_EM = 0.95;

    /**
     * And how <b>tall the mark itself</b> is drawn: about three quarters of the type size.
     *
     * <p>The other half of the same story, and the half that was missed first time round: widening
     * the button made the box right and left the {@code ■} inside it small, which the user reported
     * on 2026-08-13 as <i>"box icon in Add Item button is too small, compare to original"</i>.
     * Measured off the original's screenshot at <b>14.3 × 14.3 CSS px</b> inside a 19.04 px type
     * size (so 0.751 em) against the 9 × 9 this app drew, which is what a monospace face gives
     * for {@code ■} at that size.
     *
     * <p>This is a property of the <em>original's</em> borrowed fallback face, not of ours, so it
     * has to be stated. What must never be written down beside it is our own face's ratio; see
     * {@link Fonts#sizeForInkHeight}, which measures that at runtime.
     */
    public static final double TOUCH_GLYPH_INK_EM = 0.75;

    /** Every one of these buttons carries {@code border: 1px solid}, top and bottom, left and right. */
    public static final double TOUCH_BUTTON_BORDER = 1;

    /** The bar's own padding grows for fingers: {@code #top-bar { padding: 10px 12px }}. */
    public static final double TOP_BAR_PADDING_V_TOUCH = 10;
    public static final double TOP_BAR_PADDING_H_TOUCH = 12;

    /** And its gap becomes fluid: {@code clamp(4px, 2.16cqi, 12px)}. */
    public static final double TOP_BAR_GAP_MIN = 4;
    public static final double TOP_BAR_GAP_PERCENT = 2.16;
    public static final double TOP_BAR_GAP_MAX = 12;

    /**
     * The W/L/H labels and fields on touch: {@code clamp(9px, 3.6cqi, 15px)}.
     *
     * <p>The original's comment says this was calibrated by computing the row's needed width at
     * several phone widths rather than guessed, and its purpose is precise: all three groups must
     * fit on <em>one</em> row, because wrapping mid-group puts a stray {@code H(ft):} under a
     * number that belongs to L.
     */
    public static final double FONT_CONTROL_TOUCH_MIN = 9;
    public static final double FONT_CONTROL_TOUCH_PERCENT = 3.6;
    public static final double FONT_CONTROL_TOUCH_MAX = 15;

    /** The room fields are {@code 4.5ch} wide on touch: four and a half characters of their own type. */
    public static final double ROOM_FIELD_CHARS_TOUCH = 4.5;

    /** The bar's buttons on touch: a fixed 16 px on 8 × 14, big enough to hit. */
    public static final double FONT_BUTTON_TOUCH = 16;
    public static final double BUTTON_PADDING_V_TOUCH = 8;
    public static final double BUTTON_PADDING_H_TOUCH = 14;

    /**
     * The 3D button's cube at touch size: the same 1.3 em as {@link #ICON_3D_SIZE}, of the 16 px
     * type above rather than the desktop's 13, so 20.8 px against 16.9.
     *
     * <p>Written as the same multiplication rather than as a number, because it <em>is</em> the same
     * rule. It lives down here rather than beside its desktop twin only because Java will not let a
     * constant refer forward to one declared later in the file.
     *
     * <p>Measured off the original's own phone screenshot, where the 3D button is 51.0 × 38.7 CSS
     * px, which is this icon inside the padding above and a 1 px border, to a tenth of a pixel.
     * The button had been left out of the touch sizing altogether, so it kept the desktop's 30 × 28.
     */
    public static final double ICON_3D_SIZE_TOUCH = 1.3 * FONT_BUTTON_TOUCH;

    /**
     * How long the bar takes to fold away, and how long its contents take to fade.
     *
     * <p>Two numbers because the original uses two: {@code transition: max-height 0.25s ease,
     * padding 0.25s ease, opacity 0.2s ease}. The fade finishing first is what stops the last
     * frames of the fold showing a squashed row of half-height buttons.
     */
    /**
     * The {@code max-height} the original folds against: {@code #top-bar { max-height: 200px }}.
     *
     * <p>Not a height the bar ever has. It is a ceiling chosen to be comfortably above whatever the
     * content wraps to, and it is the reason the original's fold looks quicker than a 250 ms
     * duration suggests: the bar is about 136 px tall on a phone, so the first third of the
     * transition moves a maximum nobody can see. See {@code TopBar.foldedHeight}.
     */
    public static final double TOP_BAR_MAX_HEIGHT = 200;

    public static final double TOP_BAR_COLLAPSE_MS = 250;
    public static final double TOP_BAR_COLLAPSE_FADE_MS = 200;

    // ------------------------------- what a finger changes, in the original's own order
    //
    // Every number below is one line of the original's `@media (pointer: coarse)` block, in the
    // order that block writes them (InvMgr_V1.3.0.html 119-134 and 364-367). They are gathered here
    // rather than spread through the file so that the block can be read against the CSS line by
    // line, which is the only way to be sure none of it was missed. TouchType is what picks
    // between each pair; nothing else should.

    /** {@code .modal-box { min-width: 300px; padding: 24px }}. Desktop: 260 on 20. */
    public static final double DIALOG_MIN_WIDTH_TOUCH = 300;
    public static final double DIALOG_PADDING_TOUCH = 24;

    /** {@code .modal-box h2 { font-size: 17px }}. Desktop: 14. */
    public static final double FONT_DIALOG_TITLE_TOUCH = 17;

    /**
     * {@code .modal-row label}, {@code input} and {@code textarea}, and {@code .modal-btns button},
     * all four go to 16 px from the desktop's 13.
     *
     * <p>One constant for all four because the original writes one number four times. Splitting
     * them into four would invite them to drift apart, which the original cannot do.
     */
    public static final double FONT_CONTROL_TOUCH = 16;

    /** {@code .modal-row input { padding: 8px 8px }}. Desktop: 4 on 6. */
    public static final double DIALOG_INPUT_PADDING_V_TOUCH = 8;
    public static final double DIALOG_INPUT_PADDING_H_TOUCH = 8;

    /** {@code .modal-row input[type=number] { width: 110px }}. Desktop: 90. */
    public static final double DIALOG_NUMBER_WIDTH_TOUCH = 110;

    /** {@code .modal-row input[type=text] { width: 180px }}. Desktop: 160. */
    public static final double DIALOG_TEXT_WIDTH_TOUCH = 180;

    /** {@code .modal-btns button { padding: 10px 20px }}. Desktop: 5 on 14. */
    public static final double DIALOG_BUTTON_PADDING_V_TOUCH = 10;
    public static final double DIALOG_BUTTON_PADDING_H_TOUCH = 20;

    /** {@code .id-field-label { font-size: 13px }}, and its own input at 16. Desktop: 12 for both. */
    public static final double FONT_ID_LABEL_TOUCH = 13;
    public static final double FONT_ID_INPUT_TOUCH = 16;

    /** {@code .list-entry { font-size: 15px; padding: 9px 10px }}. Desktop: 12 on 5 × 8. */
    public static final double FONT_LIST_ROW_TOUCH = 15;
    public static final double LIST_ROW_PADDING_V_TOUCH = 9;
    public static final double LIST_ROW_PADDING_H_TOUCH = 10;

    /** {@code #list-header { padding: 8px 10px }}. Desktop: 6 on 8. */
    public static final double LIST_HEADER_PADDING_V_TOUCH = 8;
    public static final double LIST_HEADER_PADDING_H_TOUCH = 10;

    /** {@code #list-panel h3 { font-size: 14px }}. Desktop: 12. */
    public static final double FONT_LIST_HEADER_TOUCH = 14;

    /** {@code #list-export-btn { width: 26px; height: 26px; font-size: 15px }}. Desktop: 20 at 13. */
    public static final double LIST_EXPORT_BUTTON_SIZE_TOUCH = 26;
    public static final double FONT_LIST_EXPORT_TOUCH = 15;

    /** {@code #item-search { font-size: 16px; padding: 7px 8px }}. Desktop: 12 on 4 × 6. */
    public static final double FONT_SEARCH_TOUCH = 16;
    public static final double SEARCH_PADDING_V_TOUCH = 7;
    public static final double SEARCH_PADDING_H_TOUCH = 8;

    /** {@code #item-search-clear { font-size: 16px; padding: 5px 10px }}. Desktop: 13 on 2 × 7. */
    public static final double SEARCH_CLEAR_PADDING_V_TOUCH = 5;
    public static final double SEARCH_CLEAR_PADDING_H_TOUCH = 10;

    /** {@code #status-bar { font-size: 13px; padding: 5px 12px }}. Desktop: 11 on 3 × 10. */
    public static final double FONT_STATUS_TOUCH = 13;
    public static final double STATUS_PADDING_V_TOUCH = 5;
    public static final double STATUS_PADDING_H_TOUCH = 12;

    /**
     * {@code .preset-btn { width: 34px; height: 34px; font-size: 12px }} and the green {@code +} at
     * 18. Desktop: 28 square at 11, and 15.
     *
     * <p>Six pixels bigger, which is the difference between a preset you can hit with a thumb and
     * one you hit the preset beside it instead.
     */
    public static final double PRESET_SIZE_TOUCH = 34;
    public static final double FONT_PRESET_TOUCH = 12;
    public static final double FONT_PRESET_ADD_TOUCH = 18;

    // ------------------------------------------------------ the drawer tabs

    /**
     * The little grab handle on the edge of the screen that opens a drawer: 27 × 36.
     *
     * <p>A fixed box, and the original says why in its own comment: it matches what the old
     * content-plus-padding came to exactly, so the icon inside could be enlarged without the button
     * growing, and so the {@code ◄} it shows when open is drawn at the same size as the icon it
     * replaces, instead of at whatever size that character happens to be.
     */
    public static final double DRAWER_TAB_WIDTH = 27;
    public static final double DRAWER_TAB_HEIGHT = 36;
    public static final double DRAWER_TAB_PADDING = 3;
    public static final double DRAWER_TAB_ICON = 20;
    public static final double FONT_DRAWER_TAB = 16;

    /** Rounded on the two corners facing the room; square against the edge it hangs off. */
    public static final double DRAWER_TAB_RADIUS = 4;

    public static final Color DRAWER_TAB_BG = Color.web("#303030");
    public static final Color DRAWER_TAB_TEXT = Color.web("#aaa");

    /**
     * The shadow an open drawer casts over the room: {@code 3px 0 12px rgba(0,0,0,0.6)}.
     *
     * <p>It is what says the drawer is <em>over</em> the room rather than beside it, which is the
     * whole difference between the touch layout and the desktop one. Mirrored for the drawer on the
     * right, so both cast away from their own edge.
     */
    public static final double DRAWER_SHADOW_BLUR = 12;
    public static final double DRAWER_SHADOW_OFFSET = 3;
    public static final Color DRAWER_SHADOW = Color.rgb(0, 0, 0, 0.6);

    /** How long a drawer takes to slide out or away: the original's {@code 0.22s ease}. */
    public static final double DRAWER_SLIDE_MS = 220;

    /** {@code ◄} and {@code ►}: what a tab shows once its drawer is open. */
    public static final String DRAWER_TAB_CLOSE_LEFT = "◄";
    public static final String DRAWER_TAB_CLOSE_RIGHT = "►";

    // -------------------------------------------------- item list geometry

    public static final double LIST_HEADER_PADDING_V = 6;
    public static final double LIST_HEADER_PADDING_H = 8;
    public static final double LIST_ROW_PADDING_V = 5;
    public static final double LIST_ROW_PADDING_H = 8;
    public static final double LIST_ROW_GAP = 6;
    public static final double LIST_SEARCH_GAP = 4;

    /** The colored dot on each row is 10 px across. */
    public static final double LIST_DOT_RADIUS = 5;

    public static final double LIST_EXPORT_BUTTON_SIZE = 20;
    public static final double LIST_EMPTY_PADDING_V = 10;

    // ----------------------------------------------------- dialog geometry

    public static final double DIALOG_PADDING = 20;
    public static final double DIALOG_MIN_WIDTH = 260;

    /** Space between a heading and the rule beneath it. */
    public static final double DIALOG_TITLE_PADDING_BOTTOM = 6;

    /** ...and the Add dialog's title row, which is taller because it holds the presets. */
    public static final double DIALOG_TITLE_ROW_PADDING_BOTTOM = 12;

    public static final double DIALOG_TITLE_MARGIN_BOTTOM = 14;
    public static final double DIALOG_TITLE_GAP = 8;

    public static final double DIALOG_ROW_MARGIN_BOTTOM = 10;
    public static final double DIALOG_ROW_GAP = 10;

    public static final double DIALOG_INPUT_PADDING_V = 4;
    public static final double DIALOG_INPUT_PADDING_H = 6;

    /** A dimension field, borders included. Measured at exactly 90 × 28. */
    public static final double DIALOG_NUMBER_WIDTH = 90;

    /** A dimension field's height, from the browser's own minimum for a number input. */
    public static final double DIALOG_NUMBER_HEIGHT = 28;

    /** A plain text field, such as the preset's name. */
    public static final double DIALOG_TEXT_WIDTH = 160;

    /** The small ID box that lives in the button row. */
    public static final double DIALOG_ID_WIDTH = 70;

    /** {@code .id-field-label input { width: 90px }} on touch. */
    public static final double DIALOG_ID_WIDTH_TOUCH = 90;
    public static final double DIALOG_ID_GAP = 4;

    public static final double DIALOG_BUTTON_GAP = 8;

    /**
     * The gap above the button row.
     *
     * <p>14, not 24: the last field row's 10 px bottom margin and this one's 14 px top margin
     * <em>collapse</em> into a single 14 in CSS, and the reference screenshot confirms 14.
     */
    public static final double DIALOG_BUTTON_ROW_MARGIN_TOP = 14;

    /**
     * A dialog button's height, measured at 30 in the reference.
     *
     * <p>Pinned for the same reason as { #DIALOG_NUMBER_HEIGHT}: a browser button has a
     * minimum height of its own, and JavaFX computes 29 from the font and padding. Two pixels
     * on one button is nothing; two pixels on the button row makes the whole dialog the wrong
     * height, which is visible when the two are put side by side.
     */
    public static final double DIALOG_BUTTON_HEIGHT = 30;

    public static final double DIALOG_BUTTON_PADDING_V = 5;
    public static final double DIALOG_BUTTON_PADDING_H = 14;

    /** Nudges the Name caption down so it lines up with the first line of a growing field. */
    public static final double DIALOG_NAME_LABEL_PADDING_TOP = 5;

    /** How far the ↻ rotate button sits in from the Edit dialog's top-right corner. */
    public static final double DIALOG_SWAP_INSET = 12;

    /**
     * The ↻ rotate button, which is square.
     *
     * <p>Pinned rather than left to the font, for the same reason as {@link #ROOM_FIELD_HEIGHT}
     * and {@link #DIALOG_BUTTON_HEIGHT}, and with one extra wrinkle. A button sized by JavaFX
     * takes its width from the glyph's <em>advance</em>, the space the typeface reserves for the
     * character, not the space the character actually covers. In a math face those advances are
     * cut to fit the widest operators in the font, so a small arrow gets a wide berth: this came
     * out 46 wide against 30 tall, which reads as a mistake in an interface with no other oblong
     * icon buttons. 30 keeps the height it already had and squares it.
     */
    public static final double DIALOG_SWAP_BUTTON_SIZE = 30;

    /** A preset slot is a 28 px square on desktop. */
    public static final double PRESET_SIZE = 28;
    public static final double PRESET_GAP = 6;

    // ------------------------------------------------ tooltip and drag ghost

    public static final double TOOLTIP_PADDING_V = 5;
    public static final double TOOLTIP_PADDING_H = 10;

    /**
     * The same, on a touch screen. The original's
     * {@code @media (pointer: coarse) { .item-tooltip { padding: 8px 12px; } }}.
     */
    public static final double TOOLTIP_PADDING_V_TOUCH = 8;
    public static final double TOOLTIP_PADDING_H_TOUCH = 12;

    /**
     * The gap between the end of a scrolling name and the start of its repeat, in pixels.
     *
     * <p>The original's {@code padding-right: 2.5em} on each copy of the text, resolved against the
     * touch tooltip's own 14 px type. Without a gap the two copies read as one run-on string and
     * the moment the loop restarts is invisible.
     */
    public static final double TOUCH_TOOLTIP_MARQUEE_GAP = 2.5 * FONT_TOOLTIP_TOUCH;

    /** The drag ghost's rounded corner, one of the five permitted exceptions to square. */
    public static final double GHOST_RADIUS = 8;

    // ------------------------------------------------------------------ motion

    /** The dim-above fade, in milliseconds. */
    public static final double DIM_FADE_MS = 150;

    /**
     * How long a list row takes to close up when a planned item is lifted out of it, and to
     * reopen if the drop is canceled.
     *
     * <p>The same 0.22 s the drawers slide in, from the original's stylesheet.
     */
    public static final double ROW_COLLAPSE_MS = 220;

    // ---------------------------------------------------------------- helpers

    /**
     * Parses the {@code hsl(H,S%,L%)} strings the save format stores item colors as.
     *
     * <p>Written by hand because JavaFX cannot read that syntax, and because <b>HSL is not
     * HSB</b>; JavaFX's built-in {@code Color.hsb} takes brightness, which is a different
     * quantity from lightness and would produce visibly wrong colors. This implements the
     * CSS conversion so a box is the same color in both apps.
     *
     * @param css a string of the form {@code hsl(207,55%,42%)}
     * @return the color, or a mid gray if the string is malformed; the loader guarantees the
     *     shape, so a malformed value here means a bug rather than bad user data, and a gray
     *     box is more debuggable than an exception mid-render
     */
    public static Color parseHsl(String css) {
        if (css == null) {
            return Color.GRAY;
        }
        int open = css.indexOf('(');
        int close = css.lastIndexOf(')');
        if (open < 0 || close < open) {
            return Color.GRAY;
        }
        String[] parts = css.substring(open + 1, close).split(",");
        if (parts.length != 3) {
            return Color.GRAY;
        }
        try {
            double h = Double.parseDouble(parts[0].trim());
            double s = Double.parseDouble(parts[1].trim().replace("%", "")) / 100.0;
            double l = Double.parseDouble(parts[2].trim().replace("%", "")) / 100.0;
            return hsl(h, s, l);
        } catch (NumberFormatException e) {
            return Color.GRAY;
        }
    }

    /** HSL to RGB, following the CSS Color specification. */
    public static Color hsl(double hueDegrees, double saturation, double lightness) {
        double h = ((hueDegrees % 360) + 360) % 360;
        double s = clamp01(saturation);
        double l = clamp01(lightness);

        double chroma = (1 - Math.abs(2 * l - 1)) * s;
        double sector = h / 60.0;
        double x = chroma * (1 - Math.abs(sector % 2 - 1));
        double m = l - chroma / 2;

        double r;
        double g;
        double b;
        if (sector < 1) {
            r = chroma; g = x; b = 0;
        } else if (sector < 2) {
            r = x; g = chroma; b = 0;
        } else if (sector < 3) {
            r = 0; g = chroma; b = x;
        } else if (sector < 4) {
            r = 0; g = x; b = chroma;
        } else if (sector < 5) {
            r = x; g = 0; b = chroma;
        } else {
            r = chroma; g = 0; b = x;
        }
        return Color.color(clamp01(r + m), clamp01(g + m), clamp01(b + m));
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** A CSS color string for use in inline JavaFX styles, e.g. {@code #1a1a1a}. */
    public static String hex(Color color) {
        return String.format("#%02x%02x%02x",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }
}
