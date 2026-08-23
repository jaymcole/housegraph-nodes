package io.github.jaymcole.housegraph.plugins.string;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the three authored modes: how a typed label is matched, what a blank field selects,
 * that an unrecognised one fails loudly, and what each mode actually does.
 */
class ModesTest {

    @Test
    void aLabelIsMatchedRegardlessOfCaseSpacingUnderscoresOrHyphens() {
        assertEquals(CompareMode.STARTS_WITH, CompareMode.parse("starts with"));
        assertEquals(CompareMode.STARTS_WITH, CompareMode.parse("Starts With"));
        assertEquals(CompareMode.STARTS_WITH, CompareMode.parse("starts_with"));
        assertEquals(CompareMode.STARTS_WITH, CompareMode.parse("STARTS-WITH"));
        assertEquals(CompareMode.STARTS_WITH, CompareMode.parse("  startswith "));
    }

    @Test
    void aBlankFieldSelectsTheModeAUserWhoLeftItAloneMeant() {
        assertEquals(CaseMode.UPPER, CaseMode.parse(null));
        assertEquals(TrimMode.BOTH, TrimMode.parse("   "));
        assertEquals(CompareMode.CONTAINS, CompareMode.parse(""));
    }

    @Test
    void anUnrecognisedModeThrowsAndSaysWhatWasExpected() {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> CaseMode.parse("shouty"));

        assertTrue(failure.getMessage().contains("shouty"));
        assertTrue(failure.getMessage().contains("upper"), "the message should list the valid modes");
    }

    @Test
    void changingCase() {
        assertEquals("ALERT", CaseMode.UPPER.apply("Alert"));
        assertEquals("alert", CaseMode.LOWER.apply("Alert"));
        assertEquals("Front Door Camera", CaseMode.TITLE.apply("front DOOR camera"));
        assertEquals("Front door camera", CaseMode.SENTENCE.apply("front DOOR camera"));
        assertEquals("", CaseMode.TITLE.apply(null));
    }

    @Test
    void trimming() {
        assertEquals("alert", TrimMode.BOTH.apply("  alert  "));
        assertEquals("alert  ", TrimMode.LEADING.apply("  alert  "));
        assertEquals("  alert", TrimMode.TRAILING.apply("  alert  "));
        assertEquals("two words here", TrimMode.COLLAPSE.apply("  two   words\n\there  "));
        assertEquals("", TrimMode.BOTH.apply(null));
    }

    @Test
    void comparingIsCaseInsensitiveSoALabelMatchesHoweverItWasTyped() {
        assertTrue(CompareMode.CONTAINS.test("The Front Door opened", "front door"));
        assertTrue(CompareMode.STARTS_WITH.test("!deploy now", "!DEPLOY"));
        assertTrue(CompareMode.ENDS_WITH.test("report.PDF", ".pdf"));
        assertTrue(CompareMode.EQUALS.test("Kitchen", "kitchen"));
    }

    @Test
    void theNegatedModesAreTheInverseOfTheirPositiveOnes() {
        assertFalse(CompareMode.NOT_CONTAINS.test("kitchen light", "kitchen"));
        assertTrue(CompareMode.NOT_CONTAINS.test("hallway light", "kitchen"));
        assertTrue(CompareMode.NOT_EQUALS.test("hallway", "kitchen"));
    }

    @Test
    void anUnfilledSearchMatchesEverythingPositiveAndNothingNegated() {
        assertTrue(CompareMode.CONTAINS.test("anything", null));
        assertTrue(CompareMode.STARTS_WITH.test("anything", ""));
        assertFalse(CompareMode.NOT_CONTAINS.test("anything", ""));
    }

    @Test
    void theIndexIsWhereTheSearchTextFirstAppearsIgnoringCase() {
        assertEquals(4, CompareMode.indexOf("the FRONT door", "front"));
        assertEquals(-1, CompareMode.indexOf("the front door", "kitchen"));
        assertEquals(-1, CompareMode.indexOf(null, "kitchen"));
    }
}
