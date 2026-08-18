package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Node-server node's port-driven configuration and its three-way flow-in branch
 * (Start / Stop / Restart, told apart via {@code ProcessContext.wasTriggeredVia}) — the only
 * headless surfaces. Actually spawning/relaunching a {@code node} process touches the OS and the
 * network, which belongs in a manual/integration check, not the unit suite, so every test here
 * either supplies no project directory (hitting the guard before any spawn) or uses Stop (a safe
 * no-op on a server that was never started).
 */
class NodeServerNodeTest {

    /** A minimal flow source: one unnamed OUT port, fired by {@code execute()} — stands in for a trigger node. */
    private static final class Trigger extends BaseNode {
        private final FlowPort out = new FlowPort("Out", FlowPort.Direction.OUT);

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(out);
        }
    }

    private static NodeVariable<?> inputNamed(BaseNode node, String name) {
        return node.getInputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No input named \"" + name + "\""));
    }

    /** Wires a fresh {@link Trigger} into {@code targetPort} of {@code node} and fires it, blocking until the run settles. */
    private static void fire(NodeGraph graph, NodeServerNode node, FlowPort targetPort) {
        Trigger trigger = new Trigger();
        graph.addNode(trigger);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), node, targetPort));
        trigger.execute();
        graph.awaitIdle();
    }

    @Test
    void declaresItsDataInputsInDisplayOrder() {
        NodeServerNode node = new NodeServerNode();

        assertEquals(List.of("Name", "Directory", "Command", "Port"),
                node.getInputs().stream().map(v -> v.name).toList());
    }

    @Test
    void declaresStartStopAndRestartFlowInputs() {
        NodeServerNode node = new NodeServerNode();

        assertEquals(List.of("Start", "Stop", "Restart"),
                node.getFlowInputs().stream().map(p -> p.name).toList());
    }

    @Test
    void aStartAttemptRefusesToRunWithoutAProjectDirectory() {
        NodeServerNode node = new NodeServerNode();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> node.process(ProcessContext.uncancelled()),
                "an empty triggeredVia() defaults to a Start/Restart attempt, which shouldn't "
                        + "silently no-op on a node nobody has configured yet");
        assertEquals("No Node project directory configured", error.getMessage());
    }

    @Test
    void stopNeverThrowsEvenWhenNothingWasEverStarted() {
        NodeGraph graph = new NodeGraph();
        NodeServerNode node = new NodeServerNode();
        graph.addNode(node);

        fire(graph, node, node.getFlowInputs().get(1)); // Stop

        assertNull(node.getLastError(), "stopping a process that was never started must be a safe no-op");
    }

    @Test
    void aFreshNodeDefaultsToNpmStartOnPort3000NamedNodeApp() {
        NodeServerNode node = new NodeServerNode();

        assertEquals("node-app", inputNamed(node, "Name").getValue());
        assertEquals("npm start", inputNamed(node, "Command").getValue());
        assertEquals(3000, inputNamed(node, "Port").getValue());
    }

    @Test
    void legacyConfigurationInSavedStateMigratesOntoTheNewInputs() {
        NodeServerNode node = new NodeServerNode();

        node.loadState(Map.of(
                "name", "my-app",
                "directory", "/srv/my-app",
                "command", "node server.js",
                "port", "4000"));

        assertEquals("my-app", inputNamed(node, "Name").getValue());
        assertEquals("/srv/my-app", inputNamed(node, "Directory").getValue());
        assertEquals("node server.js", inputNamed(node, "Command").getValue());
        assertEquals(4000, inputNamed(node, "Port").getValue());
    }

    @Test
    void legacyDirectoryInSavedStateMigratesOntoTheDirectoryInput() {
        NodeServerNode node = new NodeServerNode();

        node.loadState(Map.of("name", "my-app", "directory", "/srv/my-app"));

        assertEquals("/srv/my-app", inputNamed(node, "Directory").getValue(),
                "a pre-input-port save's directory should land on the new Directory input, "
                        + "not be dropped, since saveState()/loadState() no longer carries it");
    }

    @Test
    void invalidPortFallsBackToDefault() {
        NodeServerNode node = new NodeServerNode();
        node.loadState(Map.of("name", "app", "port", "not-a-number"));

        assertEquals(3000, inputNamed(node, "Port").getValue(),
                "an unparseable legacy port should leave the Port input at its default rather than "
                        + "migrating garbage onto it");
    }

    @Test
    void aStoppedServerDoesNotPersistARunningFlag() {
        assertFalse(new NodeServerNode().saveState().containsKey("running"),
                "a process that isn't running must not persist a running flag");
    }

    @Test
    void loadingARunningFlagArmsAutoStart() {
        NodeServerNode node = new NodeServerNode();
        assertFalse(node.wasRunning(), "a fresh node has no pending auto-start");

        node.loadState(Map.of("name", "my-app", "port", "4000", "running", "true"));

        assertTrue(node.wasRunning(), "a graph saved while running reloads with auto-start pending");
    }
}
