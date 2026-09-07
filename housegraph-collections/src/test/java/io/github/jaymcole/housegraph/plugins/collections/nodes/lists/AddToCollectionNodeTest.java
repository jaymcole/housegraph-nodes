package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;

import io.github.jaymcole.housegraph.graph.FlowPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collection lives behind a name shared through {@code NamedCollections}, not behind this
 * node's own fields, so every test uses a fresh random name — the same collection name reused
 * across tests would let one test's leftovers leak into the next, since the backing store outlives
 * any one node instance by design.
 * <p>
 * The Add routing itself needs the engine: only it can build a {@code ProcessContext} carrying
 * whether flow arrived, and its constructor is package-private to the API. So what's covered here
 * is everything reachable without one — the accumulation the routing routes to, the ports it
 * routes between, and the pull path, which is the case a regression would actually be silent in.
 */
class AddToCollectionNodeTest {

    private static String freshName() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void keepsItemsAcrossSeparateFirings() {
        AddToCollectionNode node = new AddToCollectionNode();
        String name = freshName();

        node.collect(name, "front");
        node.collect(name, "back");
        Nodes.set(node, "Name", name);
        Nodes.run(node);

        assertEquals(List.of("front", "back"), Nodes.list(node, "List"));
        assertEquals(2, Nodes.get(node, "Count"));
    }

    @Test
    void anUnwiredItemAddsNothing() {
        AddToCollectionNode node = new AddToCollectionNode();
        String name = freshName();

        node.collect(name, null);
        Nodes.set(node, "Name", name);
        Nodes.run(node);

        assertEquals(List.of(), Nodes.list(node, "List"));
    }

    @Test
    void anUnnamedCollectionAddsNothing() {
        AddToCollectionNode node = new AddToCollectionNode();

        node.collect(null, "front");
        Nodes.run(node);

        assertEquals(List.of(), Nodes.list(node, "List"));
    }

    @Test
    void beingPulledForDataAddsNothing() {
        AddToCollectionNode node = new AddToCollectionNode();
        String name = freshName();
        node.collect(name, "front");
        Nodes.set(node, "Name", name);
        Nodes.set(node, "Item", "hallway");

        Nodes.run(node);
        Nodes.run(node);
        Nodes.run(node);

        assertEquals(List.of("front"), Nodes.list(node, "List"),
                "a list that grew every time something read it would break on the second reader");
    }

    @Test
    void publishesASnapshotThatLaterAdditionsCantReachInto() {
        AddToCollectionNode node = new AddToCollectionNode();
        String name = freshName();
        node.collect(name, "front");
        Nodes.set(node, "Name", name);
        Nodes.run(node);
        List<Object> published = Nodes.list(node, "List");

        node.collect(name, "back");

        assertEquals(List.of("front"), published, "a downstream reader must not see the list change under it");
        assertThrows(UnsupportedOperationException.class, () -> published.add("side"));
    }

    @Test
    void twoNodesSharingANameSeeEachOthersAdditions() {
        AddToCollectionNode first = new AddToCollectionNode();
        AddToCollectionNode second = new AddToCollectionNode();
        String name = freshName();

        first.collect(name, "front");
        second.collect(name, "back");
        Nodes.set(first, "Name", name);
        Nodes.run(first);

        assertEquals(List.of("front", "back"), Nodes.list(first, "List"),
                "the collection is addressed by name, not by node identity");
    }

    @Test
    void differentNamesDoNotInterfere() {
        AddToCollectionNode node = new AddToCollectionNode();
        String nameA = freshName();
        String nameB = freshName();

        node.collect(nameA, "a-item");
        node.collect(nameB, "b-item");
        Nodes.set(node, "Name", nameA);
        Nodes.run(node);

        assertEquals(List.of("a-item"), Nodes.list(node, "List"));
    }

    @Test
    void carriesOneFlowInAndOneUnnamedFlowOut() {
        AddToCollectionNode node = new AddToCollectionNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(1, flowInputs.size());
        assertEquals("", flowInputs.get(0).name, "a single flow in renders as a bare anchor");

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertTrue(node.getFlowOutputs().get(0).direction == FlowPort.Direction.OUT);
    }

    @Test
    void publishesItsPortsUnderTheNamesTheGraphSavesThemBy() {
        AddToCollectionNode node = new AddToCollectionNode();

        assertEquals(List.of("Name", "Item"), Nodes.inputNames(node));
        assertEquals(List.of("List", "Count"), Nodes.outputNames(node));
    }
}
