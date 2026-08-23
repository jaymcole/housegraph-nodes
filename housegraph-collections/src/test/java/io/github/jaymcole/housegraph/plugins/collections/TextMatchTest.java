package io.github.jaymcole.housegraph.plugins.collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The authored-as-text match mode: what a user may type, and what each mode then does. */
class TextMatchTest {

    @Test
    void anUnfilledModeMeansContains() {
        assertEquals(TextMatch.CONTAINS, TextMatch.parse(null));
        assertEquals(TextMatch.CONTAINS, TextMatch.parse("   "));
    }

    @Test
    void spellingIsForgivenExceptForGettingTheWordWrong() {
        assertEquals(TextMatch.STARTS_WITH, TextMatch.parse("starts with"));
        assertEquals(TextMatch.STARTS_WITH, TextMatch.parse("Starts With"));
        assertEquals(TextMatch.STARTS_WITH, TextMatch.parse("starts_with"));
        assertEquals(TextMatch.STARTS_WITH, TextMatch.parse(" STARTSWITH "));
        assertEquals(TextMatch.NOT_EQUALS, TextMatch.parse("not-equals"));
    }

    @Test
    void anUnknownModeFailsLoudlyAndSaysWhatIsValid() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> TextMatch.parse("greater than"));

        assertTrue(thrown.getMessage().contains("starts with"), "the message has to list the modes that do work");
    }

    @Test
    void matchingIgnoresCaseOnBothSides() {
        assertTrue(TextMatch.CONTAINS.matches("Front Door Camera", "door"));
        assertTrue(TextMatch.EQUALS.matches("kitchen", "KITCHEN"));
        assertTrue(TextMatch.ENDS_WITH.matches("motion.jpg", ".JPG"));
    }

    @Test
    void negatedModesAreTheExactComplementOfTheirPositives() {
        assertFalse(TextMatch.NOT_CONTAINS.matches("front door", "door"));
        assertTrue(TextMatch.NOT_CONTAINS.matches("front door", "window"));
        assertTrue(TextMatch.NOT_EQUALS.matches("front door", "door"));
    }

    @Test
    void anUnfilledTextKeepsEverythingRatherThanEmptyingTheList() {
        assertTrue(TextMatch.CONTAINS.matches("anything", null));
        assertTrue(TextMatch.CONTAINS.matches("anything", ""));
        assertTrue(TextMatch.STARTS_WITH.matches("anything", ""));
    }

    @Test
    void anEntryWithNoTextStillCompares() {
        assertTrue(TextMatch.EQUALS.matches("", ""));
        assertFalse(TextMatch.CONTAINS.matches("", "door"));
    }
}
