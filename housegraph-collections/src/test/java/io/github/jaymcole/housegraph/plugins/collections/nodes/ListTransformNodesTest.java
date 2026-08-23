package io.github.jaymcole.housegraph.plugins.collections.nodes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list-in, list-out nodes. Grouped for the same reason as the inspection ones: individually
 * each is a few lines, and collectively they share the promise that matters — the list they were
 * given comes back untouched, and the one they publish can't be written to.
 */
class ListTransformNodesTest {

    @Nested
    class Reverse {

        @Test
        void turnsTheListAround() {
            ReverseListNode node = new ReverseListNode();
            Nodes.set(node, "List", List.of("a", "b", "c"));
            Nodes.run(node);

            assertEquals(List.of("c", "b", "a"), Nodes.list(node, "List"));
        }

        @Test
        void leavesTheListItWasGivenAlone() {
            List<Object> source = new ArrayList<>(List.of("a", "b"));
            ReverseListNode node = new ReverseListNode();
            Nodes.set(node, "List", source);
            Nodes.run(node);

            assertEquals(List.of("a", "b"), source,
                    "several nodes can read one list in a run; reversing it in place would change what they see");
        }
    }

    @Nested
    class Sort {

        @Test
        void ordersTextAlphabeticallyIgnoringCase() {
            SortListNode node = new SortListNode();
            Nodes.set(node, "List", List.of("banana", "Apple", "cherry"));
            Nodes.run(node);

            assertEquals(List.of("Apple", "banana", "cherry"), Nodes.list(node, "List"));
        }

        @Test
        void ordersNumbersNumericallyEvenAsText() {
            SortListNode node = new SortListNode();
            Nodes.set(node, "List", List.of("10", "9", "100"));
            Nodes.run(node);

            assertEquals(List.of("9", "10", "100"), Nodes.list(node, "List"));
        }

        @Test
        void descendingIsThisFollowedByReverse() {
            SortListNode sort = new SortListNode();
            Nodes.set(sort, "List", List.of(3, 1, 2));
            Nodes.run(sort);

            ReverseListNode reverse = new ReverseListNode();
            Nodes.set(reverse, "List", Nodes.list(sort, "List"));
            Nodes.run(reverse);

            assertEquals(List.of(3, 2, 1), Nodes.list(reverse, "List"));
        }
    }

    @Nested
    class Distinct {

        @Test
        void keepsTheFirstOfEachAndReportsWhatItDropped() {
            DistinctListNode node = new DistinctListNode();
            Nodes.set(node, "List", List.of("front", "back", "front", "front"));
            Nodes.run(node);

            assertEquals(List.of("front", "back"), Nodes.list(node, "List"), "original order is preserved");
            assertEquals(2, Nodes.get(node, "Removed"));
        }

        @Test
        void collapsesEntriesThatOnlyDifferByType() {
            DistinctListNode node = new DistinctListNode();
            Nodes.set(node, "List", List.of(3, "3"));
            Nodes.run(node);

            assertEquals(1, Nodes.list(node, "List").size(),
                    "nothing downstream could tell these two apart, so neither does this");
        }

        @Test
        void severalNullsCollapseIntoOne() {
            DistinctListNode node = new DistinctListNode();
            Nodes.set(node, "List", Arrays.asList(null, "a", null));
            Nodes.run(node);

            assertEquals(Arrays.asList(null, "a"), Nodes.list(node, "List"));
        }
    }

    @Nested
    class Concat {

        @Test
        void putsOneListAfterTheOther() {
            ConcatListsNode node = new ConcatListsNode();
            Nodes.set(node, "First", List.of("a"));
            Nodes.set(node, "Second", List.of("b", "c"));
            Nodes.run(node);

            assertEquals(List.of("a", "b", "c"), Nodes.list(node, "List"));
        }

        @Test
        void anUnwiredSideLetsTheOtherPassThrough() {
            ConcatListsNode node = new ConcatListsNode();
            Nodes.set(node, "Second", List.of("b"));
            Nodes.run(node);

            assertEquals(List.of("b"), Nodes.list(node, "List"));
        }
    }

    @Nested
    class Append {

        @Test
        void addsTheItemToTheEnd() {
            AppendItemNode node = new AppendItemNode();
            Nodes.set(node, "List", List.of("a"));
            Nodes.set(node, "Item", "b");
            Nodes.run(node);

            assertEquals(List.of("a", "b"), Nodes.list(node, "List"));
        }

