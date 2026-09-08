package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map twin of {@code ClearCollectionNodeTest} in the {@code lists} package. */
class ClearMapNodeTest {

    private static String freshName() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void emptiesACollectionAPutNodeFilled() {
        String name = freshName();
        PutInMapNode putter = new PutInMapNode();
        putter.collect(name, "front", "porch");

        ClearMapNode clearer = new ClearMapNode();
        clearer.discardAll(name);
        Nodes.set(clearer, "Name", name);
        Nodes.run(clearer);

        assertEquals(Map.of(), Nodes.map(clearer, "Map"), "a clear must not leave a stale map downstream");
        assertEquals(0, Nodes.get(clearer, "Count"));

        putter.collect(name, "back", "gate");
        assertEquals(Map.of("back", "gate"), PutInMapNode.snapshotOf(name),
                "the clear must actually reach the collection the putter shares its name with");
    }

    @Test
    void anUnnamedCollectionClearsNothing() {
        ClearMapNode node = new ClearMapNode();

        node.discardAll(null);
        Nodes.run(node);

        assertEquals(Map.of(), Nodes.map(node, "Map"));
    }

    @Test
    void beingPulledForDataClearsNothing() {
        String name = freshName();
        PutInMapNode putter = new PutInMapNode();
        putter.collect(name, "front", "porch");

        ClearMapNode clearer = new ClearMapNode();
        Nodes.set(clearer, "Name", name);

        Nodes.run(clearer);
        Nodes.run(clearer);

        assertEquals(Map.of("front", "porch"), PutInMapNode.snapshotOf(name),
                "resolving this node's outputs must not clear a collection it wasn't flowed into");
    }

    @Test
    void carriesOneFlowInAndOneUnnamedFlowOut() {
        ClearMapNode node = new ClearMapNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(1, flowInputs.size());
        assertEquals("", flowInputs.get(0).name, "a single flow in renders as a bare anchor");

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertTrue(node.getFlowOutputs().get(0).direction == FlowPort.Direction.OUT);
    }

    @Test
    void publishesItsPortsUnderTheNamesTheGraphSavesThemBy() {
        ClearMapNode node = new ClearMapNode();

        assertEquals(List.of("Name"), Nodes.inputNames(node));
        assertEquals(List.of("Map", "Count"), Nodes.outputNames(node));
    }
}
