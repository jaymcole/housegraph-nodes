package io.github.jaymcole.housegraph.plugins.collections.nodes;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The map-by-parameter node: a template per entry, with the position available to it. */
class FormatEachNodeTest {

    private static FormatEachNode format(List<Object> entries, String template) {
        FormatEachNode node = new FormatEachNode();
        Nodes.set(node, "List", entries);
        Nodes.set(node, "Template", template);
        Nodes.run(node);
        return node;
    }

    @Test
    void rewritesEveryEntryThroughTheTemplate() {
        FormatEachNode node = format(List.of("front", "back"), "The {item} camera");

        assertEquals(List.of("The front camera", "The back camera"), Nodes.list(node, "List"));
    }

    @Test
    void offersBothZeroBasedAndOneBasedPositions() {
        FormatEachNode node = format(List.of("a", "b"), "{index}/{number}: {item}");

        assertEquals(List.of("0/1: a", "1/2: b"), Nodes.list(node, "List"));
    }

    @Test
    void resolvesEscapesTheSameWayJoinDoes() {
        FormatEachNode node = format(List.of("a"), "-\\t{item}");

        assertEquals(List.of("-\ta"), Nodes.list(node, "List"));
    }

    @Test
    void leavesUnknownBracesAlone() {
        FormatEachNode node = format(List.of("a"), "{item} {who}");

        assertEquals(List.of("a {who}"), Nodes.list(node, "List"));
    }

    @Test
    void aNullEntryFormatsAsNothingRatherThanTheWordNull() {
        FormatEachNode node = format(Arrays.asList("a", null), "[{item}]");

        assertEquals(List.of("[a]", "[]"), Nodes.list(node, "List"));
    }

    @Test
    void defaultsToPassingTheEntryThroughAsText() {
        FormatEachNode node = new FormatEachNode();
        Nodes.set(node, "List", List.of(1, 2));
        Nodes.run(node);

        assertEquals(List.of("1", "2"), Nodes.list(node, "List"));
    }
}
