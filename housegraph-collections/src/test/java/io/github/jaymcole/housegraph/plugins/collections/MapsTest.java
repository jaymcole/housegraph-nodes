package io.github.jaymcole.housegraph.plugins.collections;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules every map node inherits, tested once here rather than in each node test — the same
 * reason {@code ListsTest} exists for {@link Lists}.
 */
class MapsTest {

    @Test
    void anAbsentMapReadsAsAnEmptyOneRatherThanNull() {
        assertEquals(Map.of(), Maps.copyOf(null));
        assertEquals(Map.of(), Maps.mutableCopyOf(null));
    }

    @Test
    void copiesSurviveNullValues() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);

        assertEquals(1, Maps.copyOf(withNull).size(), "Map.copyOf would have thrown here");
    }

    @Test
    void aCopyIsDetachedFromTheMapItWasGiven() {
        Map<Object, Object> source = new LinkedHashMap<>(Map.of("a", 1));

        Map<Object, Object> copy = Maps.copyOf(source);
        source.put("b", 2);

        assertEquals(Map.of("a", 1), copy, "a node must not see its input change under it");
    }

    @Test
    void aPublishedMapRejectsMutation() {
        Map<Object, Object> published = Maps.frozen(new LinkedHashMap<>(Map.of("a", 1)));

        assertThrows(UnsupportedOperationException.class, () -> published.put("b", 2));
    }

    @Test
    void copiesPreserveInsertionOrder() {
        Map<Object, Object> source = new LinkedHashMap<>();
        source.put("z", 1);
        source.put("a", 2);

        assertEquals(java.util.List.of("z", "a"), new java.util.ArrayList<>(Maps.copyOf(source).keySet()));
    }

    @Test
    void keyLookupIsForgivingAcrossTypesByTextForm() {
        Map<Object, Object> map = Map.of(3, "three");

        assertTrue(Maps.containsKey(map, "3"), "a typed \"3\" has to find an upstream node's key of 3");
    }

    @Test
    void keyLookupReportsAbsenceRatherThanFailing() {
        assertFalse(Maps.containsKey(Map.of("a", 1), "z"));
        assertFalse(Maps.containsKey(null, "z"));
    }
}
