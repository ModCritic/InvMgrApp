package com.modcritic.invmgr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one decision {@link Launcher} makes before JavaFX exists: whether to ask Prism for the
 * graphics card even though it has decided against.
 *
 * <p>Written as a plain function taking its answers as arguments, the same shape as
 * {@code Device.isAndroid}, so both positions of the switch can be tested without a second machine
 * and without starting a toolkit that reads the property once and never again.
 */
class LauncherTest {

    @Test
    @DisplayName("a plain desktop launch asks for the graphics card")
    void theDefaultIsToAsk() {
        // The whole point of M6.7's follow-up: a machine where Prism refuses the driver gets a
        // working 3D view without anyone typing a flag. Once this is something you install and
        // double-click there is nowhere to type one.
        assertTrue(Launcher.shouldForceGpu(null, null, false));
    }

    @Test
    @DisplayName("the opt-out is the word false, and only that word")
    void theOptOutIsExact() {
        assertFalse(Launcher.shouldForceGpu("false", null, false));
        assertFalse(Launcher.shouldForceGpu("FALSE", null, false), "any case");
        assertFalse(Launcher.shouldForceGpu("False", null, false), "any case");

        // Anything else leaves the default in place rather than being read as "off". Same rule as
        // Device's platform override: a typo must not quietly change what the program does, and
        // "off" is the position that costs somebody their 3D view.
        assertTrue(Launcher.shouldForceGpu("no", null, false));
        assertTrue(Launcher.shouldForceGpu("0", null, false));
        assertTrue(Launcher.shouldForceGpu("", null, false));
        assertTrue(Launcher.shouldForceGpu("true", null, false));
    }

    @Test
    @DisplayName("an argument on the command line beats the default, either way")
    void prismsOwnPropertyWins() {
        // Somebody passing -Dprism.forceGPU means it. A default that overruled an argument would
        // be a default nobody could get out of, which is the bug this whole opt-out exists to
        // avoid, one level down.
        assertFalse(Launcher.shouldForceGpu(null, "true", false),
                "already true: nothing to do, and setting it again would hide who asked");
        assertFalse(Launcher.shouldForceGpu(null, "false", false),
                "explicitly false: the app must not turn it back on");
    }

    @Test
    @DisplayName("a phone is left alone")
    void androidIsNotForced() {
        // Android reaches 3D through OpenJFX's own backend and has never needed this; M6.5b and
        // M6.5c got the room running on real hardware without it. Bypassing a safety check on a
        // platform that does not need it is risk bought for nothing.
        assertFalse(Launcher.shouldForceGpu(null, null, true));
        assertFalse(Launcher.shouldForceGpu("false", null, true));
    }

    @Test
    @DisplayName("the two property names are the ones that were measured to work")
    void thePropertyNamesAreTheMeasuredOnes() {
        // Pinned as literals rather than read back from the class, because the whole value of this
        // change rests on the exact spelling: prism.forceGPU with that capitalization is what was
        // run on 2026-09-15 and turned `scene3d false` into `scene3d true`. A typo here is silent,
        // since an unknown system property is simply ignored by everyone.
        org.junit.jupiter.api.Assertions.assertEquals("prism.forceGPU", Launcher.PRISM_FORCE_GPU);
        org.junit.jupiter.api.Assertions.assertEquals("invmgr.forcegpu",
                Launcher.FORCE_GPU_OVERRIDE);
    }
}
