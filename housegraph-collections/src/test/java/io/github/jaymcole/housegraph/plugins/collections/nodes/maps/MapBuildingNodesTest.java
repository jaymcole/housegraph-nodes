package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four ways a map gets made here — from wired pairs, from two lists, from counting a list, and
 * across the firings of a loop. Each is tested for the same two things: that the ordinary case
 * lands, and that the ways a half-built graph can feed it nonsense produce a smaller map rather
 * than a failed node.
 */
class MapBuildingNodesTest {

    @Nested
    class Build {

        @Test
        void collectsTheFilledPairsAndCountsThem() {
            BuildMapNode node = new BuildMapNode();
            Nodes.set(node, "Key 1", "front");
            Nodes.set(node, "Value 1", "porch");
            Nodes.set(node, "Key 2", "back");
            Nodes.set(node, "Value 2", "gate");
            Nodes.run(node);

            assertEquals(Map.of("front", "porch", "back", "gate"), Nodes.map(node, "Map"));
            assertEquals(2, Nodes.get(node, "Count"));
        }

        @Test
        void keepsThePairsInPortOrder() {
            BuildMapNode node = new BuildMapNode();
            Nodes.set(node, "Key 1", "z");
            Nodes.set(node, "Value 1", 1);
            Nodes.set(node, "Key 2", "a");
            Nodes.set(node, "Value 2", 2);
            Nodes.run(node);

            assertEquals(List.of("z", "a"), List.copyOf(Nodes.map(node, "Map").keySet()));
        }

        @Test
        void aHalfFilledPairContributesNothing() {
            BuildMapNode node = new BuildMapNode();
            Nodes.set(node, "Key 1", "front");
            Nodes.set(node, "Value 1", "porch");
            Nodes.set(node, "Key 2", "typed but never wired");
            Nodes.run(node);

            assertEquals(Map.of("front", "porch"), Nodes.map(node, "Map"));
            assertEquals(1, Nodes.get(node, "Count"),
                    "Count reports what landed, not how many slots someone touched");
        }

        @Test
        void anUntouchedNodeBuildsAnEmptyMapRatherThanFailing() {
            BuildMapNode node = new BuildMapNode();
            Nodes.run(node);

            assertEquals(Map.of(), Nodes.map(node, "Map"));
            assertEquals(0, Nodes.get(node, "Count"));
        }

        @Test
        void aRepeatedKeyKeepsTheLastValue() {
            BuildMapNode node = new BuildMapNode();
            Nodes.set(node, "Key 1", "front");
            Nodes.set(node, "Value 1", "porch");
            Nodes.set(node, "Key 2", "front");
            Nodes.set(node, "Value 2", "hallway");
            Nodes.run(node);

            assertEquals(Map.of("front", "hallway"), Nodes.map(node, "Map"));
            assertEquals(1, Nodes.get(node, "Count"));
        }

        @Test
        void opensWithOnePairToFillAndOneSpare() {
            BuildMapNode node = new BuildMapNode();

            assertEquals(List.of("Key 1", "Value 1", "Key 2", "Value 2"), Nodes.inputNames(node));
            assertEquals(List.of("Map", "Count"), Nodes.outputNames(node));
        }

        @Test
        void writesNoStateWhileItStillHasItsDefaultShape() {
            assertTrue(new BuildMapNode().saveState().isEmpty(),
                    "a node at its default size has nothing worth persisting");
        }

        @Test
        void restoresItsPortsFromSavedState() {
            BuildMapNode node = new BuildMapNode();

            node.loadState(Map.of("pairs", "3"));

            assertEquals(
                    List.of("Key 1", "Value 1", "Key 2", "Value 2", "Key 3", "Value 3"),
                    Nodes.inputNames(node),
                    "the ports have to exist again before a load restores edges onto them");
            assertEquals(Map.of("pairs", "3"), node.saveState(), "what it loaded is what it saves again");
        }

        @Test
        void unreadableOrOutOfRangeStateFallsBackToSomethingUsable() {
            BuildMapNode nonsense = new BuildMapNode();
            nonsense.loadState(Map.of("pairs", "not a number"));
            assertEquals(4, Nodes.inputNames(nonsense).size());

            BuildMapNode tiny = new BuildMapNode();
            tiny.loadState(Map.of("pairs", "0"));
            assertEquals(4, Nodes.inputNames(tiny).size(), "a node with no ports could never be re-wired");

            BuildMapNode huge = new BuildMapNode();
            huge.loadState(Map.of("pairs", "5000"));
            assertEquals(128, Nodes.inputNames(huge).size(), "growth is clamped, so a bad save can't run away");
        }

