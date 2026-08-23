package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ParseNumberNode}, including the strictness that separates it from
 * {@link Double#valueOf} — the point being that a graph must not take a numeric branch because
 * somebody typed "Infinity" into a chat message.
 */
class ParseNumberNodeTest {

    @Test
    void declaresOneInputAndTwoOutputs() {
        ParseNumberNode node = new ParseNumberNode();

        assertEquals(List.of("Text"), Ports.inputNames(node));
        assertEquals(List.of("Number", "Valid"), Ports.outputNames(node));
    }

    @Test
    void itReadsWholeNumbersDecimalsSignsAndExponents() {
        assertEquals(21.0, parse("21"));
        assertEquals(-3.5, parse("-3.5"));
        assertEquals(1500.0, parse("1.5e3"));
        assertEquals(0.25, parse(" .25 "));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        ParseNumberNode node = reading("  42\n");

        Ports.run(node);

        assertTrue(Ports.boolOf(node, "Valid"));
        assertEquals(42.0, Ports.doubleOf(node, "Number"));
    }

    @Test
    void textThatIsNotANumberReportsInvalidRatherThanFailingTheNode() {
        for (String notANumber : List.of("", "warm", "21C", "1,500", "50%", "Infinity", "NaN", "10f", "0x1p3")) {
            ParseNumberNode node = reading(notANumber);

            Ports.run(node);

            assertFalse(Ports.boolOf(node, "Valid"), "\"" + notANumber + "\" should not read as a number");
            assertEquals(0.0, Ports.doubleOf(node, "Number"));
        }
    }

    @Test
    void unwiredTextIsInvalidRatherThanZero() {
        ParseNumberNode node = new ParseNumberNode();

        Ports.run(node);

        assertFalse(Ports.boolOf(node, "Valid"));
    }

    private static double parse(String text) {
        ParseNumberNode node = reading(text);
        Ports.run(node);
        assertTrue(Ports.boolOf(node, "Valid"), "\"" + text + "\" should read as a number");
        return Ports.doubleOf(node, "Number");
    }

    private static ParseNumberNode reading(String text) {
        ParseNumberNode node = new ParseNumberNode();
        Ports.set(node, "Text", text);
        return node;
    }
}
