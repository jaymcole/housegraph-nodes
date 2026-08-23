package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises {@link JoinTextNode}: the chosen separator, its escapes, and erased element types. */
class JoinTextNodeTest {

    @Test
    void declaresAListAndSeparatorInputAndAResultOutput() {
        JoinTextNode node = new JoinTextNode();

        assertEquals(List.of("List", "Separator"), Ports.inputNames(node));
        assertEquals(List.of("Result"), Ports.outputNames(node));
    }

    @Test
    void itJoinsWithACommaAndSpaceUntilToldOtherwise() {
        JoinTextNode node = joining(List.of("kitchen", "hallway", "porch"), null);

        Ports.run(node);

        assertEquals("kitchen, hallway, porch", Ports.get(node, "Result"));
    }

    @Test
    void theSeparatorIsWhateverWasAuthored() {
        JoinTextNode node = joining(List.of("usr", "local", "bin"), "/");

        Ports.run(node);

        assertEquals("usr/local/bin", Ports.get(node, "Result"));
    }

    @Test
    void anEscapedNewlineJoinsOneEntryPerLineWhichNoInlineFieldCouldOtherwiseExpress() {
        JoinTextNode node = joining(List.of("first", "second"), "\\n");

        Ports.run(node);

        assertEquals("first\nsecond", Ports.get(node, "Result"));
    }

    @Test
    void anyListJoinsBecauseItsElementTypeIsErased() {
        JoinTextNode node = joining(List.of(1, 2, 3), "+");

        Ports.run(node);

        assertEquals("1+2+3", Ports.get(node, "Result"));
    }

    @Test
    void aNullEntryRendersEmptyRatherThanTheWordNull() {
        JoinTextNode node = joining(Arrays.asList("kitchen", null, "porch"), ", ");

        Ports.run(node);

        assertEquals("kitchen, , porch", Ports.get(node, "Result"));
    }

    @Test
    void anEmptyOrUnwiredListYieldsEmptyText() {
        JoinTextNode empty = joining(List.of(), ", ");
        Ports.run(empty);
        assertEquals("", Ports.get(empty, "Result"));

        JoinTextNode unwired = new JoinTextNode();
        Ports.run(unwired);
        assertEquals("", Ports.get(unwired, "Result"));
    }

    private static JoinTextNode joining(List<?> entries, String separator) {
        JoinTextNode node = new JoinTextNode();
        Ports.set(node, "List", entries);
        if (separator != null) {
            Ports.set(node, "Separator", separator);
        }
        return node;
    }
}
