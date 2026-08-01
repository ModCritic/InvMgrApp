package com.modcritic.invmgr.ui;

import javafx.scene.paint.Color;

/**
 * Every colour, size and duration the interface uses, in one place.
 *
 * <p>These are not preferences — they are transcribed from {@code SPEC-DESIGN-SYSTEM.md},
 * which was in turn read out of the original app's stylesheet. The Java app is meant to look
 * like the original, not merely similar to it, and a value invented here is a visual bug.
 *
 * <p>Named after what each value is <em>for</em> rather than what colour it is, so that
 * {@code #1a1a1a} and {@code #181818} — indistinguishable by eye and both in use — cannot be
 * swapped by accident. Two names sometimes hold the same colour; that is on purpose, because
 * the same grey serving two roles can stop serving one of them without the other changing.
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

    /** Number-entry fields in the top bar. Note this is NOT the dialog input colour. */
    public static final Color TOP_BAR_INPUT_BG = Color.web("#333");

    /** The item list panel down the right edge. */
    public static final Color LIST_PANEL_BG = Color.web("#1e1e1e");

    /** The search box in that panel — darker still than the panel around it. */
    public static final Color SEARCH_BG = Color.web("#161616");

    /** A dialog's panel, and the planned-item drag ghost. */
    public static final Color DIALOG_BG = Color.web("#2a2a2a");

    /** Text and number fields inside a dialog. Same value as the body, a different role. */
    public static final Color DIALOG_INPUT_BG = Color.web("#1a1a1a");

    /** The hover tooltip's background — the darkest surface in the app. */
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

    /** Half-foot tick marks — deliberately much dimmer than the whole feet. */
    public static final Color TICK_HALF_TEXT = Color.web("#444");

    /**
     * The {@code 1m 2m 3m} labels overlaid on the tick column in metric mode.
     *
     * <p>The same blue as {@link #SLIDER_ACCENT}, but a separate token because it is a separate
     * job: the original gives it its own {@code .meter-label} rule. Blue rather than grey so the
     * metre labels read as a different scale from the foot dots they sit between.
     */
    public static final Color METER_TICK_TEXT = Color.web("#7ab");

    /** The layer slider's own accent colour. */
    public static final Color SLIDER_ACCENT = Color.web("#7ab");

    /** Button labels and general interface text. */
    public static final Color TEXT_PRIMARY = Color.web("#ccc");

    /** Text typed into an input, and dialog and tooltip text. */
    public static final Color TEXT_INPUT = Color.web("#ddd");

    /** Captions beside a dialog's fields. */
    public static final Color TEXT_DIALOG_LABEL = Color.web("#bbb");

    /** Panel headings — the "Items" above the list. */
    public static final Color TEXT_SECTION_HEADER = Color.web("#aaa");

    /** Small icon buttons at rest: the export arrow and the search clear cross. */
    public static final Color TEXT_QUIET = Color.web("#999");

    /** The {@code ID:} caption in a dialog's button row. */
    public static final Color TEXT_ID_LABEL = Color.web("#888");

    /** The {@code | Presets:} caption in the Add Item dialog's title. */
    public static final Color TEXT_PRESET_LABEL = Color.web("#777");

    /** The status bar's text — deliberately quiet. Also the empty-list message. */
    public static final Color TEXT_STATUS = Color.web("#666");

    /** Borders on buttons and inputs. One pixel, everywhere. */
    public static final Color CONTROL_BORDER = Color.web("#555");

    /** The status bar's top border, and the search row's. */
    public static final Color SEPARATOR = Color.web("#333");

    /**
     * How much of the text colour a greyed-out placeholder keeps.
     *
     * <p>Browsers draw placeholder text at 54% of the field's own text colour, blended into the
     * field's background — which is why the search box's placeholder measures {@code #828282}
     * and the dialogs' measures {@code #838383} despite both fields using {@code #ddd} text.
     * Two different greys for one rule, so the rule is what is stored here rather than the
     * greys.
     */
    private static final double PLACEHOLDER_STRENGTH = 0.54;

    /** The placeholder colour for a field with the given background. */
    public static Color placeholderOver(Color background) {
        return TEXT_INPUT.interpolate(background, 1 - PLACEHOLDER_STRENGTH);
    }

    // -------------------------------------------------- button identities
    //
    // Each button that carries meaning has its own colour, so a glance tells you which modes
    // are on. Keeping them distinct is a stated design principle, not an accident.

    public static final Color BUTTON_BG = Color.web("#383838");
    public static final Color BUTTON_BG_HOVER = Color.web("#484848");
    public static final Color BUTTON_SET_ROOM_BG = Color.web("#3a4a3a");

    /** Undo: a warm brown, distinct from every mode toggle. */
    public static final Color BUTTON_UNDO_BG = Color.web("#4a3a2e");

    /** The Add button's cool blue-grey. */
    public static final Color BUTTON_ADD_BG = Color.web("#2e3e4e");

    /** Fit, Plan and Units share a quieter base than the plain buttons. */
    public static final Color BUTTON_TOGGLE_BG = Color.web("#303030");

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

    /** A <em>planned</em> row under the pointer — quieter, because the row is already faded. */
    public static final Color LIST_ROW_HOVER_PLANNED = Color.web("#252525");

    /** The selected row, and a filled preset slot: the same blue-grey. */
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

    // -------------------------------------------------------------------- type

    /**
     * The interface's typeface, for every word in the app.
     *
     * <p>This used to be the string {@code "monospace"}, copied from the original's stylesheet.
     * That is not a font but a generic <em>alias</em>, which each system answers with a different
     * typeface, so the app looked different on every platform. It now names a real face that
     * ships inside the jar — see {@link Fonts}, and {@code CLAUDE.md} §5.5 <b>D-6</b> for why
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
     * is not a second design choice — it is a patch over a gap in the first font's character set.
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

    /** The square Add button's ■, which is drawn larger than the text beside it. */
    public static final double FONT_ADD_BUTTON = 18;

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
     * <p>Pinned for the same reason as {@link #ROOM_FIELD_HEIGHT}, and found the same way — by
     * measuring the screenshots the tests write against the reference ones. Left to JavaFX it came
     * out 33 × 31, because this is the one button drawn at 18 px instead of 13, so the bundled
     * font's line box is proportionally taller here than anywhere else in the bar.
     *
     * <p><b>Three pixels here moved the whole window.</b> The top bar's height is whatever its
     * tallest child needs, so an oversized Add button pushed the bar from the reference's 42 to
     * 46, and every single thing below it — canvas, item list, layer slider, status bar — down
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
     * JavaFX these came out at 31 with the bundled font — five pixels too tall — and the stepper
     * block, which is centred inside them, slid down with them.
     */
    public static final double ROOM_FIELD_HEIGHT = 26;

    public static final double STATUS_PADDING_V = 3;
    public static final double STATUS_PADDING_H = 10;

    /** How long an action's message shows before the instructions return. */
    public static final double STATUS_REVERT_MS = 3000;

    // -------------------------------------------------- item list geometry

    public static final double LIST_HEADER_PADDING_V = 6;
    public static final double LIST_HEADER_PADDING_H = 8;
    public static final double LIST_ROW_PADDING_V = 5;
    public static final double LIST_ROW_PADDING_H = 8;
    public static final double LIST_ROW_GAP = 6;
    public static final double LIST_SEARCH_GAP = 4;

    /** The coloured dot on each row is 10 px across. */
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
    public static final double DIALOG_ID_GAP = 4;

    public static final double DIALOG_BUTTON_GAP = 8;

    /**
     * The gap above the button row.
     *
     * <p>14, not 24 — the last field row's 10 px bottom margin and this one's 14 px top margin
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
     * takes its width from the glyph's <em>advance</em> — the space the typeface reserves for the
     * character, not the space the character actually covers. In a maths face those advances are
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

    /** The drag ghost's rounded corner — one of the five permitted exceptions to square. */
    public static final double GHOST_RADIUS = 8;

    // ------------------------------------------------------------------ motion

    /** The dim-above fade, in milliseconds. */
    public static final double DIM_FADE_MS = 150;

    /**
     * How long a list row takes to close up when a planned item is lifted out of it, and to
     * reopen if the drop is cancelled.
     *
     * <p>The same 0.22 s the drawers slide in, from the original's stylesheet.
     */
    public static final double ROW_COLLAPSE_MS = 220;

    // ---------------------------------------------------------------- helpers

    /**
     * Parses the {@code hsl(H,S%,L%)} strings the save format stores item colours as.
     *
     * <p>Written by hand because JavaFX cannot read that syntax, and because <b>HSL is not
     * HSB</b> — JavaFX's built-in {@code Color.hsb} takes brightness, which is a different
     * quantity from lightness and would produce visibly wrong colours. This implements the
     * CSS conversion so a box is the same colour in both apps.
     *
     * @param css a string of the form {@code hsl(207,55%,42%)}
     * @return the colour, or a mid grey if the string is malformed — the loader guarantees the
     *     shape, so a malformed value here means a bug rather than bad user data, and a grey
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

    /** A CSS colour string for use in inline JavaFX styles, e.g. {@code #1a1a1a}. */
    public static String hex(Color color) {
        return String.format("#%02x%02x%02x",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }
}
