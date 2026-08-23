package io.github.jaymcole.housegraph.plugins.collections.nodes;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * One pass, five answers — and the empty-list case, where the difference between "zero" and "no
 * answer" is the whole point: a sum of nothing is zero, an average of nothing is not.
 */
class ListStatisticsNodeTest {

    private static ListStatisticsNode statistics(List<Object> entries) {
        ListStatisticsNode node = new ListStatisticsNode();
        Nodes.set(node, "List", entries);
        Nodes.run(node);
        return node;
    }

    @Test
    void reportsTheUsualFourPlusHowManyItCouldRead() {
        ListStatisticsNode node = statistics(List.of(2, 4, 9));

        assertEquals(15.0, Nodes.get(node, "Sum"));
        assertEquals(2.0, Nodes.get(node, "Minimum"));
        assertEquals(9.0, Nodes.get(node, "Maximum"));
        assertEquals(5.0, Nodes.get(node, "Average"));
        assertEquals(3, Nodes.get(node, "Numeric Count"));
    }

    @Test
    void readsNumbersThatArrivedAsTextAndIgnoresTheRest() {
        ListStatisticsNode node = statistics(Arrays.asList("2.5", "warm", null, 7));

        assertEquals(9.5, Nodes.get(node, "Sum"));
        assertEquals(2, Nodes.get(node, "Numeric Count"), "only the two readable entries counted");
    }

    @Test
    void anEmptyListSumsToZeroButHasNoMinimumMaximumOrAverage() {
        ListStatisticsNode node = statistics(List.of());

        assertEquals(0.0, Nodes.get(node, "Sum"));
        assertNull(Nodes.get(node, "Minimum"));
        assertNull(Nodes.get(node, "Maximum"));
        assertNull(Nodes.get(node, "Average"), "emitting 0 here would put a wrong number on screen");
        assertEquals(0, Nodes.get(node, "Numeric Count"));
    }

    @Test
    void aListWithNothingNumericInItIsTheEmptyCase() {
        ListStatisticsNode node = statistics(List.of("warm", "cold"));

        assertNull(Nodes.get(node, "Average"));
        assertEquals(0, Nodes.get(node, "Numeric Count"),
                "a zero count is how a graph notices the list was never numeric");
    }

    @Test
    void negativeValuesDontConfuseTheMinimum() {
        ListStatisticsNode node = statistics(List.of(-5, -1, -9));

        assertEquals(-9.0, Nodes.get(node, "Minimum"));
        assertEquals(-1.0, Nodes.get(node, "Maximum"));
    }
}
