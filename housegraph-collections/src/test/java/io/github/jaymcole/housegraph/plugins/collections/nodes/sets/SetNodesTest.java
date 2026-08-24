package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The remaining set nodes, grouped for the same reason {@code ListInspectionNodesTest} and
 * {@code FilterNodesTest} are: each is a handful of assertions and they share one story with the
 * list nodes they sit beside — membership is forgiving about types, and a question with a yes/no
 * answer answers with a boolean.
 */
class SetNodesTest {

    @Nested
    class FromList {

        @Test
        void collapsesRepeatsAndReportsHowManyWereDropped() {
            SetFromListNode node = new SetFromListNode();
            Nodes.set(node, "List", List.of(3, "3", "3", 4));

            Nodes.run(node);

            assertEquals(List.of(3, 4), new ArrayList<>(Nodes.members(node, "Set")));
            assertEquals(2, Nodes.get(node, "Removed"));
        }

        @Test
        void anAbsentListIsAnEmptySet() {
            SetFromListNode node = new SetFromListNode();

            Nodes.run(node);

            assertEquals(Set.of(), Nodes.members(node, "Set"));
            assertEquals(0, Nodes.get(node, "Removed"));
        }
    }

    @Nested
    class ToList {

        @Test
        void reportsMembersInInsertionOrder() {
            SetToListNode node = new SetToListNode();
            Nodes.set(node, "Set", new java.util.LinkedHashSet<>(List.of("z", "a")));

            Nodes.run(node);

            assertEquals(List.of("z", "a"), Nodes.list(node, "List"));
        }
    }

    @Nested
    class Contains {

        @Test
        void findsAMatchingMember() {
            SetContainsNode node = new SetContainsNode();
            Nodes.set(node, "Set", Set.of(1, 2, 3));
            Nodes.set(node, "Item", "3");

            Nodes.run(node);

            assertEquals(true, Nodes.get(node, "Found"), "erasure means a typed item must find a numeric member");
        }

        @Test
        void reportsAbsenceRatherThanFailing() {
            SetContainsNode node = new SetContainsNode();
            Nodes.set(node, "Set", Set.of("a"));
            Nodes.set(node, "Item", "z");

            Nodes.run(node);

            assertEquals(false, Nodes.get(node, "Found"));
        }
    }

    @Nested
    class Add {

        @Test
        void addsANewMember() {
            AddToSetNode node = new AddToSetNode();
            Nodes.set(node, "Set", Set.of("a"));
            Nodes.set(node, "Item", "b");

            Nodes.run(node);

            assertEquals(Set.of("a", "b"), Nodes.members(node, "Set"));
            assertEquals(true, Nodes.get(node, "Added"));
        }

        @Test
        void reportsFalseWhenTheMemberWasAlreadyThere() {
            AddToSetNode node = new AddToSetNode();
            Nodes.set(node, "Set", Set.of(3));
            Nodes.set(node, "Item", "3");

            Nodes.run(node);

            assertEquals(Set.of(3), Nodes.members(node, "Set"), "the set gains nothing new");
            assertEquals(false, Nodes.get(node, "Added"));
        }

        @Test
        void aNullItemAddsNothing() {
            AddToSetNode node = new AddToSetNode();
            Nodes.set(node, "Set", Set.of("a"));

            Nodes.run(node);

            assertEquals(Set.of("a"), Nodes.members(node, "Set"));
            assertEquals(false, Nodes.get(node, "Added"));
        }
    }

    @Nested
    class Remove {

        @Test
        void removesAMatchingMember() {
            RemoveFromSetNode node = new RemoveFromSetNode();
            Nodes.set(node, "Set", Set.of("a", "b"));
            Nodes.set(node, "Item", "a");

            Nodes.run(node);

            assertEquals(Set.of("b"), Nodes.members(node, "Set"));
            assertEquals(true, Nodes.get(node, "Removed"));
        }

