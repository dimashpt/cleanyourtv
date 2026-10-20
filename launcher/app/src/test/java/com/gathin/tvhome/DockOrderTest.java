package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DockOrderTest {
    @Test public void normalizePadsTrimsAndDedupes() {
        List<String> out = DockOrder.normalize(Arrays.asList("a", "b"), 4);
        assertEquals(Arrays.asList("a", "b", "", ""), out);
        out = DockOrder.normalize(Arrays.asList("a", "b", "c", "d", "e"), 3);
        assertEquals(Arrays.asList("a", "b", "c"), out);
        out = DockOrder.normalize(Arrays.asList("a", "a", "b", ""), 4);
        assertEquals(Arrays.asList("a", "", "b", ""), out);
    }

    @Test public void compareRespectsFixedSlotsWithHoles() {
        List<String> dock = Arrays.asList("tv", "", "tube", "");
        assertTrue(DockOrder.compareDock("tv", "tube", dock) < 0);
        assertTrue(DockOrder.compareDock("other", "tv", dock) > 0);
        assertEquals(0, DockOrder.compareDock("x", "y", dock));
    }

    @Test public void applyGridToDockReplacesAndDedupes() {
        List<String> slots = Arrays.asList("a", "b", "c", "d");
        List<String> out = DockOrder.applyGridToDock(slots, 2, "new");
        assertEquals(Arrays.asList("a", "b", "new", "d"), out);
        out = DockOrder.applyGridToDock(out, 0, "b");
        assertEquals(Arrays.asList("b", "", "new", "d"), out);
    }

    @Test public void applyDockMoveSwapsAndClearEmpties() {
        List<String> slots = Arrays.asList("a", "b", "c", "d");
        assertEquals(Arrays.asList("b", "a", "c", "d"), DockOrder.applyDockMove(slots, 0, 1));
        assertEquals(Arrays.asList("b", "c", "a", "d"), DockOrder.applyDockMove(slots, 0, 2));
        assertEquals(Arrays.asList("a", "b", "c", "d"), DockOrder.applyDockMove(slots, 0, 0));
        assertEquals(Arrays.asList("a", "", "c", "d"), DockOrder.applyDockClear(slots, 1));
    }

    @Test public void firstEmptySlotFindsHoleOrFull() {
        Set<String> installed = new HashSet<>(Arrays.asList("a", "b", "c", "d"));
        assertEquals(1, DockOrder.firstEmptySlot(Arrays.asList("a", "", "c", "d"), installed));
        assertEquals(-1, DockOrder.firstEmptySlot(Arrays.asList("a", "b", "c", "d"), installed));
        assertEquals(2, DockOrder.firstEmptySlot(Arrays.asList("a", "b", "missing", "d"), installed));
    }

    @Test public void reconcileGridOrderPreservesOrderAndAppendsNewAtEnd() {
        List<String> saved = Arrays.asList("z", "m", "a", "uninstalled");
        List<String> available = Arrays.asList("a", "b_new", "m", "y_new", "z");
        assertEquals(
                Arrays.asList("z", "m", "a", "b_new", "y_new"),
                DockOrder.reconcileGridOrder(saved, available)
        );
    }

    @Test public void prependToGridOrderPlacesAtFront() {
        List<String> saved = Arrays.asList("b", "c", "d");
        assertEquals(
                Arrays.asList("a", "b", "c", "d"),
                DockOrder.prependToGridOrder(saved, "a")
        );
        assertEquals(
                Arrays.asList("c", "b", "d"),
                DockOrder.prependToGridOrder(saved, "c")
        );
    }
}
