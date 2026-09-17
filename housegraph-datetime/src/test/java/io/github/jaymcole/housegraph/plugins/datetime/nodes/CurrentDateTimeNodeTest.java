package io.github.jaymcole.housegraph.plugins.datetime.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link CurrentDateTimeNode} against the system clock, not a fixed instant. */
class CurrentDateTimeNodeTest {

    @Test
    void declaresNoInputsAndOneOutput() {
        CurrentDateTimeNode node = new CurrentDateTimeNode();

        assertEquals(List.of(), Ports.inputNames(node));
        assertEquals(List.of("Milliseconds"), Ports.outputNames(node));
    }

    @Test
    void readsTheCurrentMoment() {
        CurrentDateTimeNode node = new CurrentDateTimeNode();

        long before = System.currentTimeMillis();
        Ports.run(node);
        long after = System.currentTimeMillis();

        long reported = Ports.get(node, "Milliseconds");
        assertTrue(reported >= before && reported <= after,
                "expected " + reported + " to fall between " + before + " and " + after);
    }
}
