package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one question the interface asks about the machine it is running on, in both positions.
 *
 * <p>Everything here drives the two-argument form, which takes both answers as parameters. That is
 * the whole reason it exists: the single-codebase decision (OD-5) is only workable if the switch
 * can be flipped and checked without a phone.
 */
class DeviceTest {

    @Test
    @DisplayName("JavaFX saying android is what makes it a phone")
    void javafxPlatformDecides() {
        assertTrue(Device.isAndroid("android", null));
        assertFalse(Device.isAndroid("linux", null));
        assertFalse(Device.isAndroid("win", null));
        assertFalse(Device.isAndroid("mac", null));
    }

    @Test
    @DisplayName("a missing platform property is not a phone")
    void nothingReportedIsNotAPhone() {
        // Reading the property returns null when JavaFX has not set it, and a null must not throw
        // or be mistaken for a match.
        assertFalse(Device.isAndroid(null, null));
    }

    @Test
    @DisplayName("the override forces the phone layout onto a desktop")
    void overrideForcesAndroid() {
        // The point of the whole class: this is how the touch interface gets run and screenshotted
        // in a container with no touchscreen.
        assertTrue(Device.isAndroid("linux", "android"));
    }

    @Test
    @DisplayName("the override can also force the desktop layout onto a phone")
    void overrideForcesDesktop() {
        // Bound on both sides. An override that could only turn touch ON would leave no way to
        // check that a phone-only change has not broken the desktop path from the phone.
        assertFalse(Device.isAndroid("android", "desktop"));
    }

    @Test
    @DisplayName("a misspelled override is ignored, not treated as desktop")
    void aTypoInTheOverrideChangesNothing() {
        // The dangerous alternative is an override that means "android when it says android and
        // desktop otherwise": then -Dinvmgr.platform=Android7 or =andriod silently turns the touch
        // interface OFF on a real phone, which is far worse than doing nothing.
        assertTrue(Device.isAndroid("android", "andriod"));
        assertTrue(Device.isAndroid("android", ""));
        assertFalse(Device.isAndroid("linux", "phone"));
    }

    @Test
    @DisplayName("case does not matter, in either the platform or the override")
    void caseIsIgnored() {
        assertTrue(Device.isAndroid("Android", null));
        assertTrue(Device.isAndroid("linux", "ANDROID"));
        assertFalse(Device.isAndroid("android", "Desktop"));
    }
}
