package io.github.jaymcole.housegraph.plugins.collections;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules every node in the library inherits, tested once here rather than two dozen times in
 * the node tests. Each of these encodes a decision that erasure forced (see {@link Lists}), so
 * they are the tests most worth having: a node test would only ever re-observe them.
 */
class ListsTest {

    @Test
    void anAbsentListReadsAsAnEmptyOneRatherThanNull() {
        assertEquals(List.of(), Lists.copyOf(null));
        assertEquals(List.of(), Lists.mutableCopyOf(null));
    }

    @Test
    void copiesSurviveNullElements() {
        List<Object> withNulls = Arrays.asList("a", null, "b");

        assertEquals(3, Lists.copyOf(withNulls).size(), "List.copyOf would have thrown here");
        assertEquals(3, Lists.frozen(new ArrayList<>(withNulls)).size());
    }

    @Test
    void aCopyIsDetachedFromTheListItWasGiven() {
        List<Object> source = new ArrayList<>(List.of("a"));

        List<Object> copy = Lists.copyOf(source);
        source.add("b");

        assertEquals(List.of("a"), copy, "a node must not see its input change under it");
    }

    @Test
    void aPublishedListRejectsMutation() {
        List<Object> published = Lists.frozen(new ArrayList<>(List.of("a")));

        assertThrows(UnsupportedOperationException.class, () -> published.add("b"));
    }

    @Test
    void equalityAlsoMatchesAcrossTypesByTextForm() {
        assertTrue(Lists.sameValue(3, "3"), "a typed \"3\" has to find an upstream node's 3");
        assertTrue(Lists.sameValue("kitchen", "kitchen"));
        assertFalse(Lists.sameValue("kitchen", "Kitchen"), "equality is exact; only the filters are case-insensitive");
    }

    @Test
    void nullEqualsOnlyNullAndNeverTheEmptyString() {
        assertTrue(Lists.sameValue(null, null));
        assertFalse(Lists.sameValue(null, ""));
        assertFalse(Lists.sameValue("", null));
    }

    @Test
    void nullRendersAsNothingRatherThanTheWordNull() {
        assertEquals("", Lists.text(null));
        assertEquals("7", Lists.text(7));
    }

    @Test
    void dedupKeysCollapseNullsTogetherWithoutCollidingWithTheEmptyString() {
        assertNull(Lists.key(null));
        assertEquals("", Lists.key(""));
        assertEquals(Lists.key(3), Lists.key("3"));
    }

    @Test
    void numbersAreReadFromNumbersAndFromStringsThatParse() {
        assertEquals(3.0, Lists.number(3));
        assertEquals(2.5, Lists.number("2.5"));
        assertEquals(7.0, Lists.number("  7 "), "a split entry may still carry whitespace");
        assertNull(Lists.number("warm"));
        assertNull(Lists.number(null));
        assertNull(Lists.number(true), "a flag is not a number to filter on");
    }

    @Test
    void sortingComparesNumbersNumericallyEvenWhenTheyAreText() {
        List<Object> entries = new ArrayList<>(List.of("10", "9", "100"));

        entries.sort(Lists.NATURAL_ORDER);

        assertEquals(List.of("9", "10", "100"), entries, "a plain text sort gets this backwards");
    }

    @Test
    void sortingComparesTextCaseInsensitivelyAndPutsNullsFirst() {
        List<Object> entries = new ArrayList<>(Arrays.asList("banana", null, "Apple"));

        entries.sort(Lists.NATURAL_ORDER);

        assertEquals(Arrays.asList(null, "Apple", "banana"), entries);
    }

    @Test
    void sortingStaysTotalWhenTwoEntriesDifferOnlyInCase() {
        assertEquals(0, Lists.compare("a", "a"));
        assertTrue(Lists.compare("a", "A") != 0, "a case-only difference still has to order deterministically");
    }

    @Test
    void aNegativeIndexCountsBackFromTheEnd() {
        assertEquals(2, Lists.resolveIndex(-1, 3));
        assertEquals(0, Lists.resolveIndex(-3, 3));
        assertEquals(1, Lists.resolveIndex(1, 3));
    }

    @Test
    void anIndexOffEitherEndResolvesToNoIndexAtAll() {
        assertEquals(-1, Lists.resolveIndex(3, 3));
        assertEquals(-1, Lists.resolveIndex(-4, 3));
        assertEquals(-1, Lists.resolveIndex(0, 0));
    }

    @Test
    void escapesBecomeTheCharactersTheyStandFor() {
        assertEquals("a\nb", Lists.unescape("a\\nb"));
        assertEquals("a\tb", Lists.unescape("a\\tb"));
        assertEquals("", Lists.unescape(null));
        assertEquals("plain", Lists.unescape("plain"));
    }

    @Test
    void anEscapedBackslashIsNotTheStartOfAnEscape() {
        assertEquals("a\\nb", Lists.unescape("a\\\\nb"),
                "the chained-replace version of this turns a literal backslash-n into a newline");
    }

    @Test
    void anUnknownEscapeIsLeftExactlyAsItWasTyped() {
        assertEquals("C:\\Users", Lists.unescape("C:\\Users"));
    }
}
