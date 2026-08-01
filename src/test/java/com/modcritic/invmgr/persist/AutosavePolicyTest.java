package com.modcritic.invmgr.persist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When the autosave decides a write is due.
 *
 * <p>Time is a parameter here, so an hour of use runs in microseconds and every boundary can be
 * checked on both sides — one millisecond early must not write, one millisecond late must.
 */
class AutosavePolicyTest {

    private final AutosavePolicy policy = new AutosavePolicy();

    @Test
    void nothingIsDueBeforeAnythingChanges() {
        assertFalse(policy.isDirty());
        assertFalse(policy.isDue(0));
        assertFalse(policy.isDue(AutosavePolicy.MAX_WAIT_MILLIS * 10),
                "an idle app must never write; it would rewrite the same file forever");
    }

    @Test
    void aChangeWaitsForTheQuietPeriod() {
        policy.noteChange(1_000);

        assertTrue(policy.isDirty());
        assertFalse(policy.isDue(1_000), "due the instant it changed — there was no quiet period");
        assertFalse(policy.isDue(1_000 + AutosavePolicy.QUIET_MILLIS - 1),
                "due one millisecond early");
        assertTrue(policy.isDue(1_000 + AutosavePolicy.QUIET_MILLIS),
                "still not due once the quiet period had passed exactly");
    }

    @Test
    void everyFurtherChangeRestartsTheQuietPeriod() {
        policy.noteChange(1_000);
        policy.noteChange(2_000);
        policy.noteChange(2_500);

        assertFalse(policy.isDue(2_500 + AutosavePolicy.QUIET_MILLIS - 1),
                "the quiet period was measured from an earlier change, not the last one");
        assertTrue(policy.isDue(2_500 + AutosavePolicy.QUIET_MILLIS));
    }

    @Test
    void workThatNeverGoesQuietIsStillWritten() {
        // A long unbroken drag: a change every tick, so the quiet period never once elapses.
        // Without the ceiling this would write nothing at all until the hand stopped moving.
        long start = 10_000;
        long now = start;
        for (; now < start + AutosavePolicy.MAX_WAIT_MILLIS; now += 500) {
            policy.noteChange(now);
            assertFalse(policy.isDue(now),
                    "wrote at " + (now - start) + " ms into a continuous drag, before the ceiling");
        }
        policy.noteChange(now);
        assertTrue(policy.isDue(now),
                "a drag longer than the ceiling never wrote anything");
    }

    @Test
    void theCeilingIsMeasuredFromTheFirstUnsavedChangeNotTheLastWrite() {
        // The trap this rule exists to avoid. The app sits idle for an hour, then the user makes
        // one small edit. Measured from the last write, that edit is already an hour overdue and
        // gets written instantly, so the quiet period does nothing in the most ordinary case of
        // all: one change at a time with thinking in between.
        policy.noteChange(0);
        policy.noteWrite();

        long muchLater = 60 * 60 * 1000;
        policy.noteChange(muchLater);

        assertFalse(policy.isDue(muchLater),
                "wrote immediately after a long idle — the ceiling is being measured from the "
                        + "last write instead of the first unsaved change");
        assertTrue(policy.isDue(muchLater + AutosavePolicy.QUIET_MILLIS));
    }

    @Test
    void writingClearsTheDebt() {
        policy.noteChange(1_000);
        policy.noteWrite();

        assertFalse(policy.isDirty());
        assertFalse(policy.isDue(1_000 + AutosavePolicy.QUIET_MILLIS));
        assertFalse(policy.isDue(1_000 + AutosavePolicy.MAX_WAIT_MILLIS));
    }

    @Test
    void theCeilingStartsAgainAfterAWrite() {
        // A second continuous burst gets its own full allowance rather than inheriting the age
        // of the first one and writing on its very first tick.
        //
        // The burst has to be continuous to see this at all: after a single change the quiet
        // period comes due at 2 s and the ceiling is never reached. Writing this as one change
        // and a long wait tested nothing, and said so by failing.
        policy.noteChange(0);
        policy.noteWrite();

        long start = 5_000;
        long now = start;
        for (; now < start + AutosavePolicy.MAX_WAIT_MILLIS; now += 500) {
            policy.noteChange(now);
            assertFalse(policy.isDue(now),
                    "the second burst was due " + (start + AutosavePolicy.MAX_WAIT_MILLIS - now)
                            + " ms early — the ceiling carried over from before the write");
        }
        policy.noteChange(now);
        assertTrue(policy.isDue(now));
    }

    @Test
    void theTwoDeadlinesAreTheOnesThatWereAskedFor() {
        // Pinned on both sides. The user chose "2 seconds after a change, and a 60 second floor";
        // a policy that wrote every 5 minutes would satisfy every test above and be wrong.
        assertTrue(AutosavePolicy.QUIET_MILLIS == 2_000,
                "the quiet period is " + AutosavePolicy.QUIET_MILLIS + " ms, not the 2 000 asked "
                        + "for");
        assertTrue(AutosavePolicy.MAX_WAIT_MILLIS == 60_000,
                "the ceiling is " + AutosavePolicy.MAX_WAIT_MILLIS + " ms, not the 60 000 asked "
                        + "for");
    }
}
