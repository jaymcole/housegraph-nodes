package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;

import io.github.jaymcole.housegraph.graph.FlowPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one node here that remembers anything between firings.
 * <p>
 * The Add/Clear routing itself needs the engine: only it can build a {@code ProcessContext}
 * carrying which flow port control arrived through, and its constructor is package-private to the
 * API (the same reason {@code DailyTriggerNodeTest} leaves its Start/Stop cascade to a live
 * graph). So what's covered here is everything reachable without one — the accumulation the
 * routing routes to, the ports it routes between, and the pull path, which is the case a
 * regression would actually be silent in.
 */
class CollectItemsNodeTest {

    @Test
    void keepsItemsAcrossSeparateFirings() {
        CollectItemsNode node = new CollectItemsNode();

        node.collect("front");
        node.collect("back");
        Nodes.run(node);

        assertEquals(List.of("front", "back"), Nodes.list(node, "List"));
        assertEquals(2, Nodes.get(node, "Count"));
    }

    @Test
    void clearingEmptiesItAndRepublishesTheEmptyList() {
        CollectItemsNode node = new CollectItemsNode();
        node.collect("front");
        Nodes.run(node);

        node.discardAll();
        Nodes.run(node);

        assertEquals(List.of(), Nodes.list(node, "List"), "a clear must not leave a stale list downstream");
        assertEquals(0, Nodes.get(node, "Count"));
    }

    @Test
    void beingPulledForDataAddsNothing() {
        CollectItemsNode node = new CollectItemsNode();
        node.collect("front");
        Nodes.set(node, "Item", "hallway");

        Nodes.run(node);
        Nodes.run(node);
        Nodes.run(node);

        assertEquals(List.of("front"), Nodes.list(node, "List"),
                "a list that grew every time something read it would break on the second reader");
    }

    @Test
    void anUnwiredItemAddsNothing() {
        CollectItemsNode node = new CollectItemsNode();

        node.collect(null);
        Nodes.run(node);

        assertEquals(List.of(), Nodes.list(node, "List"));
    }

    @Test
    void publishesASnapshotThatLaterAdditionsCantReachInto() {
        CollectItemsNode node = new CollectItemsNode();
        node.collect("front");
        Nodes.run(node);
        List<Object> published = Nodes.list(node, "List");

        node.collect("back");

        assertEquals(List.of("front"), published, "a downstream reader must not see the list change under it");
        assertThrows(UnsupportedOperationException.class, () -> published.add("side"));
    }

    @Test
    void deletingTheNodeDropsWhatItWasHolding() {
        CollectItemsNode node = new CollectItemsNode();
        node.collect("front");

        node.onRemoved();
        node.onRemoved();

        Nodes.run(node);
        assertEquals(List.of(), Nodes.list(node, "List"), "teardown has to be idempotent");
    }

    @Test
    void carriesTheAddAndClearEntryPointsAndOneUnnamedFlowOut() {
        CollectItemsNode node = new CollectItemsNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(2, flowInputs.size());
        assertEquals("Add", flowInputs.get(0).name);
        assertEquals("Clear", flowInputs.get(1).name);

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertTrue(node.getFlowOutputs().get(0).direction == FlowPort.Direction.OUT);
    }

    @Test
    void publishesItsContentsUnderTheNamesTheGraphSavesThemBy() {
        CollectItemsNode node = new CollectItemsNode();

        assertEquals(List.of("Item"), Nodes.inputNames(node));
        assertEquals(List.of("List", "Count"), Nodes.outputNames(node));
    }
}