        @Test
        void anUnwiredItemLeavesTheListAsItWas() {
            AppendItemNode node = new AppendItemNode();
            Nodes.set(node, "List", List.of("a"));
            Nodes.run(node);

            assertEquals(List.of("a"), Nodes.list(node, "List"));
        }

        @Test
        void appendingToNothingStartsAList() {
            AppendItemNode node = new AppendItemNode();
            Nodes.set(node, "Item", 42);
            Nodes.run(node);

            assertEquals(List.of(42), Nodes.list(node, "List"));
        }
    }

    @Nested
    class Remove {

        @Test
        void takesOutEveryCopyAndCountsThem() {
            RemoveItemNode node = new RemoveItemNode();
            Nodes.set(node, "List", List.of("a", "b", "a"));
            Nodes.set(node, "Item", "a");
            Nodes.run(node);

            assertEquals(List.of("b"), Nodes.list(node, "List"));
            assertEquals(2, Nodes.get(node, "Removed"));
        }

        @Test
        void removingSomethingAbsentChangesNothing() {
            RemoveItemNode node = new RemoveItemNode();
            Nodes.set(node, "List", List.of("a"));
            Nodes.set(node, "Item", "z");
            Nodes.run(node);

            assertEquals(List.of("a"), Nodes.list(node, "List"));
            assertEquals(0, Nodes.get(node, "Removed"));
        }
    }

    @Nested
    class Flatten {

        @Test
        void unpacksOneLevelByDefault() {
            FlattenListNode node = new FlattenListNode();
            Nodes.set(node, "List", List.of(List.of("a", "b"), List.of("c")));
            Nodes.run(node);

            assertEquals(List.of("a", "b", "c"), Nodes.list(node, "List"));
        }

        @Test
        void leavesDeeperNestingAloneAtDepthOne() {
            FlattenListNode node = new FlattenListNode();
            Nodes.set(node, "List", List.of(List.of(List.of("a"))));
            Nodes.run(node);

            assertEquals(List.of(List.of("a")), Nodes.list(node, "List"));
        }

        @Test
        void aDepthOfZeroGoesAllTheWayDown() {
            FlattenListNode node = new FlattenListNode();
            Nodes.set(node, "List", List.of(List.of(List.of("a", "b")), List.of("c")));
            Nodes.set(node, "Depth", 0);
            Nodes.run(node);

            assertEquals(List.of("a", "b", "c"), Nodes.list(node, "List"));
        }

        @Test
        void plainEntriesPassThroughUntouched() {
            FlattenListNode node = new FlattenListNode();
            Nodes.set(node, "List", List.of("a", List.of("b")));
            Nodes.run(node);

            assertEquals(List.of("a", "b"), Nodes.list(node, "List"));
        }
    }

    @Nested
    class Shuffle {

        @Test
        void keepsExactlyTheSameEntries() {
            ShuffleListNode node = new ShuffleListNode();
            Nodes.set(node, "List", List.of("a", "b", "c", "d"));
            Nodes.run(node);

            List<Object> shuffled = new ArrayList<>(Nodes.list(node, "List"));
            shuffled.sort(null);
            assertEquals(List.of("a", "b", "c", "d"), shuffled, "a shuffle reorders; it doesn't add or drop");
        }

        @Test
        void publishesSomethingNothingCanWriteTo() {
            ShuffleListNode node = new ShuffleListNode();
            Nodes.set(node, "List", List.of("a"));
            Nodes.run(node);

            assertThrows(UnsupportedOperationException.class, () -> Nodes.list(node, "List").add("b"));
        }
    }

    @Nested
    class RandomItem {

        @Test
        void picksAnEntryAndSaysWhereItCameFrom() {
            RandomItemNode node = new RandomItemNode();
            Nodes.set(node, "List", List.of("a", "b", "c"));
            Nodes.run(node);

            Object item = Nodes.get(node, "Item");
            int index = (int) Nodes.get(node, "Index");

            assertTrue(List.of("a", "b", "c").contains(item));
            assertEquals(List.of("a", "b", "c").get(index), item, "the index has to name the entry it returned");
            assertEquals(true, Nodes.get(node, "Found"));
        }

        @Test
        void anEmptyListReportsNothingFoundInsteadOfFailing() {
            RandomItemNode node = new RandomItemNode();
            Nodes.set(node, "List", List.of());
            Nodes.run(node);

            assertEquals(null, Nodes.get(node, "Item"));
            assertEquals(-1, Nodes.get(node, "Index"));
            assertEquals(false, Nodes.get(node, "Found"));
        }
    }
}
