package io.github.jaymcole.housegraph.plugins.collections.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positional reads, and the "nothing there" shape this library uses instead of a flow branch: a
 * null Item beside a false Found, for the host's If (Boolean) to act on.
 */
class GetItemNodeTest {

    private static final List<Object> CAMERAS = List.of("front", "side", "back");

    @Test
    void readsByZeroBasedIndex() {
        GetItemNode node = new GetItemNode();
        Nodes.set(node, "List", CAMERAS);
        Nodes.set(node, "Index", 1);

        Nodes.run(node);

        assertEquals("side", Nodes.get(node, "Item"));
        assertEquals(true, Nodes.get(node, "Found"));
    }

    @Test
    void aNegativeIndexReadsFromTheEnd() {
        GetItemNode node = new GetItemNode();
        Nodes.set(node, "List", CAMERAS);
        Nodes.set(node, "Index", -1);

        Nodes.run(node);

        assertEquals("back", Nodes.get(node, "Item"), "-1 is the last entry, with no Count node needed");
    }

    @Test
    void anIndexPastTheEndReportsNotFoundRatherThanFailing() {
        GetItemNode node = new GetItemNode();
        Nodes.set(node, "List", CAMERAS);
        Nodes.set(node, "Index", 7);

        Nodes.run(node);

        assertNull(Nodes.get(node, "Item"));
        assertEquals(false, Nodes.get(node, "Found"));
    }

    @Test
    void anEmptyOrUnwiredListIsSimplyNotFound() {
        GetItemNode node = new GetItemNode();

        Nodes.run(node);

        assertNull(Nodes.get(node, "Item"));
        assertEquals(false, Nodes.get(node, "Found"));
    }

    @Test
    void branchesThroughABooleanRatherThanItsOwnFlowPorts() {
        GetItemNode node = new GetItemNode();

        assertTrue(node.getFlowOutputs().isEmpty(),
                "the found/missing decision belongs to the graph's If (Boolean), not to this node");
        assertEquals(List.of("Item", "Found"), Nodes.outputNames(node));
    }
}
