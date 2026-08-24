package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The nodes that answer a question about a list without producing another one. Grouped because
 * each is a handful of assertions and they share one story: absent and empty are the same case,
 * matching is forgiving about types, and a question with a yes/no answer answers with a boolean
 * rather than a flow branch.
 */
class ListInspectionNodesTest {

    @Nested
    class Count {

        @Test
        void countsTheEntriesAndFlagsTheEmptyCase() {
            ListCountNode node = new ListCountNode();
            Nodes.set(node, "List", List.of("a", "b"));
            Nodes.run(node);

            assertEquals(2, Nodes.get(node, "Count"));
            assertEquals(false, Nodes.get(node, "Is Empty"));
        }

        @Test
        void anUnwiredListCountsAsEmptyRatherThanFailing() {
            ListCountNode node = new ListCountNode();
            Nodes.run(node);

            assertEquals(0, Nodes.get(node, "Count"));
            assertEquals(true, Nodes.get(node, "Is Empty"));
        }
    }

    @Nested
    class Contains {

        private static ListContainsNode contains(List<Object> entries, String item) {
            ListContainsNode node = new ListContainsNode();
            Nodes.set(node, "List", entries);
            Nodes.set(node, "Item", item);
            Nodes.run(node);
            return node;
        }

        @Test
        void findsAnEntryAndCountsHowManyTimesItAppears() {
            ListContainsNode node = contains(List.of("a", "b", "a"), "a");

            assertEquals(true, Nodes.get(node, "Found"));
            assertEquals(2, Nodes.get(node, "Occurrences"));
        }

        @Test
        void reportsTheAbsenceRatherThanFailing() {
            ListContainsNode node = contains(List.of("a"), "z");

            assertEquals(false, Nodes.get(node, "Found"));
            assertEquals(0, Nodes.get(node, "Occurrences"));
        }

        @Test
        void aTypedItemFindsAnUpstreamNodesNumber() {
            ListContainsNode node = contains(List.of(1, 2, 3), "3");

            assertEquals(true, Nodes.get(node, "Found"),
                    "erasure means a typed item would otherwise never match a numeric list");
        }

        @Test
        void matchingIsExactAboutCase() {
            assertEquals(false, Nodes.get(contains(List.of("Kitchen"), "kitchen"), "Found"),
                    "only the filters are case-insensitive; a membership test is not");
        }
    }

    @Nested
    class IndexOf {

        private static IndexOfNode indexOf(List<Object> entries, String item) {
            IndexOfNode node = new IndexOfNode();
            Nodes.set(node, "List", entries);
            Nodes.set(node, "Item", item);
            Nodes.run(node);
            return node;
        }

        @Test
        void reportsThePositionOfTheFirstMatch() {
            IndexOfNode node = indexOf(List.of("a", "b", "b"), "b");

            assertEquals(1, Nodes.get(node, "Index"));
            assertEquals(true, Nodes.get(node, "Found"));
        }

        @Test
        void reportsMinusOneAndAFalseFoundWhenItIsntThere() {
            IndexOfNode node = indexOf(List.of("a"), "z");

            assertEquals(-1, Nodes.get(node, "Index"));
            assertEquals(false, Nodes.get(node, "Found"),
                    "Found exists so nothing downstream has to know -1 is the sentinel");
        }
    }

    @Nested
    class Join {

        private static JoinListNode join(List<Object> entries, String separator, String prefix, String suffix) {
            JoinListNode node = new JoinListNode();
            Nodes.set(node, "List", entries);
            Nodes.set(node, "Separator", separator);
            Nodes.set(node, "Prefix", prefix);
            Nodes.set(node, "Suffix", suffix);
            Nodes.run(node);
            return node;
        }

        @Test
        void joinsWithTheSeparatorAndWrapsInPrefixAndSuffix() {
            JoinListNode node = join(List.of("a", "b"), ", ", "[", "]");

            assertEquals("[a, b]", Nodes.get(node, "Text"));
        }

        @Test
        void defaultsToACommaAndSpace() {
            JoinListNode node = new JoinListNode();
            Nodes.set(node, "List", List.of("a", "b"));
            Nodes.run(node);

            assertEquals("a, b", Nodes.get(node, "Text"));
        }

        @Test
        void anEscapedNewlineInTheFieldBecomesARealOne() {
            JoinListNode node = join(List.of("a", "b"), "\\n- ", "- ", "");

            assertEquals("- a\n- b", Nodes.get(node, "Text"),
                    "a text field is the only way to author a newline");
        }

        @Test
        void anEmptyListJoinsToAnEmptyStringWithoutTheWrapping() {
            JoinListNode node = join(List.of(), ", ", "[", "]");

            assertEquals("", Nodes.get(node, "Text"));
        }

        @Test
        void entriesThatArentStringsAreRenderedAsText() {
            JoinListNode node = join(List.of(1, 2.5, true), " ", "", "");

            assertEquals("1 2.5 true", Nodes.get(node, "Text"));
        }
    }
}
