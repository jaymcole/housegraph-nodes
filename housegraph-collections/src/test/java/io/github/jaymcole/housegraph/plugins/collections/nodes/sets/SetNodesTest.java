package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.plugins.collections.Sets;
import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every node in the {@code sets} package. They are grouped into one file because each is a handful
 * of assertions over one shared story: a set here is an ordered, forgiving collection, absent and
 * empty are the same case, and the interesting behaviour is at the doors in and out of the package
 * rather than inside any one node.
 */
class SetNodesTest {

    /** The set an input would be handed, built the way every node in the package builds one. */
    private static Set<Object> setOf(Object... members) {
        return Sets.copyOf(Arrays.asList(members));
    }

    @Nested
    class In {

        @Test
        void dropsRepeatsAndSaysHowManyWent() {
            ToSetNode node = new ToSetNode();
            Nodes.set(node, "List", List.of("front", "back", "front"));
            Nodes.run(node);

            assertEquals(List.of("front", "back"), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(1, Nodes.get(node, "Removed"));
        }

        @Test
        void twoSpellingsOfTheSameValueAreOneMember() {
            ToSetNode node = new ToSetNode();
            Nodes.set(node, "List", List.of(3, "3"));
            Nodes.run(node);

            assertEquals(List.of(3), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(1, Nodes.get(node, "Removed"));
        }

        @Test
        void anUnwiredListMakesAnEmptySetRatherThanFailing() {
            ToSetNode node = new ToSetNode();
            Nodes.run(node);

            assertEquals(Set.of(), Nodes.set(node, "Set"));
            assertEquals(0, Nodes.get(node, "Removed"));
        }
    }

    @Nested
    class Out {

        @Test
        void handsBackTheMembersInInsertionOrderWithACount() {
            SetToListNode node = new SetToListNode();
            Nodes.set(node, "Set", setOf("z", "a", "m"));
            Nodes.run(node);

            assertEquals(List.of("z", "a", "m"), Nodes.list(node, "List"),
                    "For Each needs a list, and it needs to arrive in the order it went in");
            assertEquals(3, Nodes.get(node, "Count"));
            assertEquals(false, Nodes.get(node, "Is Empty"));
        }

        @Test
        void anUnwiredSetReadsAsEmptyRatherThanFailing() {
            SetToListNode node = new SetToListNode();
            Nodes.run(node);

            assertEquals(List.of(), Nodes.list(node, "List"));
            assertEquals(0, Nodes.get(node, "Count"));
            assertEquals(true, Nodes.get(node, "Is Empty"));
        }

        @Test
        void membersKeepTheirTypeOnTheWayOut() {
            SetToListNode node = new SetToListNode();
            Nodes.set(node, "Set", setOf(1, 2));
            Nodes.run(node);

            assertEquals(List.of(1, 2), Nodes.list(node, "List"),
                    "a set of numbers has to still be numbers for List Statistics downstream");
        }

        @Test
        void roundTripsThroughToSetUnchanged() {
            ToSetNode in = new ToSetNode();
            Nodes.set(in, "List", List.of("front", "back"));
            Nodes.run(in);

            SetToListNode out = new SetToListNode();
            Nodes.set(out, "Set", Nodes.set(in, "Set"));
            Nodes.run(out);

            assertEquals(List.of("front", "back"), Nodes.list(out, "List"));
        }
    }

    @Nested
    class Asking {

        @Test
        void findsAMemberAndMissesOneThatIsntThere() {
            SetContainsNode found = new SetContainsNode();
            Nodes.set(found, "Set", setOf("front"));
            Nodes.set(found, "Item", "front");
            Nodes.run(found);
            assertEquals(true, Nodes.get(found, "Found"));

            SetContainsNode missing = new SetContainsNode();
            Nodes.set(missing, "Set", setOf("front"));
            Nodes.set(missing, "Item", "back");
            Nodes.run(missing);
            assertEquals(false, Nodes.get(missing, "Found"));
        }

        @Test
        void findsANumberThroughTypedText() {
            SetContainsNode node = new SetContainsNode();
            Nodes.set(node, "Set", setOf(3));
            Nodes.set(node, "Item", "3");
            Nodes.run(node);

            assertEquals(true, Nodes.get(node, "Found"),
                    "the Item field is text, so this is the only way to ask about a number at all");
        }

        @Test
        void anUnwiredSetHoldsNothing() {
            SetContainsNode node = new SetContainsNode();
            Nodes.set(node, "Item", "front");
            Nodes.run(node);

            assertEquals(false, Nodes.get(node, "Found"));
        }
    }

    @Nested
    class Changing {

        @Test
        void addingSomethingNewGrowsTheSetAndSaysSo() {
            SetAddNode node = new SetAddNode();
            Nodes.set(node, "Set", setOf("front"));
            Nodes.set(node, "Item", "back");
            Nodes.run(node);

            assertEquals(List.of("front", "back"), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(true, Nodes.get(node, "Added"));
        }

        @Test
        void addingSomethingAlreadyThereChangesNothing() {
            SetAddNode node = new SetAddNode();
            Nodes.set(node, "Set", setOf(3));
            Nodes.set(node, "Item", "3");
            Nodes.run(node);

            assertEquals(List.of(3), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(false, Nodes.get(node, "Added"), "\"was this new?\" is the question a set is asked");
        }

        @Test
        void addingToAnUnwiredSetStartsANewOne() {
            SetAddNode node = new SetAddNode();
            Nodes.set(node, "Item", "front");
            Nodes.run(node);

            assertEquals(Set.of("front"), Nodes.set(node, "Set"));
            assertEquals(true, Nodes.get(node, "Added"));
        }

        @Test
        void removingFindsAMemberThroughTypedText() {
            SetRemoveNode node = new SetRemoveNode();
            Nodes.set(node, "Set", setOf(3, "front"));
            Nodes.set(node, "Item", "3");
            Nodes.run(node);

            assertEquals(List.of("front"), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(true, Nodes.get(node, "Removed"));
        }

        @Test
        void removingSomethingAbsentLeavesTheSetAsItWas() {
            SetRemoveNode node = new SetRemoveNode();
            Nodes.set(node, "Set", setOf("front"));
            Nodes.set(node, "Item", "back");
            Nodes.run(node);

            assertEquals(List.of("front"), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(false, Nodes.get(node, "Removed"));
        }

        @Test
        void leavesTheSetItWasGivenAlone() {
            Set<Object> source = setOf("front");

            SetAddNode node = new SetAddNode();
            Nodes.set(node, "Set", source);
            Nodes.set(node, "Item", "back");
            Nodes.run(node);

            assertEquals(Set.of("front"), source, "nothing in this library edits a collection it was handed");
        }
    }

    @Nested
    class Union {

        private static SetUnionNode union(Set<Object> a, Set<Object> b) {
            SetUnionNode node = new SetUnionNode();
            Nodes.set(node, "A", a);
            Nodes.set(node, "B", b);
            Nodes.run(node);
            return node;
        }

        @Test
        void takesEverythingInEitherSetWithAsMembersFirst() {
            SetUnionNode node = union(setOf("front", "back"), setOf("back", "side"));

            assertEquals(List.of("front", "back", "side"), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(3, Nodes.get(node, "Count"));
        }

        @Test
        void aMemberSpelledTwoWaysSurvivesAsAs() {
            SetUnionNode node = union(setOf(3), setOf("3"));

            assertEquals(List.of(3), List.copyOf(Nodes.set(node, "Set")), "A goes in first, so A's object wins");
        }

        @Test
        void unionWithNothingIsJustTheOtherSet() {
            assertEquals(Set.of("front"), Nodes.set(union(setOf("front"), null), "Set"));
            assertEquals(Set.of("front"), Nodes.set(union(null, setOf("front")), "Set"));
            assertEquals(Set.of(), Nodes.set(union(null, null), "Set"));
        }
    }

    @Nested
    class Intersection {

        private static SetIntersectionNode intersect(Set<Object> a, Set<Object> b) {
            SetIntersectionNode node = new SetIntersectionNode();
            Nodes.set(node, "A", a);
            Nodes.set(node, "B", b);
            Nodes.run(node);
            return node;
        }

        @Test
        void takesOnlyWhatIsInBothInAsOrder() {
            SetIntersectionNode node = intersect(setOf("front", "back", "side"), setOf("side", "front"));

            assertEquals(List.of("front", "side"), List.copyOf(Nodes.set(node, "Set")));
            assertEquals(2, Nodes.get(node, "Count"));
            assertEquals(false, Nodes.get(node, "Is Empty"));
        }

        @Test
        void matchesAcrossTypesAndKeepsAsObject() {
            SetIntersectionNode node = intersect(setOf(3), setOf("3"));

            assertEquals(List.of(3), List.copyOf(Nodes.set(node, "Set")));
        }

        @Test
        void setsWithNothingInCommonIntersectToNothing() {
            SetIntersectionNode node = intersect(setOf("front"), setOf("back"));

            assertEquals(Set.of(), Nodes.set(node, "Set"));
            assertEquals(true, Nodes.get(node, "Is Empty"),
                    "\"did these have anything in common?\" is the output that gets wired");
        }

        @Test
        void anUnwiredSideMakesTheResultEmpty() {
            assertEquals(Set.of(), Nodes.set(intersect(setOf("front"), null), "Set"));
            assertEquals(Set.of(), Nodes.set(intersect(null, setOf("front")), "Set"));
        }
    }

    @Nested
    class Difference {

        private static SetDifferenceNode difference(Set<Object> a, Set<Object> b) {
            SetDifferenceNode node = new SetDifferenceNode();
            Nodes.set(node, "A", a);
            Nodes.set(node, "B", b);
            Nodes.run(node);
            return node;
        }

        @Test
        void reportsBothDirectionsAtOnce() {
            SetDifferenceNode node = difference(setOf("front", "back"), setOf("back", "side"));

            assertEquals(Set.of("front"), Nodes.set(node, "Only in A"), "what went away");
            assertEquals(Set.of("side"), Nodes.set(node, "Only in B"), "what is new");
            assertEquals(true, Nodes.get(node, "Changed"));
        }

        @Test
        void identicalSetsHaveNotChanged() {
            SetDifferenceNode node = difference(setOf("front", "back"), setOf("back", "front"));

            assertEquals(Set.of(), Nodes.set(node, "Only in A"));
            assertEquals(Set.of(), Nodes.set(node, "Only in B"));
            assertEquals(false, Nodes.get(node, "Changed"),
                    "order is not a change - this is what makes Changed usable as \"are these equal?\"");
        }

        @Test
        void aValueSpelledTwoWaysIsNotAChange() {
            SetDifferenceNode node = difference(setOf(3), setOf("3"));

            assertEquals(false, Nodes.get(node, "Changed"),
                    "nothing downstream could tell these apart, so reporting a change would be a false alarm");
        }

        @Test
        void oneSideBeingContainedInTheOtherShowsAsAnEmptyDirection() {
            SetDifferenceNode node = difference(setOf("front"), setOf("front", "back"));

            assertEquals(Set.of(), Nodes.set(node, "Only in A"), "A being empty here is A contained in B");
            assertEquals(Set.of("back"), Nodes.set(node, "Only in B"));
        }

        @Test
        void anUnwiredSideIsEverythingAddedOrEverythingRemoved() {
            SetDifferenceNode removed = difference(setOf("front"), null);
            assertEquals(Set.of("front"), Nodes.set(removed, "Only in A"));
            assertEquals(true, Nodes.get(removed, "Changed"));

            SetDifferenceNode added = difference(null, setOf("front"));
            assertEquals(Set.of("front"), Nodes.set(added, "Only in B"));

            assertEquals(false, Nodes.get(difference(null, null), "Changed"));
        }

        @Test
        void publishesItsContentsUnderTheNamesTheGraphSavesThemBy() {
            SetDifferenceNode node = new SetDifferenceNode();

            assertEquals(List.of("A", "B"), Nodes.inputNames(node));
            assertEquals(List.of("Only in A", "Only in B", "Changed"), Nodes.outputNames(node));
        }
    }
}
