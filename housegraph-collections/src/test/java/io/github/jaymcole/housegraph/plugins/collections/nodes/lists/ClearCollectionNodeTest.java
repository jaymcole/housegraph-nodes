package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;

import io.github.jaymcole.housegraph.graph.FlowPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every test uses a fresh random name for the same reason {@code AddToCollectionNodeTest} does:
 * the backing collection lives behind {@code NamedCollections}, not behind any one node instance.
 * <p>
 * The Clear routing itself needs the engine, for the same reason documented on
 * {@code AddToCollectionNodeTest} — what's covered here is the emptying the routing routes to, the
 * ports it routes between, and the pull path.
 */
class ClearCollectionNodeTest {

    private static String freshName() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void emptiesACollectionAnAddNodeFilled() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");

        ClearCollectionNode clearer = new ClearCollectionNode();
        clearer.discardAll(name);
        Nodes.set(clearer, "Name", name);
        Nodes.run(clearer);

        assertEquals(List.of(), Nodes.list(clearer, "List"), "a clear must not leave a stale list downstream");
        assertEquals(0, Nodes.get(clearer, "Count"));

        adder.collect(name, "after the clear");
        assertEquals(List.of("after the clear"), AddToCollectionNode.snapshotOf(name),
                "the clear must actually reach the collection the adder shares its name with");
    }

    @Test
    void anUnnamedCollectionClearsNothing() {
        ClearCollectionNode node = new ClearCollectionNode();

        node.discardAll(null);
        Nodes.run(node);

        assertEquals(List.of(), Nodes.list(node, "List"));
    }

    @Test
    void beingPulledForDataClearsNothing() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");

        ClearCollectionNode clearer = new ClearCollectionNode();
        Nodes.set(clearer, "Name", name);

        Nodes.run(clearer);
        Nodes.run(clearer);

        assertEquals(List.of("front"), AddToCollectionNode.snapshotOf(name),
                "resolving this node's outputs must not clear a collection it wasn't flowed into");
    }

    @Test
    void carriesOneFlowInAndOneUnnamedFlowOut() {
        ClearCollectionNode node = new ClearCollectionNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(1, flowInputs.size());
        assertEquals("", flowInputs.get(0).name, "a single flow in renders as a bare anchor");

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertTrue(node.getFlowOutputs().get(0).direction == FlowPort.Direction.OUT);
    }

    @Test
    void publishesItsPortsUnderTheNamesTheGraphSavesThemBy() {
        ClearCollectionNode node = new ClearCollectionNode();

        assertEquals(List.of("Name"), Nodes.inputNames(node));
        assertEquals(List.of("List", "Count"), Nodes.outputNames(node));
    }
}
