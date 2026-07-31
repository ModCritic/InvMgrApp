package com.modcritic.invmgr.ui;

/**
 * How large the interface is drawn — the Ctrl+scroll zoom.
 *
 * <p><b>What it does.</b> Ctrl+scroll steps the whole interface up and down through a fixed
 * ladder of sizes, and Ctrl+middle-click puts it back to 100%. It exists because the interface is
 * built from fixed pixel measurements taken off the original app, which are right on the display
 * it was designed for and small on a dense one.
 *
 * <p><b>What it deliberately does NOT do: the room never changes size.</b> The room is drawn to
 * scale — 8 pixels to the inch, 96 to the foot — so a box on screen is a real size, and stretching
 * that would destroy the one property the whole app exists for. Scaling up makes the controls
 * bigger and therefore shows <em>less</em> of the room, exactly as making the window smaller
 * would. See {@code RoomCanvasView.setUiScale} for how the room cancels the zoom back out.
 *
 * <p><b>Why a ladder rather than smooth zooming.</b> Every measurement in {@link Tokens} is a
 * whole number of pixels, and arbitrary factors turn them into fractions, which lands borders and
 * one-pixel lines between physical pixels and makes them blur. Fixed steps keep the common ones
 * landing on whole pixels, and a step per scroll notch is also far easier to land on deliberately
 * than a continuous zoom.
 */
public final class UiScale {

    /**
     * The available sizes, as percentages.
     *
     * <p>Even 25% steps, which is what the user asked for. 50% is the floor because the smallest
     * text in the interface is 8 px and half of that is illegible; 200% is the ceiling because at
     * that point the top bar's controls no longer fit across a 1280 px window.
     */
    public static final int[] STEPS_PERCENT = { 50, 75, 100, 125, 150, 175, 200 };

    /** The size the app starts at, and the one Ctrl+middle-click returns to. */
    public static final int DEFAULT_PERCENT = 100;

    private UiScale() {
    }

    /** The index in {@link #STEPS_PERCENT} of the default size. */
    public static int defaultIndex() {
        return indexOf(DEFAULT_PERCENT);
    }

    /**
     * The step at {@code index}, as a multiplier — 1.0 at 100%.
     *
     * <p>Held inside the ladder rather than wrapping around, so scrolling past either end simply
     * stops instead of jumping from largest to smallest.
     */
    public static double factorAt(int index) {
        return STEPS_PERCENT[clampIndex(index)] / 100.0;
    }

    public static int percentAt(int index) {
        return STEPS_PERCENT[clampIndex(index)];
    }

    /** Keeps an index inside the ladder. */
    public static int clampIndex(int index) {
        return Math.max(0, Math.min(STEPS_PERCENT.length - 1, index));
    }

    private static int indexOf(int percent) {
        for (int i = 0; i < STEPS_PERCENT.length; i++) {
            if (STEPS_PERCENT[i] == percent) {
                return i;
            }
        }
        // Unreachable while DEFAULT_PERCENT is one of the steps, and a loud failure rather than a
        // silent fallback if anyone ever changes one without the other.
        throw new IllegalStateException(percent + "% is not one of the UI scale steps");
    }
}