        @Test
        void leavesTheSetUnchangedWhenTheMemberWasNeverThere() {
            RemoveFromSetNode node = new RemoveFromSetNode();
            Nodes.set(node, "Set", Set.of("a"));
            Nodes.set(node, "Item", "z");

            Nodes.run(node);

            assertEquals(Set.of("a"), Nodes.members(node, "Set"));
            assertEquals(false, Nodes.get(node, "Removed"));
        }
    }

    @Nested
    class Union {

        @Test
        void combinesEveryMemberOfEitherSet() {
            UnionSetsNode node = new UnionSetsNode();
            Nodes.set(node, "First", Set.of("a", "b"));
            Nodes.set(node, "Second", Set.of("b", "c"));

            Nodes.run(node);

            assertEquals(Set.of("a", "b", "c"), Nodes.members(node, "Set"));
        }

        @Test
        void eitherSideUnwiredPassesTheOtherThrough() {
            UnionSetsNode onlyFirst = new UnionSetsNode();
            Nodes.set(onlyFirst, "First", Set.of("a"));
            Nodes.run(onlyFirst);
            assertEquals(Set.of("a"), Nodes.members(onlyFirst, "Set"));
        }
    }

    @Nested
    class Intersect {

        @Test
        void keepsOnlyMembersInBoth() {
            IntersectSetsNode node = new IntersectSetsNode();
            Nodes.set(node, "First", Set.of("a", "b"));
            Nodes.set(node, "Second", Set.of("b", "c"));

            Nodes.run(node);

            assertEquals(Set.of("b"), Nodes.members(node, "Set"));
        }

        @Test
        void anUnwiredSideMakesTheResultEmpty() {
            IntersectSetsNode node = new IntersectSetsNode();
            Nodes.set(node, "First", Set.of("a"));

            Nodes.run(node);

            assertEquals(Set.of(), Nodes.members(node, "Set"),
                    "there is nothing in common with an empty set");
        }
    }

    @Nested
    class Difference {

        @Test
        void keepsWhatsInTheFirstButNotTheSecond() {
            DifferenceSetsNode node = new DifferenceSetsNode();
            Nodes.set(node, "First", Set.of("a", "b"));
            Nodes.set(node, "Second", Set.of("b", "c"));

            Nodes.run(node);

            assertEquals(Set.of("a"), Nodes.members(node, "Set"));
        }

        @Test
        void isNotSymmetric() {
            DifferenceSetsNode forward = new DifferenceSetsNode();
            Nodes.set(forward, "First", Set.of("a", "b"));
            Nodes.set(forward, "Second", Set.of("b", "c"));
            Nodes.run(forward);

            DifferenceSetsNode backward = new DifferenceSetsNode();
            Nodes.set(backward, "First", Set.of("b", "c"));
            Nodes.set(backward, "Second", Set.of("a", "b"));
            Nodes.run(backward);

            assertEquals(Set.of("a"), Nodes.members(forward, "Set"));
            assertEquals(Set.of("c"), Nodes.members(backward, "Set"));
        }

        @Test
        void anUnwiredSecondLeavesFirstUnchanged() {
            DifferenceSetsNode node = new DifferenceSetsNode();
            Nodes.set(node, "First", Set.of("a"));

            Nodes.run(node);

            assertEquals(Set.of("a"), Nodes.members(node, "Set"));
        }
    }

    @Nested
    class Count {

        @Test
        void countsTheMembersAndFlagsTheEmptyCase() {
            SetCountNode node = new SetCountNode();
            Nodes.set(node, "Set", Set.of("a", "b"));

            Nodes.run(node);

            assertEquals(2, Nodes.get(node, "Count"));
            assertEquals(false, Nodes.get(node, "Is Empty"));
        }

        @Test
        void anUnwiredSetCountsAsEmptyRatherThanFailing() {
            SetCountNode node = new SetCountNode();

            Nodes.run(node);

            assertEquals(0, Nodes.get(node, "Count"));
            assertEquals(true, Nodes.get(node, "Is Empty"));
        }
    }
}
