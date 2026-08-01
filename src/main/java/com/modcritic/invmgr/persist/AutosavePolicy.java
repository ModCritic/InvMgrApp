package com.modcritic.invmgr.persist;

/**
 * When the autosave is due. Arithmetic only — no files, no JavaFX, no clock of its own.
 *
 * <p>Time arrives as a parameter so this can be driven through an hour of simulated use in a
 * fraction of a millisecond, the way {@code TiltPendulum} is. Everything about <em>when</em> the
 * app saves is decided here, and the part that touches the disk decides nothing.
 *
 * <p>Two rules, and both are needed:
 *
 * <ul>
 *   <li><b>Quiet period.</b> Write {@value #QUIET_MILLIS} ms after the last change, so that a
 *       burst of edits — dragging an item, typing a name — becomes one write at the end rather
 *       than one per keystroke.
 *   <li><b>Ceiling.</b> Write anyway once {@value #MAX_WAIT_MILLIS} ms have passed since the
 *       <em>first</em> unsaved change, so that work which never goes quiet is still saved. A
 *       long unbroken drag is exactly that: the quiet period alone would write nothing until
 *       the hand stopped.
 * </ul>
 *
 * <p>The ceiling is measured from the first unsaved change and <b>not</b> from the last write.
 * Measured from the last write, a single edit made after an idle hour would be instantly overdue
 * and would write immediately, defeating the quiet period in precisely the common case — one
 * small change at a time, with thinking in between.
 */
public final class AutosavePolicy {

    /** Silence after a change before it is written. */
    public static final long QUIET_MILLIS = 2_000;

    /** The longest a change may go unwritten, however busy the app is. */
    public static final long MAX_WAIT_MILLIS = 60_000;

    private boolean dirty;
    private long firstChangeAt;
    private long lastChangeAt;

    /** Records that the state differs from what was last written. */
    public void noteChange(long nowMillis) {
        if (!dirty) {
            dirty = true;
            firstChangeAt = nowMillis;
        }
        lastChangeAt = nowMillis;
    }

    /** Whether there is unwritten work at all, due or not. */
    public boolean isDirty() {
        return dirty;
    }

    /** Whether a write is due now. */
    public boolean isDue(long nowMillis) {
        if (!dirty) {
            return false;
        }
        return nowMillis - lastChangeAt >= QUIET_MILLIS
                || nowMillis - firstChangeAt >= MAX_WAIT_MILLIS;
    }

    /**
     * Records that everything up to now has been written.
     *
     * <p>Only call this once the write actually succeeded. On failure the state must stay dirty
     * so the next tick tries again — a save that silently gave up is worse than no autosave,
     * because the app looks like it is protecting work it has stopped protecting.
     */
    public void noteWrite() {
        dirty = false;
    }
}
