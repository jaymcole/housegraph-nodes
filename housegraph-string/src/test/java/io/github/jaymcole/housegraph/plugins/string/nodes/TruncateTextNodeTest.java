package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link TruncateTextNode}. The load-bearing property is that the result never exceeds
 * Max Length — the node exists to keep a send inside somebody else's limit, and handing back
 * something one character too long would defeat it.
 */
class TruncateTextNodeTest {

    @Test
    void declaresItsThreeInputsAndTwoOutputs() {
        TruncateTextNode node = new TruncateTextNode();

        assertEquals(List.of("Text", "Max Length", "Ellipsis"), Ports.inputNames(node));
        assertEquals(List.of("Result", "Truncated"), Ports.outputNames(node));
    }

    @Test
    void textInsideTheLimitIsUntouchedAndReportsThatNothingWasCut() {
        TruncateTextNode node = truncating("all quiet", 20);

        Ports.run(node);

        assertEquals("all quiet", Ports.get(node, "Result"));
        assertFalse(Ports.boolOf(node, "Truncated"));
    }

    @Test
    void longerTextIsCutWithTheEllipsisCountingTowardTheLimit() {
        TruncateTextNode node = truncating("motion detected at the front door", 10);

        Ports.run(node);

        assertEquals("motion ...", Ports.get(node, "Result"));
        assertEquals(10, Ports.textOf(node, "Result").length());
        assertTrue(Ports.boolOf(node, "Truncated"));
    }

    @Test
    void aLimitShorterThanTheEllipsisStillRespectsTheLimit() {
        TruncateTextNode node = truncating("motion detected", 2);

        Ports.run(node);

        assertEquals("..", Ports.get(node, "Result"));
        assertTrue(Ports.boolOf(node, "Truncated"));
    }

    @Test
    void aLimitOfZeroYieldsEmptyText() {
        TruncateTextNode node = truncating("motion detected", 0);

        Ports.run(node);

        assertEquals("", Ports.get(node, "Result"));
        assertTrue(Ports.boolOf(node, "Truncated"));
    }

    @Test
    void itDefaultsToDiscordsPerMessageLimit() {
        TruncateTextNode node = new TruncateTextNode();
        Ports.set(node, "Text", "x".repeat(2500));

        Ports.run(node);

        assertEquals(2000, Ports.textOf(node, "Result").length());
        assertTrue(Ports.boolOf(node, "Truncated"));
    }

    @Test
    void theEllipsisIsTheCallersToChoose() {
        TruncateTextNode node = truncating("motion detected", 8);
        Ports.set(node, "Ellipsis", ">>");

        Ports.run(node);

        assertEquals("motion>>", Ports.get(node, "Result"));
    }

    private static TruncateTextNode truncating(String text, int maxLength) {
        TruncateTextNode node = new TruncateTextNode();
        Ports.set(node, "Text", text);
        Ports.set(node, "Max Length", maxLength);
        return node;
    }
}
