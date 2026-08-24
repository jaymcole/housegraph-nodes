package io.github.jaymcole.housegraph.plugins.collections;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules every map node plays by, tested here rather than through ten nodes each asserting the
 * same thing. What is worth pinning down is the set of decisions a reader would otherwise have to
 * take on trust from the class documentation: keys become text, a blank key is not a key, a null
 * value is not an entry, and order survives.
 */
class MapsTest {

    @Nested
    class Keys {

        @Test
        void aKeyBecomesItsTextForm() {
            assertEquals("3", Maps.key(3));
            assertEquals("3", Maps.key("3"));
            assertEquals("true", Maps.key(true));
        }

        @Test
        void aNullOrBlankKeyIsNoKeyAtAll() {
            assertNull(Maps.key(null), "an unwired Key field is not a key");
            assertNull(Maps.key(""), "an empty Key field means unfinished wiring, not the empty-string key");
            assertNull(Maps.key("   "), "and neither does typing spaces into it");
        }

        @Test
        void aKeyKeepsItsSurroundingSpaceOnceItHasAny() {
            assertEquals(" front door ", Maps.key(" front door "),
                    "trimming would silently merge two keys someone typed differently");
        }
    }

    @Nested
    class Copying {

        @Test
        void aNullMapReadsAsEmptyRatherThanFailing() {
            assertEquals(Map.of(), Maps.copyOf(null));
            assertEquals(Map.of(), Maps.mutableCopyOf(null));
        }

        @Test
        void foreignKeysAreNormalisedToText() {
            Map<Object, Object> source = new LinkedHashMap<>();
            source.put(3, "three");
            source.put(true, "yes");

            assertEquals(List.of("3", "true"), new ArrayList<>(Maps.copyOf(source).keySet()));
        }

        @Test
        void keysThatCollapseToTheSameTextKeepTheFirst() {
            Map<Object, Object> source = new LinkedHashMap<>();
            source.put(3, "as a number");
            source.put("3", "as text");

            Map<String, Object> copy = Maps.copyOf(source);

            assertEquals(1, copy.size());
            assertEquals("as a number", copy.get("3"), "Distinct keeps the first of each repeat; so does this");
        }

        @Test
        void entriesWithNothingInThemDoNotSurviveTheCopy() {
            Map<Object, Object> source = new LinkedHashMap<>();
            source.put("kept", "value");
            source.put("", "blank key");
            source.put("no value", null);

            assertEquals(Map.of("kept", "value"), Maps.copyOf(source));
        }

        @Test
        void insertionOrderSurvives() {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("z", 1);
            source.put("a", 2);
            source.put("m", 3);

            assertEquals(List.of("z", "a", "m"), new ArrayList<>(Maps.copyOf(source).keySet()),
                    "a map that reordered itself would render differently on every run");
        }

        @Test
        void theCopyIsNotAViewOntoWhatWasHandedIn() {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("front", "door");
            Map<String, Object> copy = Maps.mutableCopyOf(source);

            source.put("back", "gate");

            assertEquals(Map.of("front", "door"), copy);
        }
    }

    @Nested
    class Publishing {

        @Test
        void anEmptyMapPublishesAsAnEmptyMapNotNull() {
            assertEquals(Map.of(), Maps.frozen(new LinkedHashMap<>()));
        }

        @Test
        void aPublishedMapCannotBeChangedByItsReader() {
            Map<String, Object> working = new LinkedHashMap<>();
            working.put("front", "door");
            Map<String, Object> published = Maps.frozen(working);

            assertThrows(UnsupportedOperationException.class, () -> published.put("back", "gate"));
        }

        @Test
        void aPublishedMapDoesNotChangeUnderItsReader() {
            Map<String, Object> working = new LinkedHashMap<>();
            working.put("front", "door");
            Map<String, Object> published = Maps.frozen(working);

            working.put("back", "gate");

            assertEquals(Map.of("front", "door"), published);
        }

        @Test
        void publishingKeepsOrder() {
            Map<String, Object> working = new LinkedHashMap<>();
            working.put("z", 1);
            working.put("a", 2);

            assertEquals(List.of("z", "a"), new ArrayList<>(Maps.frozen(working).keySet()),
                    "Map.copyOf would lose this, which is why frozen() doesn't use it");
        }
    }

    @Nested
    class Putting {

        @Test
        void storesAWholePairUnderItsTextKey() {
            Map<String, Object> target = new LinkedHashMap<>();

            assertTrue(Maps.put(target, 3, "three"));
            assertEquals(Map.of("3", "three"), target);
        }

        @Test
        void aHalfFilledPairStoresNothing() {
            Map<String, Object> target = new LinkedHashMap<>();

            assertFalse(Maps.put(target, null, "orphan value"));
            assertFalse(Maps.put(target, "  ", "blank key"));
            assertFalse(Maps.put(target, "orphan key", null));

            assertEquals(Map.of(), target, "Map Get's Found is only trustworthy if none of these got in");
        }

        @Test
        void aValueIsKeptAsTheObjectItArrivedAs() {
            Map<String, Object> target = new LinkedHashMap<>();
            List<Object> value = List.of("a", "b");
            Maps.put(target, "list", value);

            assertSame(value, target.get("list"), "only keys are normalised to text, never values");
        }

        @Test
        void puttingAgainReplacesInPlace() {
            Map<String, Object> target = new LinkedHashMap<>();
            Maps.put(target, "first", 1);
            Maps.put(target, "second", 2);
            Maps.put(target, "first", 99);

            assertEquals(99, target.get("first"));
            assertEquals(List.of("first", "second"), new ArrayList<>(target.keySet()),
                    "a replaced entry keeps its position rather than jumping to the end");
        }
    }
}
