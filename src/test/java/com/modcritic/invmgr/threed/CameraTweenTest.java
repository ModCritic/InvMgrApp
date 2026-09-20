package com.modcritic.invmgr.threed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Moving the camera from one pose to another, and (the part that matters) letting one channel
 * travel along a different curve from the rest.
 *
 * <p>The per-channel override is the failure mode this class exists to prevent, and it is a
 * <em>silent</em> one: a tween that ignores the override still animates, still starts and ends in
 * the right places, and still looks like a working camera move. Only the shape in between is
 * wrong. That is the M3.5 shape of bug: a mechanism that appears to work because everything
 * around it does.
 */
class CameraTweenTest {

    private static final double TOLERANCE = 1e-12;

    private static CameraPose pose(double x, double y, double z, double yaw, double pitch) {
        return new CameraPose(x, y, z, yaw, pitch);
    }

    @Test
    @DisplayName("a tween starts exactly at its start pose and ends exactly at its end pose")
    void theEndsAreExact() {
        CameraTween tween = new CameraTween(pose(1, 2, 3, 0.4, -0.5), pose(9, 8, 7, -1.2, 0.3));

        CameraPose start = tween.at(0);
        assertEquals(1, start.x, TOLERANCE);
        assertEquals(2, start.y, TOLERANCE);
        assertEquals(3, start.z, TOLERANCE);
        assertEquals(0.4, start.yaw, TOLERANCE);
        assertEquals(-0.5, start.pitch, TOLERANCE);

        CameraPose end = tween.at(1);
        assertEquals(9, end.x, TOLERANCE);
        assertEquals(8, end.y, TOLERANCE);
        assertEquals(7, end.z, TOLERANCE);
        assertEquals(-1.2, end.yaw, TOLERANCE);
        assertEquals(0.3, end.pitch, TOLERANCE);
    }

    @Test
    @DisplayName("asking past either end gives the same answer as asking at the end")
    void progressIsClampedAtBothEnds() {
        // A frame can land after the animation was due to finish, because the clock does not tick
        // in whole frames. Without the clamp the curve carries on past 1 and the camera sails
        // past the pose it was meant to arrive at -- and then the render loop takes over from
        // wherever it ended up.
        CameraTween tween = new CameraTween(pose(0, 100, 0, 0, 0), pose(0, 6, 0, 0, 0));

        assertEquals(100, tween.at(-3).y, TOLERANCE, "before the start is the start");
        assertEquals(6, tween.at(1.4).y, TOLERANCE, "after the end is the end");
        assertEquals(6, tween.at(Double.POSITIVE_INFINITY).y, TOLERANCE);
    }

    @Test
    @DisplayName("a channel given its own curve does not follow the default one")
    void thePerChannelOverrideIsActuallyUsed() {
        // Both channels travel from 0 to 1 over the same time, so if the override were ignored
        // they would agree at every instant. They must not.
        CameraTween tween = new CameraTween(
                pose(0, 0, 0, 0, 0),
                pose(0, 1, 0, 0, 1),
                Map.of(CameraTween.Channel.PITCH, Easing.IN_CUBIC));

        for (double p : new double[] {0.2, 0.35, 0.5, 0.65, 0.8}) {
            CameraPose at = tween.at(p);
            assertNotEquals(at.y, at.pitch, 1e-6,
                    "pitch was given IN_CUBIC and height the default, so at " + p
                            + " they cannot be in the same place");
        }

        // And it is the right way round, not merely different: in-cubic lags.
        CameraPose third = tween.at(1.0 / 3);
        assertTrue(third.pitch < third.y,
                "IN_CUBIC starts slower than the default, so pitch must be behind height");

        // The unnamed channels really do get the default.
        assertEquals(Easing.IN_OUT_SINE, tween.curveFor(CameraTween.Channel.X));
        assertEquals(Easing.IN_CUBIC, tween.curveFor(CameraTween.Channel.PITCH));
    }

    @Test
    @DisplayName("two channels can each have their own curve, and they differ from each other")
    void twoOverridesDoNotCollapseIntoOne() {
        CameraTween tween = new CameraTween(
                pose(0, 0, 0, 0, 0),
                pose(1, 1, 1, 1, 1),
                Map.of(CameraTween.Channel.PITCH, Easing.IN_CUBIC,
                        CameraTween.Channel.YAW, Easing.OUT_CUBIC));

        CameraPose at = tween.at(0.4);
        assertTrue(at.pitch < at.x, "pitch eases in, so it lags the default");
        assertTrue(at.yaw > at.x, "yaw eases out, so it leads the default");
        assertTrue(at.pitch < at.yaw, "and the two overrides are not the same curve");
    }

    @Test
    @DisplayName("a tween holds copies, so moving the live camera cannot change where it is going")
    void theEndPosesAreCopied() {
        // The live camera is the pose being animated. If the tween held a reference to it rather
        // than a copy, writing each frame's result into the camera would rewrite the tween's own
        // starting point, and the animation would chase its own tail.
        CameraPose live = pose(0, 100, 0, 0, -Math.PI / 2);
        CameraTween tween = new CameraTween(live, pose(0, 6, 0, 0, 0));

        live.y = 42;
        live.pitch = 1.1;

        assertEquals(100, tween.at(0).y, TOLERANCE, "the tween kept its own copy of the start");
        assertEquals(-Math.PI / 2, tween.at(0).pitch, TOLERANCE);
        assertEquals(100, tween.from().y, TOLERANCE);
    }

    @Test
    @DisplayName("applyTo writes the same pose that at() returns")
    void applyToAgreesWithAt() {
        // applyTo exists only to avoid allocating a pose per frame; if it ever disagreed with at()
        // the tests would all be checking a path the render loop never takes -- the M3.5 mistake.
        CameraTween tween = new CameraTween(
                pose(1, 50, 2, 2.5, -1.4),
                pose(6, 6, 9, -0.5, 0),
                Map.of(CameraTween.Channel.PITCH, Easing.OUT_CUBIC));
        CameraPose camera = new CameraPose();

        for (double p : new double[] {0, 0.13, 0.5, 0.87, 1}) {
            CameraPose expected = tween.at(p);
            tween.applyTo(camera, p);
            assertEquals(expected.x, camera.x, TOLERANCE);
            assertEquals(expected.y, camera.y, TOLERANCE);
            assertEquals(expected.z, camera.z, TOLERANCE);
            assertEquals(expected.yaw, camera.yaw, TOLERANCE);
            assertEquals(expected.pitch, camera.pitch, TOLERANCE);
        }
    }
}
