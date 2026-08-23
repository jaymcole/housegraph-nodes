package io.github.jaymcole.housegraph.plugins.collections.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "do this N times" source, including the two cases it deliberately treats differently. */
class NumberRangeNodeTest {

    @Test
    void countsFromStartInStepsOfStep() {
        NumberRangeNode node = new NumberRangeNode();
        Nodes.set(node, "Start", 5);
        Nodes.set(node, "Count", 4);
        Nodes.set(node, "Step", 10);

        Nodes.run(node);

        assertEquals(List.of(5, 15, 25, 35), Nodes.list(node, "List"));
    }

    @Test
    void countsDownOnANegativeStep() {
        NumberRangeNode node = new NumberRangeNode();
        Nodes.set(node, "Start", 3);
        Nodes.set(node, "Count", 3);
        Nodes.set(node, "Step", -1);

        Nodes.run(node);

        assertEquals(List.of(3, 2, 1), Nodes.list(node, "List"));
    }

    @Test
    void aCountOfZeroOrLessIsAnEmptyListAndNotAFailure() {
        NumberRangeNode zero = new NumberRangeNode();
        Nodes.set(zero, "Count", 0);
        Nodes.run(zero);
        assertEquals(List.of(), Nodes.list(zero, "List"));

        NumberRangeNode negative = new NumberRangeNode();
        Nodes.set(negative, "Count", -5);
        Nodes.run(negative);
        assertEquals(List.of(), Nodes.list(negative, "List"),
                "a count computed from live data reaching zero is ordinary, not a fault");
    }

    @Test
    void aZeroStepIsRejectedRatherThanRepeatingOneNumber() {
        NumberRangeNode node = new NumberRangeNode();
        Nodes.set(node, "Count", 3);
        Nodes.set(node, "Step", 0);

        assertThrows(IllegalArgumentException.class, () -> Nodes.run(node));
    }

    @Test
    void anAbsurdCountIsRefusedBeforeAnythingIsAllocated() {
        NumberRangeNode node = new NumberRangeNode();
        Nodes.set(node, "Count", 5_000_000);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> Nodes.run(node));
        assertTrue(thrown.getMessage().contains("100000"), "the message should name the limit");
    }

    @Test
    void defaultsToTenEntriesFromZero() {
        NumberRangeNode node = new NumberRangeNode();

        Nodes.run(node);

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Nodes.list(node, "List"));
    }
}
