package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The remaining map nodes, grouped for the same reason {@code ListInspectionNodesTest} and
 * {@code FilterNodesTest} are: each is a handful of assertions and they share one story with the
 * list nodes they sit beside — absent and empty are the same case, key lookup is forgiving about
 * types, and a question with a yes/no answer answers with a boolean.
 */
class MapNodesTest {

    @Nested
    class GetValue {

        @Test
        void returnsTheValueUnderAMatchingKey() {
            GetValueNode node = new GetValueNode();
            Nodes.set(node, "Map", Map.of("kind", "camera"));
            Nodes.set(node, "Key", "kind");

            Nodes.run(node);

            assertEquals("camera", Nodes.get(node, "Value"));
            assertEquals(true, Nodes.get(node, "Found"));
        }

        @Test
        void reportsAbsenceRatherThanFailing() {
            GetValueNode node = new GetValueNode();
            Nodes.set(node, "Map", Map.of("kind", "camera"));
            Nodes.set(node, "Key", "missing");

            Nodes.run(node);

            assertNull(Nodes.get(node, "Value"));
            assertEquals(false, Nodes.get(node, "Found"));
        }

        @Test
        void aTypedKeyFindsAnUpstreamNodesNumericKey() {
            GetValueNode node = new GetValueNode();
            Nodes.set(node, "Map", Map.of(3, "three"));
            Nodes.set(node, "Key", "3");

            Nodes.run(node);

            assertEquals("three", Nodes.get(node, "Value"));
        }
    }

    @Nested
    class ContainsKey {

        @Test
        void reportsTrueForAMatchingKeyEvenWithANullValue() {
            java.util.Map<String, Object> withNull = new java.util.LinkedHashMap<>();
            withNull.put("empty", null);

            MapContainsKeyNode node = new MapContainsKeyNode();
            Nodes.set(node, "Map", withNull);
            Nodes.set(node, "Key", "empty");

            Nodes.run(node);

            assertEquals(true, Nodes.get(node, "Found"), "presence is about the key, not the value");
        }

        @Test
        void reportsFalseForAnAbsentKey() {
            MapContainsKeyNode node = new MapContainsKeyNode();
            Nodes.set(node, "Map", Map.of("a", 1));
            Nodes.set(node, "Key", "z");

            Nodes.run(node);

            assertEquals(false, Nodes.get(node, "Found"));
        }
    }

    @Nested
    class Put {

        @Test
        void addsANewKey() {
            PutNode node = new PutNode();
            Nodes.set(node, "Map", Map.of("a", 1));
            Nodes.set(node, "Key", "b");
            Nodes.set(node, "Value", 2);

            Nodes.run(node);

            assertEquals(Map.of("a", 1, "b", 2), Nodes.map(node, "Map"));
        }

        @Test
        void updatesAMatchingKeyInPlaceRatherThanAppending() {
            Map<Object, Object> original = new java.util.LinkedHashMap<>();
            original.put("a", 1);
            original.put("b", 2);

            PutNode node = new PutNode();
            Nodes.set(node, "Map", original);
            Nodes.set(node, "Key", "a");
            Nodes.set(node, "Value", 99);

            Nodes.run(node);

            assertEquals(List.of("a", "b"), new java.util.ArrayList<>(Nodes.map(node, "Map").keySet()),
                    "an update keeps the key's original position");
            assertEquals(99, Nodes.map(node, "Map").get("a"));
        }

        @Test
        void aNullValueIsStoredRatherThanSkipped() {
            PutNode node = new PutNode();
            Nodes.set(node, "Key", "empty");

            Nodes.run(node);

            assertEquals(true, Nodes.map(node, "Map").containsKey("empty"));
            assertNull(Nodes.map(node, "Map").get("empty"));
        }
    }

    @Nested
    class RemoveKey {

        @Test
        void removesAMatchingKey() {
            RemoveKeyNode node = new RemoveKeyNode();
            Nodes.set(node, "Map", Map.of("a", 1, "b", 2));
            Nodes.set(node, "Key", "a");

            Nodes.run(node);

            assertEquals(Map.of("b", 2), Nodes.map(node, "Map"));
            assertEquals(true, Nodes.get(node, "Removed"));
        }

