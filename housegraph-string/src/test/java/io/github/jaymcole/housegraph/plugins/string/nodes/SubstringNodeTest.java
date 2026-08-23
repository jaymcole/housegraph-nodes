package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link SubstringNode}, and in particular the promise that it never throws — a node
 * that failed the one night a message arrived shorter than usual would be worse than useless.
 */
class SubstringNodeTest {

    @Test
    void declaresItsThreeInputsAndAResultOutput() {
        SubstringNode node = new SubstringNode();

        assertEquals(List.of("Text", "Start", "End"), Ports.inputNames(node));
        assertEquals(List.of("Result"), Ports.outputNames(node));
    }

    @Test
    void itTakesTheSliceBetweenTheTwoPositions() {
        assertEquals("front", slice("front door camera", 0, 5));
        assertEquals("door", slice("front door camera", 6, 10));
    }

    @Test
    void anUnsetPositionMeansTheStartOrTheEndOfTheText() {
        SubstringNode fromEight = new SubstringNode();
        Ports.set(fromEight, "Text", "housegraph-nodes");
        Ports.set(fromEight, "Start", 11);
        Ports.run(fromEight);
        assertEquals("nodes", Ports.get(fromEight, "Result"));

        SubstringNode upToTen = new SubstringNode();
        Ports.set(upToTen, "Text", "housegraph-nodes");
        Ports.set(upToTen, "End", 10);
        Ports.run(upToTen);
        assertEquals("housegraph", Ports.get(upToTen, "Result"));
    }

    @Test
    void aNegativePositionCountsBackFromTheEnd() {
        assertEquals(".log", slice("camera.log", -4, null));
    }

    @Test
    void aPositionPastTheEndClampsInsteadOfThrowing() {
        assertEquals("short", slice("short", 0, 500));
        assertEquals("", slice("short", 500, 600));
    }

    @Test
    void anEndBeforeTheStartYieldsEmptyText() {
        assertEquals("", slice("front door", 8, 2));
    }

    private static String slice(String text, Integer start, Integer end) {
        SubstringNode node = new SubstringNode();
        Ports.set(node, "Text", text);
        Ports.set(node, "Start", start);
        Ports.set(node, "End", end);
        Ports.run(node);
        return Ports.get(node, "Result");
    }
}
