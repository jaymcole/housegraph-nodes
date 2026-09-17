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
 * The Remove routing itself needs the engine, for the same reason documented on
 * {@code AddToCollectionNodeTest} — what's covered here is the removal the routing routes to, the
 * ports it routes between, and the pull path.
 */
class RemoveFromCollectionNodeTest {

    private static String freshName() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void removesEveryMatchingCopyFromACollectionAnAddNodeFilled() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");
        adder.collect(name, "middle");
        adder.collect(name, "front");

        RemoveFromCollectionNode remover = new RemoveFromCollectionNode();
        Nodes.set(remover, "Name", name);
        Nodes.set(remover, "Item", "front");
        remover.remove(name, "front");
        Nodes.run(remover);

        assertEquals(List.of("middle"), Nodes.list(remover, "List"));
        assertEquals(1, Nodes.get(remover, "Count"));
    }

    @Test
    void reportsHowManyCopiesWereRemoved() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");
        adder.collect(name, "front");

        RemoveFromCollectionNode remover = new RemoveFromCollectionNode();

        assertEquals(2, remover.remove(name, "front"));
        assertEquals(0, remover.remove(name, "front"), "nothing left to remove the second time");
    }

    @Test
    void matchesForgivinglyAcrossTextAndNumber() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, 3);

        RemoveFromCollectionNode remover = new RemoveFromCollectionNode();

        assertEquals(1, remover.remove(name, "3"), "a text Item must find the number an upstream node added");
    }

    @Test
    void anUnwiredItemRemovesNothing() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");

        RemoveFromCollectionNode remover = new RemoveFromCollectionNode();
        Nodes.set(remover, "Name", name);
        Nodes.run(remover);

        assertEquals(List.of("front"), Nodes.list(remover, "List"));
    }

    @Test
    void anUnnamedCollectionRemovesNothing() {
        RemoveFromCollectionNode remover = new RemoveFromCollectionNode();

        assertEquals(0, remover.remove(null, "front"));
        Nodes.run(remover);

        assertEquals(List.of(), Nodes.list(remover, "List"));
    }

    @Test
    void beingPulledForDataRemovesNothing() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");

        RemoveFromCollectionNode remover = new RemoveFromCollectionNode();
        Nodes.set(remover, "Name", name);
        Nodes.set(remover, "Item", "front");

        Nodes.run(remover);
        Nodes.run(remover);

        assertEquals(List.of("front"), AddToCollectionNode.snapshotOf(name),
                "resolving this node's outputs must not remove from a collection it wasn't flowed into");
        assertEquals(0, Nodes.get(remover, "Removed"));
    }

    @Test
    void publishesASnapshotThatLaterRemovalsCantReachInto() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");
        adder.collect(name, "back");

        RemoveFromCollectionNode remover = new RemoveFromCollectionNode();
        Nodes.set(remover, "Name", name);
        Nodes.set(remover, "Item", "front");
        remover.remove(name, "front");
        Nodes.run(remover);
        List<Object> published = Nodes.list(remover, "List");

        adder.collect(name, "after publish");

        assertEquals(List.of("back"), published, "a downstream reader must not see the list change under it");
    }

    @Test
    void twoNodesSharingANameSeeEachOthersRemovals() {
        String name = freshName();
        AddToCollectionNode adder = new AddToCollectionNode();
        adder.collect(name, "front");
        adder.collect(name, "back");

        RemoveFromCollectionNode first = new RemoveFromCollectionNode();
        RemoveFromCollectionNode second = new RemoveFromCollectionNode();

        first.remove(name, "front");
        second.remove(name, "back");

        assertEquals(List.of(), AddToCollectionNode.snapshotOf(name),
                "the collection is addressed by name, not by node identity");
    }

    @Test
    void carriesOneFlowInAndOneUnnamedFlowOut() {
        RemoveFromCollectionNode node = new RemoveFromCollectionNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(1, flowInputs.size());
        assertEquals("", flowInputs.get(0).name, "a single flow in renders as a bare anchor");

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertTrue(node.getFlowOutputs().get(0).direction == FlowPort.Direction.OUT);
    }

    @Test
    void publishesItsPortsUnderTheNamesTheGraphSavesThemBy() {
        RemoveFromCollectionNode node = new RemoveFromCollectionNode();

        assertEquals(List.of("Name", "Item"), Nodes.inputNames(node));
        assertEquals(List.of("List", "Count", "Removed"), Nodes.outputNames(node));
    }
}
