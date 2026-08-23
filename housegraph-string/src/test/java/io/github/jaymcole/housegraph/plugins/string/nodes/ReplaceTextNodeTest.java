package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises {@link ReplaceTextNode}: plain replacement, the count it reports, and its edge cases. */
class ReplaceTextNodeTest {

    @Test
    void declaresItsThreeInputsAndTwoOutputs() {
        ReplaceTextNode node = new ReplaceTextNode();

        assertEquals(List.of("Text", "Find", "Replace With"), Ports.inputNames(node));
        assertEquals(List.of("Result", "Replacements"), Ports.outputNames(node));
    }

    @Test
    void itReplacesEveryOccurrenceAndCountsThem() {
        ReplaceTextNode node = replacing("the cat sat on the mat", "at", "og");

        Ports.run(node);

        assertEquals("the cog sog on the mog", Ports.get(node, "Result"));
        assertEquals(3, Ports.intOf(node, "Replacements"));
    }

    @Test
    void findIsPlainTextNotAPatternSoAFullStopIsAFullStop() {
        ReplaceTextNode node = replacing("a.b.c", ".", "-");

        Ports.run(node);

        assertEquals("a-b-c", Ports.get(node, "Result"));
    }

    @Test
    void replacingWithNothingDeletes() {
        ReplaceTextNode node = new ReplaceTextNode();
        Ports.set(node, "Text", "[DEBUG] motion");
        Ports.set(node, "Find", "[DEBUG] ");

        Ports.run(node);

        assertEquals("motion", Ports.get(node, "Result"));
    }

    @Test
    void findingNothingLeavesTheTextAloneAndReportsZero() {
        ReplaceTextNode node = replacing("all quiet", "motion", "!");

        Ports.run(node);

        assertEquals("all quiet", Ports.get(node, "Result"));
        assertEquals(0, Ports.intOf(node, "Replacements"));
    }

    @Test
    void aBlankFindChangesNothingRatherThanInsertingBetweenEveryCharacter() {
        ReplaceTextNode node = replacing("quiet", "", "!");

        Ports.run(node);

        assertEquals("quiet", Ports.get(node, "Result"));
        assertEquals(0, Ports.intOf(node, "Replacements"));
    }

    private static ReplaceTextNode replacing(String text, String find, String with) {
        ReplaceTextNode node = new ReplaceTextNode();
        Ports.set(node, "Text", text);
        Ports.set(node, "Find", find);
        Ports.set(node, "Replace With", with);
        return node;
    }
}
