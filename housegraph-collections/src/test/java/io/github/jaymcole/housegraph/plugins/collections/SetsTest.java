package io.github.jaymcole.housegraph.plugins.collections;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules every set node inherits, tested once here rather than in each node test — the same
 * reason {@code ListsTest} exists for {@link Lists}.
 */
class SetsTest {

    @Test
    void anAbsentSetReadsAsAnEmptyOneRatherThanNull() {
        assertEquals(Set.of(), Sets.copyOf(null));
        assertEquals(Set.of(), Sets.mutableCopyOf(null));
    }

    @Test
    void aCopyIsDetachedFromTheSetItWasGiven() {
        LinkedHashSet<Object> source = new LinkedHashSet<>(List.of("a"));

        Set<Object> copy = Sets.copyOf(source);
        source.add("b");

        assertEquals(Set.of("a"), copy, "a node must not see its input change under it");
    }

    @Test
    void aPublishedSetRejectsMutation() {
        Set<Object> published = Sets.frozen(new LinkedHashSet<>(List.of("a")));

        assertThrows(UnsupportedOperationException.class, () -> published.add("b"));
    }

    @Test
    void copiesPreserveInsertionOrder() {
        LinkedHashSet<Object> source = new LinkedHashSet<>(List.of("z", "a"));

        assertEquals(List.of("z", "a"), new java.util.ArrayList<>(Sets.copyOf(source)));
    }

    @Test
    void membershipIsForgivingAcrossTypesByTextForm() {
        assertTrue(Sets.contains(Set.of(3), "3"), "a typed \"3\" has to find an upstream node's 3");
        assertFalse(Sets.contains(Set.of("a"), "z"));
        assertFalse(Sets.contains(null, "z"));
    }

    @Test
    void fromListDropsRepeatsByTextFormKeepingTheFirstOccurrence() {
        Set<Object> result = Sets.fromList(List.of(3, "3", "3", 4));

        assertEquals(List.of(3, 4), new java.util.ArrayList<>(result),
                "the first occurrence's actual value is kept, in first-seen order");
    }

    @Test
    void fromListOfAnAbsentListIsEmpty() {
        assertEquals(Set.of(), Sets.fromList(null));
    }
}
