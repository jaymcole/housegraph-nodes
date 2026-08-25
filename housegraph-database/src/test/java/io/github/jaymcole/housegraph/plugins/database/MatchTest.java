package io.github.jaymcole.housegraph.plugins.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a person can type into a Test field, and what happens when they type something else. */
class MatchTest {

    @Test
    void blankMeansEquals() {
        assertEquals(Match.EQUALS, Match.parse(null));
        assertEquals(Match.EQUALS, Match.parse("  "));
    }

    @Test
    void acceptsSymbolsWordsAndSpacing() {
        assertEquals(Match.GREATER_OR_EQUAL, Match.parse(">="));
        assertEquals(Match.GREATER_OR_EQUAL, Match.parse("gte"));
        assertEquals(Match.NOT_EQUALS, Match.parse("<>"));
        assertEquals(Match.STARTS_WITH, Match.parse("  Starts   With "));
        assertEquals(Match.IS_EMPTY, Match.parse("IS NULL"));
        assertEquals(Match.IS_NOT_EMPTY, Match.parse("not empty"));
    }

    @Test
    void refusesSomethingItDoesNotUnderstand() {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Match.parse("roughly"));
        assertTrue(failure.getMessage().contains("contains"), "the message lists what is valid");
    }

    @Test
    void knowsWhichTestsReadAValue() {
        assertTrue(Match.EQUALS.needsValue());
        assertFalse(Match.IS_EMPTY.needsValue());
        assertFalse(Match.IS_NOT_EMPTY.needsValue());
    }
}
