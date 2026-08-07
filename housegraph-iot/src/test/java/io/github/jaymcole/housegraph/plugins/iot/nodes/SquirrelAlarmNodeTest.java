package io.github.jaymcole.housegraph.plugins.iot.nodes;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Deterministic and offline: nothing here reaches the device. */
class SquirrelAlarmNodeTest {

    @Test
    void hostFieldToleratesWhateverGetsPastedIntoIt() {
        assertAll(
                () -> assertEquals("squirrel-alarm.local", SquirrelAlarmNode.resolveHost("http://squirrel-alarm.local/")),
                () -> assertEquals("squirrel-alarm.local", SquirrelAlarmNode.resolveHost("https://squirrel-alarm.local")),
                () -> assertEquals("192.168.1.50", SquirrelAlarmNode.resolveHost("  192.168.1.50  ")),
                () -> assertEquals("squirrel-alarm.local", SquirrelAlarmNode.resolveHost(null), "empty falls back to mDNS name"),
                () -> assertEquals("squirrel-alarm.local", SquirrelAlarmNode.resolveHost("   ")));
    }

    @Test
    void statusBecomesTheDevicesEndpointPath() {
        assertAll(
                () -> assertEquals("bird", SquirrelAlarmNode.normalizeStatus("Bird")),
                () -> assertEquals("squirrel", SquirrelAlarmNode.normalizeStatus("  SQUIRREL ")),
                () -> assertEquals("clear", SquirrelAlarmNode.normalizeStatus("/clear"), "a leading slash is tolerated"),
                () -> assertNull(SquirrelAlarmNode.normalizeStatus(""), "nothing wired yet"),
                () -> assertNull(SquirrelAlarmNode.normalizeStatus(null)));
    }

    @Test
    void doesNotPokeTheDeviceWhenNoStatusIsSet() {
        // The early return matters: without it every run would fire a request at whatever host is
        // in the field, on a node the user has only just placed.
        SquirrelAlarmNode node = new SquirrelAlarmNode();

        assertDoesNotThrow(() -> node.process(ProcessContext.uncancelled()));
    }

    @Test
    void exposesTheExpectedPorts() {
        SquirrelAlarmNode node = new SquirrelAlarmNode();

        assertEquals(2, node.getInputs().size());
        assertEquals("Host", node.getInputs().get(0).name);
        assertEquals("Status", node.getInputs().get(1).name);
        assertEquals("squirrel-alarm.local", node.getInputs().get(0).getValue(), "prefilled so it works out of the box");
        assertEquals(0, node.getOutputs().size());
        assertEquals(1, node.getFlowInputs().size());
        assertEquals(1, node.getFlowOutputs().size(), "control flows through, so work can be chained after it");
    }
}
