package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map half of <b>Collect Items</b> — one of the two nodes in this library that remembers
 * anything between firings.
 * <p>
 * The Put/Clear routing itself needs the engine: only it can build a {@code ProcessContext}
 * carrying which flow port control arrived through, and its constructor is package-private to the
 * API. So what's covered here is everything reachable without one — the accumulation the routing
 * routes to, the ports it routes between, and the pull path, which is the case a regression would
 * actually be silent in.
 */
class CollectEntriesNodeTest {

    @Test
    void keepsEntriesAcrossSeparateFirings() {
        CollectEntriesNode node = new CollectEntriesNode();

        node.collect("front", "porch");
        node.collect("back", "gate");
        Nodes.run(node);

        assertEquals(Map.of("front", "porch", "back", "gate"), Nodes.map(node, "Map"));
        assertEquals(2, Nodes.get(node, "Count"));
    }

    @Test
    void puttingTheSameKeyAgainReplacesRatherThanGrowing() {
        CollectEntriesNode node = new CollectEntriesNode();

        node.collect("front", "porch");
        node.collect("front", "hallway");
        Nodes.run(node);

        assertEquals(Map.of("front", "hallway"), Nodes.map(node, "Map"));
        assertEquals(1, Nodes.get(node, "Count"));
    }

    @Test
    void clearingEmptiesItAndRepublishesTheEmptyMap() {
        CollectEntriesNode node = new CollectEntriesNode();
        node.collect("front", "porch");
        Nodes.run(node);

        node.discardAll();
        Nodes.run(node);

        assertEquals(Map.of(), Nodes.map(node, "Map"), "a clear must not leave a stale map downstream");
        assertEquals(0, Nodes.get(node, "Count"));
    }

    @Test
    void beingPulledForDataAddsNothing() {
        CollectEntriesNode node = new CollectEntriesNode();
        node.collect("front", "porch");
        Nodes.set(node, "Key", "back");
        Nodes.set(node, "Value", "gate");

        Nodes.run(node);
        Nodes.run(node);
        Nodes.run(node);

        assertEquals(Map.of("front", "porch"), Nodes.map(node, "Map"),
                "a map that grew every time something read it would break on the second reader");
    }

    @Test
    void aHalfFilledPairAddsNothing() {
        CollectEntriesNode node = new CollectEntriesNode();

        assertFalse(node.collect(null, "orphan value"));
        assertFalse(node.collect("orphan key", null));
        assertFalse(node.collect("  ", "blank key"));
        Nodes.run(node);

        assertEquals(Map.of(), Nodes.map(node, "Map"));
    }

    @Test
    void publishesASnapshotThatLaterEntriesCantReachInto() {
        CollectEntriesNode node = new CollectEntriesNode();
        node.collect("front", "porch");
        Nodes.run(node);
        Map<String, Object> published = Nodes.map(node, "Map");

        node.collect("back", "gate");

        assertEquals(Map.of("front", "porch"), published,
                "a downstream reader must not see the map change under it");
        assertThrows(UnsupportedOperationException.class, () -> published.put("side", "path"));
    }

    @Test
    void deletingTheNodeDropsWhatItWasHolding() {
        CollectEntriesNode node = new CollectEntriesNode();
        node.collect("front", "porch");

        node.onRemoved();
        node.onRemoved();

        Nodes.run(node);
        assertEquals(Map.of(), Nodes.map(node, "Map"), "teardown has to be idempotent");
    }

    @Test
    void carriesThePutAndClearEntryPointsAndOneUnnamedFlowOut() {
        CollectEntriesNode node = new CollectEntriesNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(2, flowInputs.size());
        assertEquals("Put", flowInputs.get(0).name);
        assertEquals("Clear", flowInputs.get(1).name);

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertTrue(node.getFlowOutputs().get(0).direction == FlowPort.Direction.OUT);
    }

    @Test
    void publishesItsContentsUnderTheNamesTheGraphSavesThemBy() {
        CollectEntriesNode node = new CollectEntriesNode();

        assertEquals(List.of("Key", "Value"), Nodes.inputNames(node));
        assertEquals(List.of("Map", "Count"), Nodes.outputNames(node));
    }
}
