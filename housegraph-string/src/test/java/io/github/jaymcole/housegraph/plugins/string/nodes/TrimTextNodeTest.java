package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises {@link TrimTextNode}'s ports and its handling of an authored trim mode. */
class TrimTextNodeTest {

    @Test
    void declaresTextAndTrimInputsAndAResultOutput() {
        TrimTextNode node = new TrimTextNode();

        assertEquals(List.of("Text", "Trim"), Ports.inputNames(node));
        assertEquals(List.of("Result"), Ports.outputNames(node));
    }

    @Test
    void itTrimsBothEndsByDefault() {
        TrimTextNode node = new TrimTextNode();
        Ports.set(node, "Text", "  motion detected\n");

        Ports.run(node);

        assertEquals("motion detected", Ports.get(node, "Result"));
    }

    @Test
    void collapseMakesAMultiLineBlobSafeForAOneLineDisplay() {
        TrimTextNode node = new TrimTextNode();
        Ports.set(node, "Text", "  motion\n  detected   now ");
        Ports.set(node, "Trim", "collapse");

        Ports.run(node);

        assertEquals("motion detected now", Ports.get(node, "Result"));
    }

    @Test
    void unwiredTextIsEmptyTextRatherThanAFailure() {
        TrimTextNode node = new TrimTextNode();

        Ports.run(node);

        assertEquals("", Ports.get(node, "Result"));
    }
}
