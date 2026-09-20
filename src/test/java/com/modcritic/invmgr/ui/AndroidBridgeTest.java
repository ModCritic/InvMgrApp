package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic that turns what Android reports into what the app lays out with.
 *
 * <p>Only the conversion is testable here: the numbers themselves come from a phone, and the
 * methods that fetch them reach native code that exists only in the Android build. Which is
 * exactly why the conversion was pulled out; it is the half that can be wrong on any machine.
 */
class AndroidBridgeTest {

    /** The user's phone: a scale of three, a 24 dip status bar, a 48 dip navigation bar. */
    private static final int[] PHONE_PORTRAIT = { 72, 0, 144, 0, 0 };

    @Test
    @DisplayName("real pixels become design pixels at the screen's own scale")
    void convertsAtDensity() {
        SystemInsets insets = AndroidBridge.toInsets(PHONE_PORTRAIT, 3);
        assertEquals(24, insets.top(), 1e-9);
        assertEquals(48, insets.bottom(), 1e-9);
        assertEquals(0, insets.left(), 1e-9);
        assertEquals(0, insets.right(), 1e-9);
    }

    @Test
    @DisplayName("a side inset survives, which is the landscape case the constants got wrong")
    void keepsSideInsets() {
        // Turned on its side the navigation bar moves to an edge. SystemInsets.forAndroid
        // returns zero for both sides whatever the phone is doing, and the room drew under it.
        SystemInsets insets = AndroidBridge.toInsets(new int[] { 0, 144, 0, 0, 0 }, 3);
        assertEquals(48, insets.right(), 1e-9);
        assertEquals(0, insets.bottom(), 1e-9);
    }

    @Test
    @DisplayName("the keyboard is reported separately from the bars")
    void readsKeyboardHeight() {
        assertEquals(380, AndroidBridge.toKeyboardHeight(new int[] { 72, 0, 144, 0, 1140 }, 3), 1e-9);
        assertEquals(0, AndroidBridge.toKeyboardHeight(PHONE_PORTRAIT, 3), 1e-9);
    }

    @Test
    @DisplayName("nothing to convert is null rather than zeros")
    void nothingToConvert() {
        // The difference matters: zeros would be applied as "the phone takes no space", and
        // the top bar would come up underneath the clock. Null makes the caller fall back.
        assertNull(AndroidBridge.toInsets(null, 3));
        assertNull(AndroidBridge.toInsets(new int[] { 1, 2, 3 }, 3));
        assertEquals(0, AndroidBridge.toKeyboardHeight(null, 3), 1e-9);
    }

    @Test
    @DisplayName("an impossible density is refused rather than dividing by zero")
    void refusesBadDensity() {
        assertNull(AndroidBridge.toInsets(PHONE_PORTRAIT, 0));
        assertNull(AndroidBridge.toInsets(PHONE_PORTRAIT, -1));
    }

    @Test
    @DisplayName("the bridge is unavailable on a desktop forced into the phone layout")
    void overrideDoesNotClaimAndroid() {
        // -Dinvmgr.platform=android is how the touch layout is run and screenshotted here.
        // If isAvailable() honored it, every one of these calls would throw
        // UnsatisfiedLinkError and break the workflow the override exists for.
        String saved = System.getProperty(Device.OVERRIDE_PROPERTY);
        try {
            System.setProperty(Device.OVERRIDE_PROPERTY, "android");
            org.junit.jupiter.api.Assertions.assertTrue(Device.isAndroid(),
                    "the override should still drive the layout");
            org.junit.jupiter.api.Assertions.assertFalse(AndroidBridge.isAvailable(),
                    "but it must not claim there is an Android host to call into");
        } finally {
            if (saved == null) {
                System.clearProperty(Device.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(Device.OVERRIDE_PROPERTY, saved);
            }
        }
    }
}
