package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link CompareTextNode}. The comparisons themselves are covered by {@code ModesTest};
 * what matters here is that the node answers with a value rather than a branch, and that Index is
 * set whichever mode was selected.
 */
class CompareTextNodeTest {

    @Test
    void itAnswersWithABooleanAndNeverBranchesItself() {
        CompareTextNode node = new CompareTextNode();

        assertEquals(List.of("Text", "Search", "Comparison"), Ports.inputNames(node));
        assertEquals(List.of("Result", "Index"), Ports.outputNames(node));
        assertTrue(node.getFlowOutputs().isEmpty(), "branching belongs to If Bool, not here");
    }

    @Test
    void itContainsByDefault() {
        CompareTextNode node = comparing("The front door opened", "front", null);

        Ports.run(node);

        assertTrue(Ports.boolOf(node, "Result"));
    }

    @Test
    void itAppliesTheAuthoredComparison() {
        CompareTextNode startsWith = comparing("!deploy now", "!deploy", "starts with");
        Ports.run(startsWith);
        assertTrue(Ports.boolOf(startsWith, "Result"));

        CompareTextNode endsWith = comparing("!deploy now", "!deploy", "ends with");
        Ports.run(endsWith);
        assertFalse(Ports.boolOf(endsWith, "Result"));
    }

    @Test
    void indexIsSetWhicheverModeWasSelectedBecauseItIsTheSameAnswerEitherWay() {
        CompareTextNode node = comparing("the front door", "front", "equals");

        Ports.run(node);

        assertFalse(Ports.boolOf(node, "Result"));
        assertEquals(4, Ports.intOf(node, "Index"));
    }

    @Test
    void textThatIsNotThereReportsAnIndexOfMinusOne() {
        CompareTextNode node = comparing("all quiet", "motion", null);

        Ports.run(node);

        assertFalse(Ports.boolOf(node, "Result"));
        assertEquals(-1, Ports.intOf(node, "Index"));
    }

    @Test
    void anUnrecognisedComparisonFailsTheNode() {
        CompareTextNode node = comparing("all quiet", "motion", "sounds like");

        assertThrows(IllegalArgumentException.class, () -> Ports.run(node));
    }

    private static CompareTextNode comparing(String text, String search, String mode) {
        CompareTextNode node = new CompareTextNode();
        Ports.set(node, "Text", text);
        Ports.set(node, "Search", search);
        if (mode != null) {
            Ports.set(node, "Comparison", mode);
        }
        return node;
    }
}
