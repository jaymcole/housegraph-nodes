package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link RegexMatchNode}: searching, capture groups, and a pattern that will not compile. */
class RegexMatchNodeTest {

    @Test
    void declaresTwoInputsAndThreeOutputs() {
        RegexMatchNode node = new RegexMatchNode();

        assertEquals(List.of("Text", "Pattern"), Ports.inputNames(node));
        assertEquals(List.of("Matched", "Match", "Groups"), Ports.outputNames(node));
    }

    @Test
    void itSearchesRatherThanRequiringTheWholeTextToMatch() {
        RegexMatchNode node = matching("now at 21 degrees", "\\d+");

        Ports.run(node);

        assertTrue(Ports.boolOf(node, "Matched"));
        assertEquals("21", Ports.get(node, "Match"));
    }

    @Test
    void anAnchoredPatternIsHowYouAskForTheWholeText() {
        RegexMatchNode node = matching("now at 21 degrees", "^\\d+$");

        Ports.run(node);

        assertFalse(Ports.boolOf(node, "Matched"));
    }

    @Test
    void groupsHoldTheCapturesOfTheFirstMatchWithoutRepeatingTheWholeOne() {
        RegexMatchNode node = matching("deploy housegraph-nodes to prod", "deploy (\\S+) to (\\S+)");

        Ports.run(node);

        assertEquals("deploy housegraph-nodes to prod", Ports.get(node, "Match"));
        assertEquals(List.of("housegraph-nodes", "prod"), Ports.listOf(node, "Groups"));
    }

    @Test
    void aGroupThatTookPartInNoMatchReadsAsEmptyRatherThanNull() {
        RegexMatchNode node = matching("v2", "v(\\d+)(?:\\.(\\d+))?");

        Ports.run(node);

        assertEquals(List.of("2", ""), Ports.listOf(node, "Groups"));
    }

    @Test
    void nothingMatchedLeavesEmptyOutputsRatherThanNulls() {
        RegexMatchNode node = matching("all quiet", "\\d+");

        Ports.run(node);

        assertFalse(Ports.boolOf(node, "Matched"));
        assertEquals("", Ports.get(node, "Match"));
        assertEquals(List.of(), Ports.listOf(node, "Groups"));
    }

    @Test
    void caseInsensitivityIsAskedForInThePatternWhereItStaysVisible() {
        RegexMatchNode node = matching("Front Door", "(?i)front");

        Ports.run(node);

        assertTrue(Ports.boolOf(node, "Matched"));
    }

    @Test
    void aPatternThatWillNotCompileFailsTheNodeInsteadOfNeverMatching() {
        RegexMatchNode node = matching("anything", "(unclosed");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Ports.run(node));
        assertTrue(failure.getMessage().contains("(unclosed"));
    }

    @Test
    void anEmptyPatternFailsTheNodeRatherThanMatchingEverything() {
        RegexMatchNode node = matching("anything", "");

        assertThrows(IllegalArgumentException.class, () -> Ports.run(node));
    }

    private static RegexMatchNode matching(String text, String pattern) {
        RegexMatchNode node = new RegexMatchNode();
        Ports.set(node, "Text", text);
        Ports.set(node, "Pattern", pattern);
        return node;
    }
}
