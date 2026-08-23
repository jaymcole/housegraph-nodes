package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises {@link RegexReplaceNode}: group references, deletion, and the count it reports. */
class RegexReplaceNodeTest {

    @Test
    void declaresThreeInputsAndTwoOutputs() {
        RegexReplaceNode node = new RegexReplaceNode();

        assertEquals(List.of("Text", "Pattern", "Replacement"), Ports.inputNames(node));
        assertEquals(List.of("Result", "Replacements"), Ports.outputNames(node));
    }

    @Test
    void aReplacementCanReferBackToWhatMatched() {
        RegexReplaceNode node = rewriting("due 2026-08-23", "(\\d{4})-(\\d{2})-(\\d{2})", "$3/$2/$1");

        Ports.run(node);

        assertEquals("due 23/08/2026", Ports.get(node, "Result"));
        assertEquals(1, Ports.intOf(node, "Replacements"));
    }

    @Test
    void replacingWithNothingStripsEveryMatchOut() {
        RegexReplaceNode node = rewriting("<b>motion</b> detected", "</?\\w+>", "");

        Ports.run(node);

        assertEquals("motion detected", Ports.get(node, "Result"));
        assertEquals(2, Ports.intOf(node, "Replacements"));
    }

    @Test
    void itReplacesEveryMatchAndTheCountComesFromTheSamePassThatDidTheWork() {
        RegexReplaceNode node = rewriting("21C 19C 23C", "\\d+", "#");

        Ports.run(node);

        assertEquals("#C #C #C", Ports.get(node, "Result"));
        assertEquals(3, Ports.intOf(node, "Replacements"));
    }

    @Test
    void matchingNothingLeavesTheTextAloneAndReportsZero() {
        RegexReplaceNode node = rewriting("all quiet", "\\d+", "#");

        Ports.run(node);

        assertEquals("all quiet", Ports.get(node, "Result"));
        assertEquals(0, Ports.intOf(node, "Replacements"));
    }

    @Test
    void aReplacementNamingAGroupThePatternDoesNotHaveFailsTheNode() {
        RegexReplaceNode node = rewriting("motion", "(m)", "$4");

        assertThrows(IllegalArgumentException.class, () -> Ports.run(node));
    }

    @Test
    void aPatternThatWillNotCompileFailsTheNode() {
        RegexReplaceNode node = rewriting("motion", "(unclosed", "x");

        assertThrows(IllegalArgumentException.class, () -> Ports.run(node));
    }

    private static RegexReplaceNode rewriting(String text, String pattern, String replacement) {
        RegexReplaceNode node = new RegexReplaceNode();
        Ports.set(node, "Text", text);
        Ports.set(node, "Pattern", pattern);
        Ports.set(node, "Replacement", replacement);
        return node;
    }
}
