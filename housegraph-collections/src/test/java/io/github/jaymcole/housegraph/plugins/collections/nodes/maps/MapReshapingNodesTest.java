package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The nodes that turn a map into something else — two lists, one string, or one bigger map.
 */
class MapReshapingNodesTest {

    private static Map<String, Object> cameras() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("front", "porch");
        map.put("back", "gate");
        return map;
    }

    @Nested
    class Entries {

        @Test
        void handsBackTheKeysAndValuesAsTwoListsThatLineUp() {
            MapEntriesNode node = new MapEntriesNode();
            Nodes.set(node, "Map", cameras());
            Nodes.run(node);

            List<Object> keys = Nodes.list(node, "Keys");
            List<Object> values = Nodes.list(node, "Values");

            assertEquals(List.of("front", "back"), keys);
            assertEquals(List.of("porch", "gate"), values);
            for (int i = 0; i < keys.size(); i++) {
                assertEquals(cameras().get(keys.get(i)), values.get(i),
                        "Keys[i] has to be the key of Values[i] - that guarantee is why this is one node");
            }
        }

        @Test
        void countsAndFlagsTheEmptyCase() {
            MapEntriesNode node = new MapEntriesNode();
            Nodes.set(node, "Map", cameras());
            Nodes.run(node);

            assertEquals(2, Nodes.get(node, "Count"));
            assertEquals(false, Nodes.get(node, "Is Empty"));
        }

        @Test
        void anUnwiredMapReadsAsEmptyRatherThanFailing() {
            MapEntriesNode node = new MapEntriesNode();
            Nodes.run(node);

            assertEquals(List.of(), Nodes.list(node, "Keys"));
            assertEquals(List.of(), Nodes.list(node, "Values"));
            assertEquals(0, Nodes.get(node, "Count"));
            assertEquals(true, Nodes.get(node, "Is Empty"));
        }

        @Test
        void keysComeOutAsTextAndValuesAsWhateverTheyWere() {
            Map<Object, Object> source = new LinkedHashMap<>();
            source.put(3, 42);

            MapEntriesNode node = new MapEntriesNode();
            Nodes.set(node, "Map", source);
            Nodes.run(node);

            assertEquals(List.of("3"), Nodes.list(node, "Keys"));
            assertEquals(List.of(42), Nodes.list(node, "Values"),
                    "only keys are normalised; a value has to stay a number for List Statistics");
        }

        @Test
        void publishesItsContentsUnderTheNamesTheGraphSavesThemBy() {
            MapEntriesNode node = new MapEntriesNode();

            assertEquals(List.of("Map"), Nodes.inputNames(node));
            assertEquals(List.of("Keys", "Values", "Count", "Is Empty"), Nodes.outputNames(node));
        }
    }

    @Nested
    class Merge {

        private static MergeMapsNode merge(Map<String, Object> base, Map<String, Object> overrides) {
            MergeMapsNode node = new MergeMapsNode();
            Nodes.set(node, "Base", base);
            Nodes.set(node, "Overrides", overrides);
            Nodes.run(node);
            return node;
        }

        @Test
        void overridesWinsOnASharedKey() {
            MergeMapsNode node = merge(cameras(), Map.of("front", "hallway"));

            assertEquals("hallway", Nodes.map(node, "Map").get("front"));
            assertEquals(1, Nodes.get(node, "Overridden"));
        }

        @Test
        void anOverridingEntryKeepsBasesPosition() {
            MergeMapsNode node = merge(cameras(), Map.of("front", "hallway"));

            assertEquals(List.of("front", "back"), List.copyOf(Nodes.map(node, "Map").keySet()),
                    "the merged map should read in the order the defaults were laid out");
        }

        @Test
        void whatOverridesAddsGoesOnTheEnd() {
            MergeMapsNode node = merge(cameras(), Map.of("side", "path"));

            assertEquals(List.of("front", "back", "side"), List.copyOf(Nodes.map(node, "Map").keySet()));
            assertEquals(0, Nodes.get(node, "Overridden"));
        }

        @Test
        void mergingWithNothingIsJustTheOtherMap() {
            assertEquals(cameras(), Nodes.map(merge(cameras(), null), "Map"));
            assertEquals(cameras(), Nodes.map(merge(null, cameras()), "Map"));
            assertEquals(Map.of(), Nodes.map(merge(null, null), "Map"));
        }
    }

    @Nested
    class Join {

        @Test
        void rendersEachEntryThroughTheTemplate() {
            JoinMapNode node = new JoinMapNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Template", "{key} = {value}");
            Nodes.set(node, "Separator", ", ");
            Nodes.run(node);

            assertEquals("front = porch, back = gate", Nodes.get(node, "Text"));
        }

        @Test
        void resolvesEscapesInTheSeparatorAndTemplate() {
            JoinMapNode node = new JoinMapNode();
            Nodes.set(node, "Map", cameras());
            Nodes.set(node, "Template", "{key}:\\t{value}");
            Nodes.set(node, "Separator", "\\n");
            Nodes.run(node);

            assertEquals("front:\tporch\nback:\tgate", Nodes.get(node, "Text"),
                    "a text field is the only way to author a newline, so it has to mean one");
        }

        @Test
        void wrapsTheWholeThingInAPrefixAndSuffix() {
            JoinMapNode node = new JoinMapNode();
            Nodes.set(node, "Map", Map.of("front", "porch"));
            Nodes.set(node, "Template", "{key}");
            Nodes.set(node, "Prefix", "[");
            Nodes.set(node, "Suffix", "]");
            Nodes.run(node);

            assertEquals("[front]", Nodes.get(node, "Text"));
        }

        @Test
        void anEmptyMapJoinsToAnEmptyString() {
            JoinMapNode node = new JoinMapNode();
            Nodes.set(node, "Template", "{key}");
            Nodes.set(node, "Prefix", "[");
            Nodes.set(node, "Suffix", "]");
            Nodes.run(node);

            assertEquals("", Nodes.get(node, "Text"), "prefix and suffix included - there is nothing to wrap");
        }

        @Test
        void opensWithATemplateAndSeparatorAlreadyUsable() {
            JoinMapNode node = new JoinMapNode();
            Nodes.set(node, "Map", cameras());
            Nodes.run(node);

            assertEquals("front: porch\nback: gate", Nodes.get(node, "Text"),
                    "dropping this node on the canvas and wiring one input should already produce something");
        }
    }
}
