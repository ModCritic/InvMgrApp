package com.modcritic.invmgr.ui;

/**
 * Which kind of machine is this running on: a desktop with a mouse, or a phone with fingers?
 *
 * <p>The app is <b>one interface with a switch in it</b>, not two interfaces (decision OD-5,
 * 2026-08-06). That mirrors the original web version, which is a single file that asks the browser
 * one question and lays itself out accordingly. Two separate interfaces would mean every future
 * fix has two homes and the bug where only one of them got it.
 *
 * <p>This class is that question. It is deliberately tiny, and everything that decides is a plain
 * function taking the answers as arguments, so the switch can be tested in both positions without
 * a phone.
 *
 * <h2>The override, and why it earns its keep</h2>
 *
 * <p>Setting {@code -Dinvmgr.platform=android} forces the phone answer on a desktop. That is not a
 * convenience; it is the reason the single-interface decision was worth making. This container has
 * no phone and no touchscreen, so without it the touch layout could not be run, screenshotted or
 * compared against the reference images at all until an Android build existed, and every mistake in
 * it would cost a ten-minute build and a sideload to find.
 *
 * <p>It is for development, not for people using the app: no menu offers it and nothing writes it
 * to a settings file. And it changes only what the interface <em>looks</em> like. A forced desktop
 * cannot test a <em>gesture</em>, because a mouse has one finger and never two, which is why the
 * phone came first in M6.1 and why gestures are checked on real glass.
 */
public final class Device {

    /**
     * Set this system property to {@code android} to force the phone layout anywhere, or to
     * {@code desktop} to force the mouse layout. Anything else, including absent, means "work it
     * out".
     */
    public static final String OVERRIDE_PROPERTY = "invmgr.platform";

    /**
     * What JavaFX itself calls the platform it is running on. Reads {@code android} on a phone
     * (confirmed in the OD-1 spike's own on-device readout) and something else everywhere we care
     * about.
     */
    static final String PLATFORM_PROPERTY = "javafx.platform";

    private Device() {
    }

    /** Is this a phone? Reads the live system properties; see {@link #isAndroid(String, String)}. */
    public static boolean isAndroid() {
        return isAndroid(System.getProperty(PLATFORM_PROPERTY), System.getProperty(OVERRIDE_PROPERTY));
    }

    /**
     * The decision itself, with both answers handed in so it can be tested from a desktop.
     *
     * <p>The override wins when it is one of the two words it understands. An override of anything
     * else is ignored rather than treated as "desktop", so a typo cannot silently switch the
     * interface; it just leaves the real answer in place.
     *
     * @param javafxPlatform what JavaFX reports, or null
     * @param override       the forced answer, or null
     */
    static boolean isAndroid(String javafxPlatform, String override) {
        if ("android".equalsIgnoreCase(override)) {
            return true;
        }
        if ("desktop".equalsIgnoreCase(override)) {
            return false;
        }
        return "android".equalsIgnoreCase(javafxPlatform);
    }

    /**
     * Should the interface be laid out for fingers?
     *
     * <p>Today this is exactly {@link #isAndroid()}, and it is a separate method on purpose,
     * because the two questions are not the same one and will come apart. A Windows tablet is touch
     * and not Android; the file-picker problem is Android and not touch. Callers should ask the
     * question they actually mean, so that when the two separate nothing has to be untangled.
     */
    public static boolean isTouch() {
        return isAndroid();
    }
}