        @Test
        void isAPureDataNodeWithNoFlowPorts() {
            BuildMapNode node = new BuildMapNode();

            assertTrue(node.getFlowInputs().isEmpty());
            assertTrue(node.getFlowOutputs().isEmpty());
        }
    }

    @Nested
    class FromLists {

        private static MapFromListsNode zip(List<Object> keys, List<Object> values) {
            MapFromListsNode node = new MapFromListsNode();
            Nodes.set(node, "Keys", keys);
            Nodes.set(node, "Values", values);
            Nodes.run(node);
            return node;
        }

        @Test
        void pairsTheTwoListsByPosition() {
            MapFromListsNode node = zip(List.of("front", "back"), List.of("porch", "gate"));

            assertEquals(Map.of("front", "porch", "back", "gate"), Nodes.map(node, "Map"));
            assertEquals(0, Nodes.get(node, "Dropped"));
        }

        @Test
        void isTheInverseOfMapEntries() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("front", "porch");
            original.put("back", "gate");

            MapEntriesNode apart = new MapEntriesNode();
            Nodes.set(apart, "Map", original);
            Nodes.run(apart);

            MapFromListsNode together = zip(Nodes.list(apart, "Keys"), Nodes.list(apart, "Values"));

            assertEquals(original, Nodes.map(together, "Map"));
            assertEquals(List.copyOf(original.keySet()), List.copyOf(Nodes.map(together, "Map").keySet()),
                    "the round trip has to preserve order too, or a rendered map moves about");
        }

        @Test
        void listsOfDifferentLengthsStopAtTheShorterAndSayHowManyWereLeft() {
            MapFromListsNode node = zip(List.of("a", "b", "c"), List.of(1));

            assertEquals(Map.of("a", 1), Nodes.map(node, "Map"));
            assertEquals(2, Nodes.get(node, "Dropped"));
        }

        @Test
        void aPairThatCouldNotBeStoredCountsAsDropped() {
            MapFromListsNode node = zip(List.of("a", "  "), Arrays.asList(1, 2));

            assertEquals(Map.of("a", 1), Nodes.map(node, "Map"));
            assertEquals(1, Nodes.get(node, "Dropped"), "a blank key is not a key, and the count has to say so");
        }

        @Test
        void unwiredListsMakeAnEmptyMapRatherThanFailing() {
            MapFromListsNode node = new MapFromListsNode();
            Nodes.run(node);

            assertEquals(Map.of(), Nodes.map(node, "Map"));
            assertEquals(0, Nodes.get(node, "Dropped"));
        }
    }

    @Nested
    class Tally {

        @Test
        void countsHowOftenEachEntryAppears() {
            TallyNode node = new TallyNode();
            Nodes.set(node, "List", List.of("front", "back", "front", "front"));
            Nodes.run(node);

            assertEquals(Map.of("front", 3, "back", 1), Nodes.map(node, "Counts"));
            assertEquals(2, Nodes.get(node, "Distinct Count"));
        }

        @Test
        void keysAppearInTheOrderEachWasFirstSeen() {
            TallyNode node = new TallyNode();
            Nodes.set(node, "List", List.of("z", "a", "z"));
            Nodes.run(node);

            assertEquals(List.of("z", "a"), List.copyOf(Nodes.map(node, "Counts").keySet()));
        }

        @Test
        void twoSpellingsOfTheSameValueAreOneTally() {
            TallyNode node = new TallyNode();
            Nodes.set(node, "List", List.of(3, "3"));
            Nodes.run(node);

            assertEquals(Map.of("3", 2), Nodes.map(node, "Counts"),
                    "nothing downstream could tell these apart, so counting them apart would be a lie");
        }

        @Test
        void nullAndBlankEntriesAreSkippedRatherThanTalliedUnderNothing() {
            TallyNode node = new TallyNode();
            Nodes.set(node, "List", Arrays.asList("front", null, "", "   "));
            Nodes.run(node);

            assertEquals(Map.of("front", 1), Nodes.map(node, "Counts"));
        }

        @Test
        void anUnwiredListTalliesNothingRatherThanFailing() {
            TallyNode node = new TallyNode();
            Nodes.run(node);

            assertEquals(Map.of(), Nodes.map(node, "Counts"));
            assertEquals(0, Nodes.get(node, "Distinct Count"));
        }
    }
}