        @Test
        void leavesTheMapUnchangedWhenTheKeyWasNeverThere() {
            RemoveKeyNode node = new RemoveKeyNode();
            Nodes.set(node, "Map", Map.of("a", 1));
            Nodes.set(node, "Key", "z");

            Nodes.run(node);

            assertEquals(Map.of("a", 1), Nodes.map(node, "Map"));
            assertEquals(false, Nodes.get(node, "Removed"));
        }
    }

    @Nested
    class MergeMaps {

        @Test
        void combinesBothMapsWithTheSecondWinningOnConflicts() {
            MergeMapsNode node = new MergeMapsNode();
            Nodes.set(node, "First", Map.of("a", 1, "b", 2));
            Nodes.set(node, "Second", Map.of("b", 99, "c", 3));

            Nodes.run(node);

            assertEquals(Map.of("a", 1, "b", 99, "c", 3), Nodes.map(node, "Map"));
        }

        @Test
        void eitherSideUnwiredPassesTheOtherThrough() {
            MergeMapsNode onlyFirst = new MergeMapsNode();
            Nodes.set(onlyFirst, "First", Map.of("a", 1));
            Nodes.run(onlyFirst);
            assertEquals(Map.of("a", 1), Nodes.map(onlyFirst, "Map"));

            MergeMapsNode onlySecond = new MergeMapsNode();
            Nodes.set(onlySecond, "Second", Map.of("a", 1));
            Nodes.run(onlySecond);
            assertEquals(Map.of("a", 1), Nodes.map(onlySecond, "Map"));
        }
    }

    @Nested
    class KeysAndValues {

        @Test
        void reportKeysAndValuesInInsertionOrder() {
            Map<Object, Object> source = new java.util.LinkedHashMap<>();
            source.put("z", 1);
            source.put("a", 2);

            MapKeysNode keysNode = new MapKeysNode();
            Nodes.set(keysNode, "Map", source);
            Nodes.run(keysNode);
            assertEquals(List.of("z", "a"), Nodes.list(keysNode, "Keys"));

            MapValuesNode valuesNode = new MapValuesNode();
            Nodes.set(valuesNode, "Map", source);
            Nodes.run(valuesNode);
            assertEquals(List.of(1, 2), Nodes.list(valuesNode, "Values"));
        }
    }

    @Nested
    class Count {

        @Test
        void countsTheEntriesAndFlagsTheEmptyCase() {
            MapCountNode node = new MapCountNode();
            Nodes.set(node, "Map", Map.of("a", 1, "b", 2));

            Nodes.run(node);

            assertEquals(2, Nodes.get(node, "Count"));
            assertEquals(false, Nodes.get(node, "Is Empty"));
        }

        @Test
        void anUnwiredMapCountsAsEmptyRatherThanFailing() {
            MapCountNode node = new MapCountNode();

            Nodes.run(node);

            assertEquals(0, Nodes.get(node, "Count"));
            assertEquals(true, Nodes.get(node, "Is Empty"));
        }
    }

    @Nested
    class FromLists {

        @Test
        void zipsKeysAndValuesPairwise() {
            MapFromListsNode node = new MapFromListsNode();
            Nodes.set(node, "Keys", List.of("a", "b"));
            Nodes.set(node, "Values", List.of(1, 2));

            Nodes.run(node);

            assertEquals(Map.of("a", 1, "b", 2), Nodes.map(node, "Map"));
            assertEquals(2, Nodes.get(node, "Pairs"));
        }

        @Test
        void stopsAtTheShorterListRatherThanFailing() {
            MapFromListsNode node = new MapFromListsNode();
            Nodes.set(node, "Keys", List.of("a", "b", "c"));
            Nodes.set(node, "Values", List.of(1));

            Nodes.run(node);

            assertEquals(Map.of("a", 1), Nodes.map(node, "Map"));
            assertEquals(1, Nodes.get(node, "Pairs"));
        }

        @Test
        void aLaterPairsValueWinsWhenTwoKeysCollide() {
            MapFromListsNode node = new MapFromListsNode();
            Nodes.set(node, "Keys", List.of("a", "a"));
            Nodes.set(node, "Values", List.of(1, 2));

            Nodes.run(node);

            assertEquals(Map.of("a", 2), Nodes.map(node, "Map"));
        }
    }
}
