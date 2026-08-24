package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Take, drop, first N and last N — all of which are this one node's Start and Count. */
class SliceListNodeTest {

    private static final List<Object> ENTRIES = List.of("a", "b", "c", "d", "e");

    private static SliceListNode slice(int start, int count) {
        SliceListNode node = new SliceListNode();
        Nodes.set(node, "List", ENTRIES);
        Nodes.set(node, "Start", start);
        Nodes.set(node, "Count", count);
        Nodes.run(node);
        return node;
    }

    @Test
    void takesCountEntriesFromStart() {
        assertEquals(List.of("b", "c"), Nodes.list(slice(1, 2), "List"));
    }

    @Test
    void aCountOfZeroMeansEverythingFromStartOnwards() {
        assertEquals(List.of("c", "d", "e"), Nodes.list(slice(2, 0), "List"),
                "an unfilled Count should be useful, not empty");
    }

    @Test
    void aNegativeStartTakesFromTheEnd() {
        assertEquals(List.of("d", "e"), Nodes.list(slice(-2, 0), "List"));
    }

    @Test
    void aNegativeStartPastTheBeginningStartsAtTheBeginning() {
        assertEquals(ENTRIES, Nodes.list(slice(-99, 0), "List"));
    }

    @Test
    void askingForMoreThanThereIsYieldsWhatThereIs() {
        assertEquals(List.of("d", "e"), Nodes.list(slice(3, 99), "List"));
    }

    @Test
    void aStartPastTheEndYieldsAnEmptyListRatherThanFailing() {
        assertEquals(List.of(), Nodes.list(slice(99, 2), "List"));
    }

    @Test
    void anEmptyListSlicesToAnEmptyList() {
        SliceListNode node = new SliceListNode();
        Nodes.set(node, "List", List.of());
        Nodes.run(node);

        assertEquals(List.of(), Nodes.list(node, "List"));
    }
}
