package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link ParseBooleanNode}, and the several spellings of yes and no it accepts. */
class ParseBooleanNodeTest {

    @Test
    void declaresOneInputAndTwoOutputs() {
        ParseBooleanNode node = new ParseBooleanNode();

        assertEquals(List.of("Text"), Ports.inputNames(node));
        assertEquals(List.of("Value", "Valid"), Ports.outputNames(node));
    }

    @Test
    void everySpellingOfYesReadsAsTrue() {
        for (String yes : List.of("true", "yes", "y", "on", "1", "TRUE", " Yes ")) {
            ParseBooleanNode node = reading(yes);

            Ports.run(node);

            assertTrue(Ports.boolOf(node, "Valid"), "\"" + yes + "\" should be understood");
            assertTrue(Ports.boolOf(node, "Value"), "\"" + yes + "\" should read as true");
        }
    }

    @Test
    void everySpellingOfNoReadsAsFalse() {
        for (String no : List.of("false", "no", "n", "off", "0", "FALSE", " No ")) {
            ParseBooleanNode node = reading(no);

            Ports.run(node);

            assertTrue(Ports.boolOf(node, "Valid"), "\"" + no + "\" should be understood");
            assertFalse(Ports.boolOf(node, "Value"), "\"" + no + "\" should read as false");
        }
    }

    @Test
    void anythingElseIsNotUnderstoodRatherThanFailingTheNode() {
        for (String unclear : List.of("", "maybe", "2", "ja")) {
            ParseBooleanNode node = reading(unclear);

            Ports.run(node);

            assertFalse(Ports.boolOf(node, "Valid"), "\"" + unclear + "\" should not be understood");
            assertFalse(Ports.boolOf(node, "Value"));
        }
    }

    @Test
    void noAnswerIsDistinguishableFromAnswerNoByReadingValidFirst() {
        ParseBooleanNode noAnswer = new ParseBooleanNode();
        Ports.run(noAnswer);

        ParseBooleanNode answeredNo = reading("no");
        Ports.run(answeredNo);

        assertFalse(Ports.boolOf(noAnswer, "Value"));
        assertFalse(Ports.boolOf(answeredNo, "Value"));
        assertFalse(Ports.boolOf(noAnswer, "Valid"), "and Valid is what tells the two apart");
        assertTrue(Ports.boolOf(answeredNo, "Valid"));
    }

    private static ParseBooleanNode reading(String text) {
        ParseBooleanNode node = new ParseBooleanNode();
        Ports.set(node, "Text", text);
        return node;
    }
}
