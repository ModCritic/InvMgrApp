package com.modcritic.invmgr.persist;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.model.Item;
import org.junit.jupiter.api.Test;

/**
 * What it costs to serialize a full room, because the autosave does it on a timer.
 *
 * <p>Autosave detects a change by serializing the state and comparing the text to what it last
 * wrote, rather than by having every mutation set a dirty flag. The flag would be faster and is
 * the obvious design, but it has to be set in a dozen places and a thirteenth is added every
 * time the app grows a feature — a forgotten one loses the user's work silently, which is the
 * one failure this milestone exists to prevent. Comparing the text cannot be forgotten.
 *
 * <p>The price is running {@link SaveFormat#save} once a second forever, on the FX thread, where
 * anything over a millisecond or two would show as a stutter during a drag. This test measures
 * that price at the format's hard cap of {@value AppState#MAX_ITEMS} items and fails if it ever
 * stops being negligible — at which point the design has to change, not the bound.
 */
class SerializationCostTest {

    /**
     * Generous by design: this is a "the approach is still viable" guard, not a benchmark. A
     * shared container under load is allowed to be several times slower than the real measurement
     * without failing the build, but a change that made serialization *structurally* expensive —
     * a per-item regex, a sort, a stream that copies the list — would blow through it.
     */
    private static final double BUDGET_MS = 5.0;

    @Test
    void serializingAFullRoomIsCheapEnoughToDoOnATimer() {
        AppState state = fullRoom();

        // Warm up: the first calls run interpreted and measure the JIT, not the code.
        for (int i = 0; i < 50; i++) {
            SaveFormat.save(state);
        }

        int runs = 200;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            SaveFormat.save(state);
        }
        double perCallMs = (System.nanoTime() - start) / 1e6 / runs;

        System.out.printf("SaveFormat.save(%d items) = %.3f ms%n", AppState.MAX_ITEMS, perCallMs);
        assertTrue(perCallMs < BUDGET_MS,
                "serializing " + AppState.MAX_ITEMS + " items took " + perCallMs
                        + " ms, over the " + BUDGET_MS + " ms budget — autosave cannot keep "
                        + "polling on the FX thread at this cost");
    }

    /** A worst-case room: the item cap, every name and id at full length. */
    private static AppState fullRoom() {
        AppState state = new AppState();
        for (int i = 0; i < AppState.MAX_ITEMS; i++) {
            Item item = new Item();
            item.id = "i" + (1000 + i) + "_" + (2000 + i);
            item.serial = i + 1;
            item.dragOrder = i + 1;
            item.w_in = 12 + (i % 7);
            item.l_in = 10 + (i % 5);
            item.h_in = 8 + (i % 3);
            item.x_px = i * 3.5;
            item.y_px = i * 2.25;
            item.color = "hsl(" + (i % 360) + ",70%,50%)";
            item.name = "x".repeat(Item.MAX_NAME_LENGTH);
            item.customId = "y".repeat(Item.MAX_CUSTOM_ID_LENGTH);
            item.baseHeight_in = i % 40;
            state.items.add(item);
        }
        state.itemCounter = AppState.MAX_ITEMS;
        state.dragOrderCounter = AppState.MAX_ITEMS;
        return state;
    }
}
