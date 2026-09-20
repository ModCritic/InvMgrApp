package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.scene.layout.Region;

/**
 * Type and spacing that scale with the width of the box they sit in.
 *
 * <p>The original writes this as CSS {@code clamp(12px, 4.5cqi, 16px)}: a floor, a percentage of
 * the containing element's own width, and a ceiling. It is used for the touch island's six buttons
 * and for the top bar's W/L/H row, and in both places it does real work: a phone can be
 * 320 px wide or 412, and a row of buttons sized for one wraps or overflows on the other.
 *
 * <h2>Why this is a class and not two lines inside the island</h2>
 *
 * <p><b>JavaFX has no container queries.</b> There is no equivalent of {@code cqi} and no
 * equivalent of {@code clamp}; the only way to get this behavior is to listen to a node's width
 * and re-set the fonts by hand. That listener is unavoidable, but the <em>arithmetic</em> it runs
 * need not be trapped inside it: pulled out here it can be checked at a hundred widths in a
 * millisecond, with no window, which is the difference between the ratios being tested and being
 * hoped for.
 *
 * <p>The ratios are the design. Add is always larger than Undo, which is always larger than Fit,
 * Plan and Units, at every width, not just at the one a screenshot happened to be taken at.
 */
public final class Fluid {

    private Fluid() {
    }

    /**
     * One fluid measurement: a percentage of the container, held between a floor and a ceiling.
     *
     * <p>Exactly CSS's {@code clamp(floor, percent * width, ceiling)}, including the order it
     * resolves in: the ceiling applies first and the floor second, so a floor above the ceiling
     * wins. That ordering is CSS's own and is kept rather than "fixed", because the floor is the
     * smallest still-legible size and legibility is the thing that must not be given up.
     *
     * <p>A container width of zero, which is what a node reports before it has ever been laid out,
     * yields the floor rather than nothing at all. That matters: the island seeds itself once at
     * construction, before it has a width, and must come out legible rather than invisible.
     *
     * @param containerWidth the width the percentage is of, in pixels
     * @param floorPx        the smallest this may ever be
     * @param percent        percentage of {@code containerWidth}, so 4.5 means 4.5%
     * @param ceilingPx      the largest this may ever be, normally the desktop design size
     */
    public static double size(double containerWidth, double floorPx, double percent,
            double ceilingPx) {
        double wanted = containerWidth * percent / 100;
        return Math.max(floorPx, Math.min(wanted, ceilingPx));
    }

    /**
     * The width a {@code cqi} is one percent of: the container's <b>content box</b>, not its
     * border box.
     *
     * <p><b>This is the whole of the island's sizing bug, found on the phone and reported as
     * "the buttons are elongated upward".</b> {@code Region.getWidth()} is the border box, and
     * feeding that to {@link #size} made every island font 5.9% too large on a 360 px screen,
     * because the island carries 10 px of padding on each side. Every button came out three to
     * four pixels too wide, which is small enough to read as a rounding quibble and large enough
     * to flip the Add button's proportions from wider-than-tall to taller-than-wide.
     *
     * <p><b>Measured, not reasoned.</b> Against the original's own screenshots, at a 360 px screen,
     * subtracting the padding predicts Undo 57.1, Fit 38.7, Plan 46.1 and Units 53.4 where the
     * pixels say 57.0, 39.0, 46.0 and 53.3. The border box predicts none of them. Four independent
     * agreements is what settled it; the CSS specification prose did not.
     *
     * @param container the node the percentage is of, the island or the top bar
     */
    public static double queryWidth(Region container) {
        Insets padding = container.getPadding();
        return Math.max(0, container.getWidth() - padding.getLeft() - padding.getRight());
    }
}
