package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link TextLengthNode}, and the empty-versus-blank distinction it exists to expose. */
class TextLengthNodeTest {

    @Test
    void declaresOneInputAndThreeOutputs() {
        TextLengthNode node = new TextLengthNode();

        assertEquals(List.of("Text"), Ports.inputNames(node));
        assertEquals(List.of("Length", "Is Empty", "Is Blank"), Ports.outputNames(node));
    }

    @Test
    void itCountsCharacters() {
        TextLengthNode node = measuring("front door");

        Ports.run(node);

        assertEquals(10, Ports.intOf(node, "Length"));
        assertFalse(Ports.boolOf(node, "Is Empty"));
        assertFalse(Ports.boolOf(node, "Is Blank"));
    }

    @Test
    void whitespaceOnlyTextIsBlankButNotEmpty() {
        TextLengthNode node = measuring("   ");

        Ports.run(node);

        assertEquals(3, Ports.intOf(node, "Length"));
        assertFalse(Ports.boolOf(node, "Is Empty"), "three spaces are three characters");
        assertTrue(Ports.boolOf(node, "Is Blank"), "but they say nothing");
    }

    @Test
    void unwiredTextIsEmptyAndBlankRatherThanAFailure() {
        TextLengthNode node = new TextLengthNode();

        Ports.run(node);

        assertEquals(0, Ports.intOf(node, "Length"));
        assertTrue(Ports.boolOf(node, "Is Empty"));
        assertTrue(Ports.boolOf(node, "Is Blank"));
    }

    private static TextLengthNode measuring(String text) {
        TextLengthNode node = new TextLengthNode();
        Ports.set(node, "Text", text);
        return node;
    }
}
