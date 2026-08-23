package io.github.jaymcole.housegraph.plugins.collections.nodes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three predicate-parameter filters — the shape this library uses instead of a callback-driven
 * filter, which would have been a control node (see the package documentation). Their Kept /
 * Removed / Skipped counts are part of the contract, not decoration: they're how a graph tells
 * "nothing matched" apart from "nothing was readable".
 */
class FilterNodesTest {

    @Nested
    class ByText {

        private static final List<Object> CAMERAS = List.of("Front Door", "Back Door", "Driveway");

        private static FilterByTextNode filter(String text, String mode) {
            FilterByTextNode node = new FilterByTextNode();
            Nodes.set(node, "List", CAMERAS);
            Nodes.set(node, "Text", text);
            if (mode != null) {
                Nodes.set(node, "Mode", mode);
            }
            Nodes.run(node);
            return node;
        }

        @Test
        void keepsTheEntriesContainingTheTextByDefault() {
            FilterByTextNode node = filter("door", null);

            assertEquals(List.of("Front Door", "Back Door"), Nodes.list(node, "List"));
            assertEquals(2, Nodes.get(node, "Kept"));
            assertEquals(1, Nodes.get(node, "Removed"));
        }

        @Test
        void matchingIgnoresCase() {
            assertEquals(2, Nodes.list(filter("DOOR", "contains"), "List").size());
        }

        @Test
        void eachModeSelectsWhatItSays() {
            assertEquals(List.of("Driveway"), Nodes.list(filter("door", "not contains"), "List"));
            assertEquals(List.of("Front Door"), Nodes.list(filter("front", "starts with"), "List"));
            assertEquals(List.of("Front Door", "Back Door"), Nodes.list(filter("door", "ends with"), "List"));
            assertEquals(List.of("Driveway"), Nodes.list(filter("driveway", "equals"), "List"));
        }

        @Test
        void worksOnEntriesThatArentStringsAtAll() {
            FilterByTextNode node = new FilterByTextNode();
            Nodes.set(node, "List", List.of(12, 25, 21));
            Nodes.set(node, "Text", "2");
            Nodes.set(node, "Mode", "starts with");
            Nodes.run(node);

            assertEquals(List.of(25, 21), Nodes.list(node, "List"),
                    "entries compare by their text form, so a list of anything works");
        }

        @Test
        void anUnknownModeFailsTheNodeRatherThanFilteringWrongly() {
            FilterByTextNode node = new FilterByTextNode();
            Nodes.set(node, "List", CAMERAS);
            Nodes.set(node, "Mode", "sort of like");

            assertThrows(IllegalArgumentException.class, () -> Nodes.run(node));
        }
    }

    @Nested
    class ByPattern {

        private static FilterByPatternNode filter(List<Object> entries, String pattern) {
            FilterByPatternNode node = new FilterByPatternNode();
            Nodes.set(node, "List", entries);
            Nodes.set(node, "Pattern", pattern);
            Nodes.run(node);
            return node;
        }

        @Test
        void keepsTheEntriesThePatternOccursIn() {
            FilterByPatternNode node = filter(List.of("motion.jpg", "notes.txt", "cat.jpg"), "\\.jpg$");

            assertEquals(List.of("motion.jpg", "cat.jpg"), Nodes.list(node, "List"));
            assertEquals(2, Nodes.get(node, "Kept"));
            assertEquals(1, Nodes.get(node, "Removed"));
        }

        @Test
        void isCaseSensitiveUnlessThePatternSaysOtherwise() {
            assertEquals(List.of(), Nodes.list(filter(List.of("Motion.JPG"), "\\.jpg$"), "List"));
            assertEquals(List.of("Motion.JPG"), Nodes.list(filter(List.of("Motion.JPG"), "(?i)\\.jpg$"), "List"));
        }

        @Test
        void searchesRatherThanAnchoringSoAPlainWordWorks() {
            assertEquals(List.of("front door"), Nodes.list(filter(List.of("front door", "driveway"), "door"), "List"));
        }

        @Test
        void aBrokenPatternFailsTheNodeInsteadOfQuietlyMatchingNothing() {
            FilterByPatternNode node = new FilterByPatternNode();
            Nodes.set(node, "List", List.of("a"));
            Nodes.set(node, "Pattern", "[unclosed");

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> Nodes.run(node));
            assertTrue(thrown.getMessage().startsWith("Filter by Pattern can't read the pattern"));
        }
    }

    @Nested
    class ByNumber {

        private static FilterByNumberNode filter(List<Object> entries, String comparison, float value) {
            FilterByNumberNode node = new FilterByNumberNode();
            Nodes.set(node, "List", entries);
            Nodes.set(node, "Comparison", comparison);
            Nodes.set(node, "Value", value);
            Nodes.run(node);
            return node;
        }

        @Test
        void keepsTheEntriesOnTheRightSideOfTheThreshold() {
            FilterByNumberNode node = filter(List.of(18, 21, 25), ">", 20f);

            assertEquals(List.of(21, 25), Nodes.list(node, "List"));
            assertEquals(2, Nodes.get(node, "Kept"));
        }

        @Test
        void readsNumbersThatArrivedAsText() {
            assertEquals(List.of("21", "25"), Nodes.list(filter(List.of("18", "21", "25"), ">", 20f), "List"),
                    "a split line of text is still a list of numbers");
        }

        @Test
        void dropsUnreadableEntriesAndSaysHowMany() {
            FilterByNumberNode node = filter(Arrays.asList(21, "warm", null, 25), ">", 20f);

            assertEquals(List.of(21, 25), Nodes.list(node, "List"));
            assertEquals(2, Nodes.get(node, "Skipped"),
                    "an entirely non-numeric list has to be distinguishable from one that simply didn't match");
        }

        @Test
        void everyComparisonIsAvailableThroughTheTextField() {
            assertEquals(List.of(18), Nodes.list(filter(List.of(18, 21), "<", 20f), "List"));
            assertEquals(List.of(21), Nodes.list(filter(List.of(18, 21), "==", 21f), "List"));
            assertEquals(List.of(18), Nodes.list(filter(List.of(18, 21), "!=", 21f), "List"));
        }
    }
}
