package com.modcritic.invmgr.ui;

/**
 * How much of the screen belongs to the phone rather than to the app.
 *
 * <p>On Android this program draws <em>edge to edge</em>: all the way into the corners, under the
 * clock and battery at the top and under the navigation buttons at the bottom. That is deliberate
 * and it is what makes the 3D view fill the whole screen with no black bars. It is also why the
 * 2D interface needs to know how much room the phone has taken, or the top bar comes up underneath
 * the clock, which is exactly what the first Android build did.
 *
 * <p>This class is only the four numbers and the arithmetic for working them out. It holds no
 * JavaFX and reads nothing from the system, so it can be tested without a screen. Who applies them
 * and where is {@code App}'s business and {@code View3D}'s.
 *
 * <h2>⚠ The top number is a standard, not a measurement</h2>
 *
 * <p>This is the honest state of it as of M6.1b, and it is worth stating plainly rather than
 * leaving someone to discover it.
 *
 * <p>The layer that puts JavaFX on Android hands over exactly one number about the screen (how
 * many real pixels make up one design pixel) and nothing at all about the system bars. Its own
 * source says, in a comment, that the way to get the bars is a library this project may not use:
 * that library is GPL-licensed without the exemption that would let a permissively licensed app
 * link it, and linking it would force this entire program to change license. See {@code CLAUDE.md}
 * OD-3.
 *
 * <p>So {@link #forAndroid} does the best available thing, and does two different things for the
 * two edges on purpose:
 *
 * <ul>
 *   <li><b>The bottom is measured when it can be.</b> The navigation bar is the one that really
 *       varies (three buttons take about 48, a gesture pill about 16, a television none at all),
 *       so guessing it is the worse bet. JavaFX reports both the screen's full size and its
 *       "visual" size, and on Android the second appears to come from a measurement that already
 *       has the navigation bar taken off. The difference between them is therefore the navigation
 *       bar, when it is a believable size.
 *   <li><b>The top is a constant.</b> Android's status bar has been 24 design pixels since 2014
 *       and almost every phone still uses it, so a constant is very likely right and there is
 *       nothing available to measure it with. A phone with a tall cut-out camera will be wrong
 *       here, and that is a known limitation rather than a surprise.
 * </ul>
 *
 * <p><b>If the measurement is not believable, both edges fall back to the standards</b>, which
 * were verified correct on the user's own phone: 24 and 48 design pixels, appearing in its log as
 * 72 and 144 real pixels at a scale of three.
 */
public final class SystemInsets {

    /** Nothing is reserved: every desktop, and the state before anything is known. */
    public static final SystemInsets NONE = new SystemInsets(0, 0, 0, 0);

    /**
     * Android's standard status bar, in design pixels. Confirmed on the user's phone: its log
     * reported the top inset as 72 real pixels at a scale of three.
     */
    public static final double ANDROID_STATUS_BAR = 24;

    /**
     * Android's standard three-button navigation bar, in design pixels. Also confirmed on the
     * user's phone: 144 real pixels at a scale of three. Only a fallback; {@link #forAndroid}
     * prefers a measurement, because this is the number that varies between phones.
     */
    public static final double ANDROID_NAV_BAR = 48;

    /**
     * Above this, a computed bar height is not a bar height. Android's tallest ordinary navigation
     * bar is 48 design pixels; anything past 120 means the two sizes being subtracted are not the
     * pair this assumes, and the standard is the safer answer. Deliberately generous: the point
     * is to reject nonsense, not to second-guess an unusual phone.
     */
    static final double MAX_PLAUSIBLE_BAR = 120;

    private final double top;
    private final double right;
    private final double bottom;
    private final double left;

    /** All four edges, in design pixels (the same units every size in {@code Tokens} is in). */
    public SystemInsets(double top, double right, double bottom, double left) {
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.left = left;
    }

    /**
     * Works out what to reserve on Android from the two screen sizes JavaFX is willing to report.
     *
     * <p>Both arguments are heights in design pixels: the screen's full height, and the height
     * JavaFX calls "visual". Where the second is smaller by a believable amount, that difference
     * is the navigation bar and is used. Otherwise (including when the two are equal, which is
     * what a platform that never implemented the distinction reports), the standard is used.
     *
     * <p>Sides are always zero. A phone held upright reserves nothing left or right; a phone on
     * its side does, and this does not yet handle that. Recorded rather than hidden.
     */
    public static SystemInsets forAndroid(double screenHeight, double visualHeight) {
        double measured = screenHeight - visualHeight;
        boolean believable = measured > 0 && measured <= MAX_PLAUSIBLE_BAR;
        double navBar = believable ? measured : ANDROID_NAV_BAR;
        return new SystemInsets(ANDROID_STATUS_BAR, 0, navBar, 0);
    }

    /** Reserved at the top (the status bar, with the clock and the battery in it). */
    public double top() {
        return top;
    }

    /** Reserved on the right. Always zero today; see {@link #forAndroid}. */
    public double right() {
        return right;
    }

    /** Reserved at the bottom (the navigation bar, or the gesture pill). */
    public double bottom() {
        return bottom;
    }

    /** Reserved on the left. Always zero today; see {@link #forAndroid}. */
    public double left() {
        return left;
    }

    /** True when nothing at all is reserved, which is every desktop. */
    public boolean areNone() {
        return top == 0 && right == 0 && bottom == 0 && left == 0;
    }

    @Override
    public String toString() {
        return "SystemInsets[top=" + top + ", right=" + right
                + ", bottom=" + bottom + ", left=" + left + "]";
    }
}
