package io.github.jaymcole.housegraph.plugins.collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The authored-as-text numeric comparison: the symbols accepted, and the relation each tests. */
class ComparisonTest {

    @Test
    void anUnfilledComparisonMeansGreaterThan() {
        assertEquals(Comparison.GREATER, Comparison.parse(null));
        assertEquals(Comparison.GREATER, Comparison.parse(" "));
    }

    @Test
    void theSymbolsPeopleActuallyTypeAllResolve() {
        assertEquals(Comparison.GREATER_OR_EQUAL, Comparison.parse(">="));
        assertEquals(Comparison.GREATER_OR_EQUAL, Comparison.parse(" > = "));
        assertEquals(Comparison.GREATER_OR_EQUAL, Comparison.parse("gte"));
        assertEquals(Comparison.EQUAL, Comparison.parse("="));
        assertEquals(Comparison.NOT_EQUAL, Comparison.parse("<>"));
        assertEquals(Comparison.LESS, Comparison.parse("LT"));
    }

    @Test
    void anUnknownComparisonFailsLoudlyAndSaysWhatIsValid() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> Comparison.parse("bigger"));

        assertTrue(thrown.getMessage().contains(">="), "the message has to list the symbols that do work");
    }

    @Test
    void eachComparisonTestsTheRelationItNames() {
        assertTrue(Comparison.GREATER.test(21, 20));
        assertFalse(Comparison.GREATER.test(20, 20));
        assertTrue(Comparison.GREATER_OR_EQUAL.test(20, 20));
        assertTrue(Comparison.LESS.test(19, 20));
        assertTrue(Comparison.LESS_OR_EQUAL.test(20, 20));
        assertTrue(Comparison.EQUAL.test(20, 20));
        assertTrue(Comparison.NOT_EQUAL.test(19, 20));
    }
}
