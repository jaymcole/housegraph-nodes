package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link RegexFindAllNode}: collecting every match, and choosing which group to collect. */
class RegexFindAllNodeTest {

    @Test
    void declaresThreeInputsAndTwoOutputs() {
        RegexFindAllNode node = new RegexFindAllNode();

        assertEquals(List.of("Text", "Pattern", "Group"), Ports.inputNames(node));
        assertEquals(List.of("Matches", "Count"), Ports.outputNames(node));
    }

    @Test
    void itCollectsEveryMatch() {
        RegexFindAllNode node = finding("21C, 19C and 23C", "\\d+C", null);

        Ports.run(node);

        assertEquals(List.of("21C", "19C", "23C"), Ports.listOf(node, "Matches"));
        assertEquals(3, Ports.intOf(node, "Count"));
    }

    @Test
    void describingThePiecesIsHowYouSplitOnSomethingThatVaries() {
        RegexFindAllNode node = finding("  motion   detected\tnow ", "\\S+", null);

        Ports.run(node);

        assertEquals(List.of("motion", "detected", "now"), Ports.listOf(node, "Matches"));
    }

    @Test
    void aGroupChoosesWhatEachEntryIs() {
        RegexFindAllNode names = finding("mode=auto, level=3", "(\\w+)=(\\w+)", 1);
        Ports.run(names);
        assertEquals(List.of("mode", "level"), Ports.listOf(names, "Matches"));

        RegexFindAllNode values = finding("mode=auto, level=3", "(\\w+)=(\\w+)", 2);
        Ports.run(values);
        assertEquals(List.of("auto", "3"), Ports.listOf(values, "Matches"));
    }

    @Test
    void askingForAGroupThePatternDoesNotHaveFailsRatherThanLookingLikeNothingMatched() {
        RegexFindAllNode node = finding("mode=auto", "\\w+=\\w+", 2);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Ports.run(node));
        assertTrue(failure.getMessage().contains("capture group"));
    }

    @Test
    void nothingFoundIsAnEmptyListAndACountOfZeroNeverANull() {
        RegexFindAllNode node = finding("all quiet", "\\d+", null);

        Ports.run(node);

        assertEquals(List.of(), Ports.listOf(node, "Matches"));
        assertEquals(0, Ports.intOf(node, "Count"));
    }

    private static RegexFindAllNode finding(String text, String pattern, Integer group) {
        RegexFindAllNode node = new RegexFindAllNode();
        Ports.set(node, "Text", text);
        Ports.set(node, "Pattern", pattern);
        if (group != null) {
            Ports.set(node, "Group", group);
        }
        return node;
    }
}
