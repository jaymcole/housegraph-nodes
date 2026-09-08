package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map twin of {@code AddToCollectionNodeTest} in the {@code lists} package — see that class
 * for why every test uses a fresh random name and why the Put routing itself is out of reach
 * without a live engine.
 */
class PutInMapNodeTest {

    private static String freshName() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void keepsEntriesAcrossSeparateFirings() {
        PutInMapNode node = new PutInMapNode();
        String name = freshName();

        node.collect(name, "front", "porch");
        node.collect(name, "back", "gate");
        Nodes.set(node, "Name", name);
        Nodes.run(node);

        assertEquals(Map.of("front", "porch", "back", "gate"), Nodes.map(node, "Map"));
        assertEquals(2, Nodes.get(node, "Count"));
    }

    @Test
    void puttingTheSameKeyAgainReplacesRatherThanGrowing() {
        PutInMapNode node = new PutInMapNode();
        String name = freshName();

        node.collect(name, "front", "porch");
        node.collect(name, "front", "hallway");
        Nodes.set(node, "Name", name);
        Nodes.run(node);

        assertEquals(Map.of("front", "hallway"), Nodes.map(node, "Map"));
        assertEquals(1, Nodes.get(node, "Count"));
    }

    @Test
    void aHalfFilledPairAddsNothing() {
        PutInMapNode node = new PutInMapNode();
        String name = freshName();

        assertFalse(node.collect(name, null, "orphan value"));
        assertFalse(node.collect(name, "orphan key", null));
        assertFalse(node.collect(name, "  ", "blank key"));
        Nodes.set(node, "Name", name);
        Nodes.run(node);

        assertEquals(Map.of(), Nodes.map(node, "Map"));
    }

    @Test
    void anUnnamedCollectionAddsNothing() {
        PutInMapNode node = new PutInMapNode();

        assertFalse(node.collect(null, "front", "porch"));
        Nodes.run(node);

        assertEquals(Map.of(), Nodes.map(node, "Map"));
    }

    @Test
    void beingPulledForDataAddsNothing() {
        PutInMapNode node = new PutInMapNode();
        String name = freshName();
        node.collect(name, "front", "porch");
        Nodes.set(node, "Name", name);
        Nodes.set(node, "Key", "back");
        Nodes.set(node, "Value", "gate");

        Nodes.run(node);
        Nodes.run(node);
        Nodes.run(node);

        assertEquals(Map.of("front", "porch"), Nodes.map(node, "Map"),
                "a map that grew every time something read it would break on the second reader");
    }

    @Test
    void publishesASnapshotThatLaterEntriesCantReachInto() {
        PutInMapNode node = new PutInMapNode();
        String name = freshName();
        node.collect(name, "front", "porch");
        Nodes.set(node, "Name", name);
        Nodes.run(node);
        Map<String, Object> published = Nodes.map(node, "Map");

        node.collect(name, "back", "gate");

        assertEquals(Map.of("front", "porch"), published,
                "a downstream reader must not see the map change under it");
        assertThrows(UnsupportedOperationException.class, () -> published.put("side", "path"));
    }

    @Test
    void twoNodesSharingANameSeeEachOthersEntries() {
        PutInMapNode first = new PutInMapNode();
        PutInMapNode second = new PutInMapNode();
        String name = freshName();

        first.collect(name, "front", "porch");
        second.collect(name, "back", "gate");
        Nodes.set(first, "Name", name);
        Nodes.run(first);

        assertEquals(Map.of("front", "porch", "back", "gate"), Nodes.map(first, "Map"),
                "the collection is addressed by name, not by node identity");
    }

    @Test
    void carriesOneFlowInAndOneUnnamedFlowOut() {
        PutInMapNode node = new PutInMapNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(1, flowInputs.size());
        assertEquals("", flowInputs.get(0).name, "a single flow in renders as a bare anchor");

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertTrue(node.getFlowOutputs().get(0).direction == FlowPort.Direction.OUT);
    }

    @Test
    void publishesItsPortsUnderTheNamesTheGraphSavesThemBy() {
        PutInMapNode node = new PutInMapNode();

        assertEquals(List.of("Name", "Key", "Value"), Nodes.inputNames(node));
        assertEquals(List.of("Map", "Count"), Nodes.outputNames(node));
    }
}
