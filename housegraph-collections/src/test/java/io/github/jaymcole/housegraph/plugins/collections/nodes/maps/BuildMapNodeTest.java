package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The variable-port node for maps — the paired-slot counterpart to {@code BuildListNode}. What's
 * covered here is everything either side of live-graph wiring: the slots it starts with, the state
 * it persists, and what it does with the key/value pairs once they're there.
 */
class BuildMapNodeTest {

    @Test
    void startsWithOnePairToFillAndOneSpare() {
        assertEquals(List.of("Key 1", "Value 1", "Key 2", "Value 2"), Nodes.inputNames(new BuildMapNode()));
    }

    @Test
    void gathersTypedKeysAndWiredValuesInSlotOrder() {
        BuildMapNode node = new BuildMapNode();
        Nodes.set(node, "Key 1", "name");
        Nodes.set(node, "Value 1", "Front Door");
        Nodes.set(node, "Key 2", "kind");
        Nodes.set(node, "Value 2", "camera");

        Nodes.run(node);

        assertEquals(Map.of("name", "Front Door", "kind", "camera"), Nodes.map(node, "Map"));
    }

    @Test
    void aSlotWithNoKeyContributesNothing() {
        BuildMapNode node = new BuildMapNode();
        Nodes.set(node, "Value 1", "orphaned");
        Nodes.set(node, "Key 2", "kind");
        Nodes.set(node, "Value 2", "camera");

        Nodes.run(node);

        assertEquals(Map.of("kind", "camera"), Nodes.map(node, "Map"),
                "a value with nothing to name it cannot become an entry");
    }

    @Test
    void aLaterSlotsValueWinsWhenTwoKeysMatch() {
        BuildMapNode node = new BuildMapNode();
        node.loadState(Map.of("slots", "3"));
        Nodes.set(node, "Key 1", "kind");
        Nodes.set(node, "Value 1", "camera");
        Nodes.set(node, "Key 3", "kind");
        Nodes.set(node, "Value 3", "sensor");

        Nodes.run(node);

        assertEquals(Map.of("kind", "sensor"), Nodes.map(node, "Map"));
    }

    @Test
    void anEmptyNodeBuildsAnEmptyMap() {
        BuildMapNode node = new BuildMapNode();

        Nodes.run(node);

        assertEquals(Map.of(), Nodes.map(node, "Map"));
    }

    @Test
    void writesNoStateWhileItStillHasItsDefaultShape() {
        assertTrue(new BuildMapNode().saveState().isEmpty());
    }

    @Test
    void restoresItsPortsFromSavedState() {
        BuildMapNode node = new BuildMapNode();

        node.loadState(Map.of("slots", "3"));

        assertEquals(List.of("Key 1", "Value 1", "Key 2", "Value 2", "Key 3", "Value 3"), Nodes.inputNames(node));
        assertEquals(Map.of("slots", "3"), node.saveState());
    }

    @Test
    void unreadableOrOutOfRangeStateFallsBackToSomethingUsable() {
        BuildMapNode nonsense = new BuildMapNode();
        nonsense.loadState(Map.of("slots", "not a number"));
        assertEquals(4, Nodes.inputNames(nonsense).size());

        BuildMapNode tiny = new BuildMapNode();
        tiny.loadState(Map.of("slots", "0"));
        assertEquals(4, Nodes.inputNames(tiny).size());

        BuildMapNode huge = new BuildMapNode();
        huge.loadState(Map.of("slots", "5000"));
        assertEquals(128, Nodes.inputNames(huge).size());
    }
}
