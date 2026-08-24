package io.github.jaymcole.housegraph.plugins.collections;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules every set node plays by. The one worth its own file is the forgiving membership: a set
 * here counts a {@code 3} and a {@code "3"} as one member, which is the thing plain
 * {@code java.util.Set} does <em>not</em> do and the whole reason this class exists.
 */
class SetsTest {

    @Nested
    class Building {

        @Test
        void aNullCollectionReadsAsEmptyRatherThanFailing() {
            assertEquals(Set.of(), Sets.copyOf(null));
            assertEquals(Set.of(), Sets.mutableCopyOf(null));
        }

        @Test
        void repeatsAreDroppedAndTheFirstOfEachIsKept() {
            assertEquals(List.of("a", "b"), new ArrayList<>(Sets.copyOf(List.of("a", "b", "a"))));
        }

        @Test
        void twoSpellingsOfTheSameValueAreOneMember() {
            Set<Object> members = Sets.copyOf(List.of(3, "3"));

            assertEquals(1, members.size(), "erasure makes these indistinguishable downstream, so they are one thing");
            assertEquals(List.of(3), new ArrayList<>(members), "and the one that survives is the first seen");
        }

        @Test
        void membersKeepTheirTypeRatherThanBecomingText() {
            assertEquals(List.of(1, 2), new ArrayList<>(Sets.copyOf(List.of(1, 2))),
                    "a set of numbers has to still be numbers for List Statistics downstream");
        }

        @Test
        void insertionOrderSurvives() {
            assertEquals(List.of("z", "a", "m"), new ArrayList<>(Sets.copyOf(List.of("z", "a", "m"))),
                    "hash order would reshuffle the rendered set between runs for no reason");
        }

        @Test
        void nullsContributeNothing() {
            assertEquals(List.of("a"), new ArrayList<>(Sets.copyOf(Arrays.asList("a", null))));
        }
    }

    @Nested
    class Membership {

        private static final Set<Object> CAMERAS = Sets.copyOf(List.of("front", 3));

        @Test
        void findsAMemberByItsOwnSpelling() {
            assertTrue(Sets.contains(CAMERAS, "front"));
        }

        @Test
        void findsANumberThroughTypedText() {
            assertTrue(Sets.contains(CAMERAS, "3"), "the Item field is text; the set need not be");
            assertTrue(Sets.contains(CAMERAS, 3));
        }

        @Test
        void findsNothingThatIsntThere() {
            assertFalse(Sets.contains(CAMERAS, "back"));
            assertFalse(Sets.contains(CAMERAS, null), "an unwired Item is held by no set");
            assertFalse(Sets.contains(null, "front"));
        }
    }

    @Nested
    class Changing {

        @Test
        void addingSomethingNewGrowsTheSet() {
            Set<Object> members = Sets.mutableCopyOf(List.of("front"));

            assertTrue(Sets.add(members, "back"));
            assertEquals(List.of("front", "back"), new ArrayList<>(members));
        }

        @Test
        void addingSomethingAlreadyThereChangesNothing() {
            Set<Object> members = Sets.mutableCopyOf(List.of(3));

            assertFalse(Sets.add(members, "3"), "the forgiving comparison has to apply to adding too");
            assertFalse(Sets.add(members, null));
            assertEquals(List.of(3), new ArrayList<>(members));
        }

        @Test
        void removingFindsAMemberThroughTypedText() {
            Set<Object> members = Sets.mutableCopyOf(List.of(3, "front"));

            assertTrue(Sets.remove(members, "3"), "Set.remove would compare by equals here and miss it");
            assertEquals(List.of("front"), new ArrayList<>(members));
        }

        @Test
        void removingSomethingAbsentChangesNothing() {
            Set<Object> members = Sets.mutableCopyOf(List.of("front"));

            assertFalse(Sets.remove(members, "back"));
            assertFalse(Sets.remove(members, null));
            assertEquals(List.of("front"), new ArrayList<>(members));
        }
    }

    @Nested
    class Indexing {

        @Test
        void reportsEachMembersTextForm() {
            assertEquals(Set.of("3", "front"), Sets.keysOf(List.of(3, "front")));
        }

        @Test
        void skipsNullsAndHandlesNothingAtAll() {
            assertEquals(Set.of("a"), Sets.keysOf(Arrays.asList("a", null)));
            assertEquals(Set.of(), Sets.keysOf(null));
        }
    }

    @Nested
    class Publishing {

        @Test
        void anEmptySetPublishesAsAnEmptySetNotNull() {
            assertEquals(Set.of(), Sets.frozen(new LinkedHashSet<>()));
        }

        @Test
        void aPublishedSetCannotBeChangedByItsReader() {
            Set<Object> published = Sets.frozen(new LinkedHashSet<>(List.of("front")));

            assertThrows(UnsupportedOperationException.class, () -> published.add("back"));
        }

        @Test
        void aPublishedSetDoesNotChangeUnderItsReader() {
            Set<Object> working = new LinkedHashSet<>(List.of("front"));
            Set<Object> published = Sets.frozen(working);

            working.add("back");

            assertEquals(Set.of("front"), published);
        }

        @Test
        void publishingKeepsOrder() {
            Set<Object> working = new LinkedHashSet<>(List.of("z", "a"));

            assertEquals(List.of("z", "a"), new ArrayList<>(Sets.frozen(working)),
                    "Set.copyOf would lose this, which is why frozen() doesn't use it");
        }
    }
}
