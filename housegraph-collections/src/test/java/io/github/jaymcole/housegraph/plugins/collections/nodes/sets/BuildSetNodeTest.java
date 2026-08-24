package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The variable-port node for sets — the deduplicating counterpart to {@code BuildListNode}. What's
 * covered here is everything either side of live-graph wiring: the slots it starts with, the state
 * it persists, and what it does with the values once they're there.
 */
class BuildSetNodeTest {

    @Test
    void startsWithOneSlotToFillAndOneSpare() {
        assertEquals(List.of("Item 1", "Item 2"), Nodes.inputNames(new BuildSetNode()));
    }

    @Test
    void gathersWiredValuesInPortOrder() {
        BuildSetNode node = new BuildSetNode();
        Nodes.set(node, "Item 1", "front door");
        Nodes.set(node, "Item 2", "hallway");

        Nodes.run(node);

        assertEquals(List.of("front door", "hallway"), new java.util.ArrayList<>(Nodes.members(node, "Set")));
    }

    @Test
    void collapsesRepeatsByTextFormKeepingTheFirstOccurrence() {
        BuildSetNode node = new BuildSetNode();
        node.loadState(Map.of("slots", "3"));
        Nodes.set(node, "Item 1", 3);
        Nodes.set(node, "Item 2", "3");
        Nodes.set(node, "Item 3", 4);

        Nodes.run(node);

        assertEquals(List.of(3, 4), new java.util.ArrayList<>(Nodes.members(node, "Set")));
    }

    @Test
    void anUnfilledSlotContributesNothing() {
        BuildSetNode node = new BuildSetNode();
        Nodes.set(node, "Item 2", "hallway");

        Nodes.run(node);

        assertEquals(List.of("hallway"), new java.util.ArrayList<>(Nodes.members(node, "Set")));
    }

    @Test
    void anEmptyNodeBuildsAnEmptySet() {
        BuildSetNode node = new BuildSetNode();

        Nodes.run(node);

        assertEquals(java.util.Set.of(), Nodes.members(node, "Set"));
    }

    @Test
    void writesNoStateWhileItStillHasItsDefaultShape() {
        assertTrue(new BuildSetNode().saveState().isEmpty());
    }

    @Test
    void restoresItsPortsFromSavedState() {
        BuildSetNode node = new BuildSetNode();

        node.loadState(Map.of("slots", "4"));

        assertEquals(List.of("Item 1", "Item 2", "Item 3", "Item 4"), Nodes.inputNames(node));
        assertEquals(Map.of("slots", "4"), node.saveState());
    }

    @Test
    void unreadableOrOutOfRangeStateFallsBackToSomethingUsable() {
        BuildSetNode nonsense = new BuildSetNode();
        nonsense.loadState(Map.of("slots", "not a number"));
        assertEquals(2, Nodes.inputNames(nonsense).size());

        BuildSetNode tiny = new BuildSetNode();
        tiny.loadState(Map.of("slots", "0"));
        assertEquals(2, Nodes.inputNames(tiny).size());

        BuildSetNode huge = new BuildSetNode();
        huge.loadState(Map.of("slots", "5000"));
        assertEquals(64, Nodes.inputNames(huge).size());
    }
}
