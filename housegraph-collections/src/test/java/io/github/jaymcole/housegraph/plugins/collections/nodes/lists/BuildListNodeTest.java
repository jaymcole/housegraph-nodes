package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The variable-port node. Growing itself needs a live graph to wire edges into, so what's covered
 * here is everything either side of that: the slot count it starts at, the state it persists so a
 * loaded graph gets its ports back, and what it does with the values once they're there.
 */
class BuildListNodeTest {

    @Test
    void startsWithOneSlotToFillAndOneSpare() {
        assertEquals(List.of("Item 1", "Item 2"), Nodes.inputNames(new BuildListNode()));
    }

    @Test
    void gathersWiredValuesInPortOrder() {
        BuildListNode node = new BuildListNode();
        Nodes.set(node, "Item 1", "front door");
        Nodes.set(node, "Item 2", "hallway");

        Nodes.run(node);

        assertEquals(List.of("front door", "hallway"), Nodes.list(node, "List"));
    }

    @Test
    void anUnfilledSlotContributesNothing() {
        BuildListNode node = new BuildListNode();
        Nodes.set(node, "Item 2", "hallway");

        Nodes.run(node);

        assertEquals(List.of("hallway"), Nodes.list(node, "List"),
                "the trailing spare slot must never put a null in the list");
    }

    @Test
    void anEmptyNodeBuildsAnEmptyList() {
        BuildListNode node = new BuildListNode();

        Nodes.run(node);

        assertEquals(List.of(), Nodes.list(node, "List"));
    }

    @Test
    void writesNoStateWhileItStillHasItsDefaultShape() {
        assertTrue(new BuildListNode().saveState().isEmpty(),
                "a node at its default size has nothing worth persisting");
    }

    @Test
    void restoresItsPortsFromSavedState() {
        BuildListNode node = new BuildListNode();

        node.loadState(Map.of("slots", "4"));

        assertEquals(List.of("Item 1", "Item 2", "Item 3", "Item 4"), Nodes.inputNames(node));
        assertEquals(Map.of("slots", "4"), node.saveState(), "what it loaded is what it saves again");
    }

    @Test
    void unreadableOrOutOfRangeStateFallsBackToSomethingUsable() {
        BuildListNode nonsense = new BuildListNode();
        nonsense.loadState(Map.of("slots", "not a number"));
        assertEquals(2, Nodes.inputNames(nonsense).size());

        BuildListNode tiny = new BuildListNode();
        tiny.loadState(Map.of("slots", "0"));
        assertEquals(2, Nodes.inputNames(tiny).size(), "a node with no ports could never be re-wired");

        BuildListNode huge = new BuildListNode();
        huge.loadState(Map.of("slots", "5000"));
        assertEquals(64, Nodes.inputNames(huge).size());
    }

    @Test
    void isAPureDataNodeWithNoFlowPorts() {
        BuildListNode node = new BuildListNode();

        assertTrue(node.getFlowInputs().isEmpty());
        assertTrue(node.getFlowOutputs().isEmpty());
        assertEquals(List.of("List"), Nodes.outputNames(node));
    }
}
