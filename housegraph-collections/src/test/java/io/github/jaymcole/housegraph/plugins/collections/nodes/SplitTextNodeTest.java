package io.github.jaymcole.housegraph.plugins.collections.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node that stands in for a list literal, since a list value can't survive a save (see the
 * node's own documentation). Its trimming and blank-dropping aren't options, so they're the
 * behaviour most worth pinning down here.
 */
class SplitTextNodeTest {

    @Test
    void splitsOnTheSeparatorTrimmingEachEntry() {
        SplitTextNode node = new SplitTextNode();
        Nodes.set(node, "Text", "kitchen, hallway ,porch");

        Nodes.run(node);

        assertEquals(List.of("kitchen", "hallway", "porch"), Nodes.list(node, "List"));
    }

    @Test
    void dropsBlankEntriesSoATrailingSeparatorCostsNothing() {
        SplitTextNode node = new SplitTextNode();
        Nodes.set(node, "Text", "kitchen, ,porch,");

        Nodes.run(node);

        assertEquals(List.of("kitchen", "porch"), Nodes.list(node, "List"));
    }

    @Test
    void defaultsToACommaSoAPlainListJustWorks() {
        SplitTextNode node = new SplitTextNode();
        Nodes.set(node, "Text", "a,b");

        Nodes.run(node);

        assertEquals(List.of("a", "b"), Nodes.list(node, "List"));
    }

    @Test
    void aSeparatorIsLiteralTextAndNotAPattern() {
        SplitTextNode node = new SplitTextNode();
        Nodes.set(node, "Text", "10.20.30");
        Nodes.set(node, "Separator", ".");

        Nodes.run(node);

        assertEquals(List.of("10", "20", "30"), Nodes.list(node, "List"),
                "an unquoted \".\" as a regex would match every character and yield nothing");
    }

    @Test
    void aMultiCharacterSeparatorWorks() {
        SplitTextNode node = new SplitTextNode();
        Nodes.set(node, "Text", "a -> b -> c");
        Nodes.set(node, "Separator", "->");

        Nodes.run(node);

        assertEquals(List.of("a", "b", "c"), Nodes.list(node, "List"));
    }

    @Test
    void anEmptySeparatorSplitsIntoCharacters() {
        SplitTextNode node = new SplitTextNode();
        Nodes.set(node, "Text", "abc");
        Nodes.set(node, "Separator", "");

        Nodes.run(node);

        assertEquals(List.of("a", "b", "c"), Nodes.list(node, "List"));
    }

    @Test
    void emptyOrUnwiredTextYieldsAnEmptyListRatherThanNull() {
        SplitTextNode empty = new SplitTextNode();
        Nodes.set(empty, "Text", "");
        Nodes.run(empty);
        assertEquals(List.of(), Nodes.list(empty, "List"));

        SplitTextNode unwired = new SplitTextNode();
        Nodes.run(unwired);
        assertEquals(List.of(), Nodes.list(unwired, "List"));
    }

    @Test
    void isAPureDataNodeWithNoFlowPorts() {
        SplitTextNode node = new SplitTextNode();

        assertTrue(node.getFlowInputs().isEmpty(), "nothing to trigger");
        assertTrue(node.getFlowOutputs().isEmpty(), "nothing to report");
        assertEquals(List.of("Text", "Separator"), Nodes.inputNames(node));
        assertEquals(List.of("List"), Nodes.outputNames(node));
    }
}
