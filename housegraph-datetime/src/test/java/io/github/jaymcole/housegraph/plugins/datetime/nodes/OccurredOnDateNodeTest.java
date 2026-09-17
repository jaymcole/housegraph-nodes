package io.github.jaymcole.housegraph.plugins.datetime.nodes;

import io.github.jaymcole.housegraph.graph.ProcessContext;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link OccurredOnDateNode} against the system's own time zone, not a fixed one.
 * <p>
 * Which flow-out port fires is graph-cascade behavior — {@code BaseNode.activate()} is a no-op
 * with no {@code ExecutionContext} bound, the same limitation {@code GitSyncNodeTest} notes — so
 * this stays focused on the node's declared ports and its Occurred data output.
 */
class OccurredOnDateNodeTest {

    @Test
    void declaresTwoInputsOneOutputAndAFlowInWithYesNo() {
        OccurredOnDateNode node = new OccurredOnDateNode();

        assertEquals(List.of("Milliseconds", "Date"), Ports.inputNames(node));
        assertEquals(List.of("Occurred"), Ports.outputNames(node));
        assertEquals(1, node.getFlowInputs().size());
        List<String> outNames = node.getFlowOutputs().stream().map(port -> port.name).toList();
        assertEquals(List.of("Yes", "No"), outNames);
    }

    @Test
    void sameCalendarDateOccurred() {
        ZonedDateTime morning = ZonedDateTime.of(2026, 3, 5, 8, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime evening = ZonedDateTime.of(2026, 3, 5, 22, 30, 0, 0, ZoneId.systemDefault());
        OccurredOnDateNode node = configuredNode(morning, evening);

        Ports.run(node);

        assertEquals(true, Ports.get(node, "Occurred"));
    }

    @Test
    void differentCalendarDateDidNotOccur() {
        ZonedDateTime moment = ZonedDateTime.of(2026, 3, 5, 8, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime otherDay = ZonedDateTime.of(2026, 3, 6, 8, 0, 0, 0, ZoneId.systemDefault());
        OccurredOnDateNode node = configuredNode(moment, otherDay);

        Ports.run(node);

        assertEquals(false, Ports.get(node, "Occurred"));
    }

    @Test
    void bothInputsUnwiredReadAsTheSameEpochDateRatherThanFailing() {
        OccurredOnDateNode node = new OccurredOnDateNode();

        node.process(ProcessContext.uncancelled());

        assertEquals(true, Ports.get(node, "Occurred"));
    }

    private static OccurredOnDateNode configuredNode(ZonedDateTime millis, ZonedDateTime date) {
        OccurredOnDateNode node = new OccurredOnDateNode();
        Ports.set(node, "Milliseconds", millis.toInstant().toEpochMilli());
        Ports.set(node, "Date", date.toInstant().toEpochMilli());
        return node;
    }
}
