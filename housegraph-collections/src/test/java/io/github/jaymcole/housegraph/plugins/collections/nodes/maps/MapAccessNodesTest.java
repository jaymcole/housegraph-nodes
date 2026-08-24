package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The three nodes that reach into a map by key. Grouped because each is a handful of assertions
 * and they share one story: a missing key is an answer rather than a failure, the answer comes back
 * as a boolean for the host's <b>If (Boolean)</b> rather than as a flow branch, and lookup is
 * forgiving about how the key was spelled.
 */
class MapAccessNodesTest {

    private static Map<String, Object> cameras() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("front", "porch");
        map.put("3", "driveway");
        return map;
    }

    @Nested
    class Get {

        @Test
        void findsAValueAndSaysSo() {
            MapGetNode node = new MapGetNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "front");
            Nodes.run(node);

            assertEquals("porch", Nodes.get(node, "Value"));
            assertEquals(true, Nodes.get(node, "Found"));
        }

        @Test
        void findsAnEntryStoredUnderANumberThroughTypedText() {
            MapGetNode node = new MapGetNode();
            Map<Object, Object> keyedByNumber = new LinkedHashMap<>();
            keyedByNumber.put(3, "driveway");
            Nodes.set(node, "Map", keyedByNumber);
            Nodes.set(node, "Key", "3");
            Nodes.run(node);

            assertEquals("driveway", Nodes.get(node, "Value"),
                    "a Key field is text, so this is the only way to reach a numeric key at all");
            assertEquals(true, Nodes.get(node, "Found"));
        }

        @Test
        void aMissingKeyIsAnAnswerRatherThanAnError() {
            MapGetNode node = new MapGetNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "back");
            Nodes.run(node);

            assertNull(Nodes.get(node, "Value"));
            assertEquals(false, Nodes.get(node, "Found"));
        }

        @Test
        void aDefaultFillsInForAMissingKeyWithoutClaimingItWasFound() {
            MapGetNode node = new MapGetNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "back");
            Nodes.set(node, "Default", "unknown");
            Nodes.run(node);

            assertEquals("unknown", Nodes.get(node, "Value"));
            assertEquals(false, Nodes.get(node, "Found"),
                    "Found reports the lookup, not whether something came out of the port");
        }

        @Test
        void aDefaultDoesNotDisplaceAValueThatWasThere() {
            MapGetNode node = new MapGetNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "front");
            Nodes.set(node, "Default", "unknown");
            Nodes.run(node);

            assertEquals("porch", Nodes.get(node, "Value"));
        }

        @Test
        void anUnwiredMapOrBlankKeyFindsNothingRatherThanFailing() {
            MapGetNode node = new MapGetNode();
            Nodes.run(node);

            assertEquals(false, Nodes.get(node, "Found"));

            MapGetNode blank = new MapGetNode();
            Nodes.set(blank, "Map", cameras());
            Nodes.set(blank, "Key", "   ");
            Nodes.run(blank);

            assertEquals(false, Nodes.get(blank, "Found"), "an unfilled Key field is not the blank key");
        }

        @Test
        void publishesItsContentsUnderTheNamesTheGraphSavesThemBy() {
            MapGetNode node = new MapGetNode();

            assertEquals(List.of("Map", "Key", "Default"), Nodes.inputNames(node));
            assertEquals(List.of("Value", "Found"), Nodes.outputNames(node));
        }
    }

    @Nested
    class Put {

        @Test
        void addsANewEntryOnTheEnd() {
            MapPutNode node = new MapPutNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "back");
            Nodes.set(node, "Value", "gate");
            Nodes.run(node);

            assertEquals(List.of("front", "3", "back"), List.copyOf(Nodes.map(node, "Map").keySet()));
            assertEquals(false, Nodes.get(node, "Replaced"));
        }

        @Test
        void replacingKeepsThePositionAndSaysItReplaced() {
            MapPutNode node = new MapPutNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "front");
            Nodes.set(node, "Value", "hallway");
            Nodes.run(node);

            Map<String, Object> result = Nodes.map(node, "Map");
            assertEquals("hallway", result.get("front"));
            assertEquals(List.of("front", "3"), List.copyOf(result.keySet()));
            assertEquals(true, Nodes.get(node, "Replaced"));
        }

        @Test
        void aHalfFilledPairLeavesTheMapAsItWas() {
            MapPutNode node = new MapPutNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "back");
            Nodes.run(node);

            assertEquals(cameras(), Nodes.map(node, "Map"), "a key with no value is not an entry");
            assertEquals(false, Nodes.get(node, "Replaced"));
        }

        @Test
        void leavesTheMapItWasGivenAlone() {
            MapPutNode node = new MapPutNode();
            Map<String, Object> source = cameras();
            Nodes.set(node, "Map", source);
            Nodes.set(node, "Key", "back");
            Nodes.set(node, "Value", "gate");
            Nodes.run(node);

            assertEquals(cameras(), source, "nothing in this library edits a collection it was handed");
        }

        @Test
        void anUnwiredMapStartsANewOne() {
            MapPutNode node = new MapPutNode();
            Nodes.set(node, "Key", "front");
            Nodes.set(node, "Value", "porch");
            Nodes.run(node);

            assertEquals(Map.of("front", "porch"), Nodes.map(node, "Map"));
        }
    }

    @Nested
    class Remove {

        @Test
        void takesTheEntryOutAndHandsBackWhatWasThere() {
            MapRemoveNode node = new MapRemoveNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "front");
            Nodes.run(node);

            assertEquals(Map.of("3", "driveway"), Nodes.map(node, "Map"));
            assertEquals("porch", Nodes.get(node, "Value"));
            assertEquals(true, Nodes.get(node, "Removed"));
        }

        @Test
        void aMissingKeyLeavesTheMapAsItWas() {
            MapRemoveNode node = new MapRemoveNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Key", "back");
            Nodes.run(node);

            assertEquals(cameras(), Nodes.map(node, "Map"));
            assertNull(Nodes.get(node, "Value"));
            assertEquals(false, Nodes.get(node, "Removed"));
        }

        @Test
        void theEntriesThatStayKeepTheirOrder() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("a", 1);
            map.put("b", 2);
            map.put("c", 3);

            MapRemoveNode node = new MapRemoveNode();
            Nodes.set(node, "Map", map);
            Nodes.set(node, "Key", "b");
            Nodes.run(node);

            assertEquals(List.of("a", "c"), List.copyOf(Nodes.map(node, "Map").keySet()));
        }
    }
}
