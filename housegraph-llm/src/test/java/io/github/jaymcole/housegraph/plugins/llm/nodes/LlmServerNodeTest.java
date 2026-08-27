package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmException;
import io.github.jaymcole.housegraph.plugins.llm.LlmServerSpec;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;
import io.github.jaymcole.housegraph.plugins.llm.StubLlmServer;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server node headlessly: its declared ports, its defaults, the state it saves, and the one
 * lifecycle path that can be exercised without a real model server — adopting one that is already
 * answering, which a {@link StubLlmServer} can stand in for. Actually spawning a server touches the
 * OS, and what the supervisor does when it has to is covered by {@code LlmServerProcessTest}.
 * <p>
 * {@link AppDirectories#get()} caches its resolved root for the life of the JVM, so it is pointed
 * at a temp directory once before anything here touches it: a start writes the spawn record used
 * to reap orphans, and a suite has no business writing into the real user profile.
 */
class LlmServerNodeTest {

    @TempDir
    static Path storageRoot;

    @BeforeAll
    static void pointTheDefaultStorageLocationAtATempDirectory() {
        System.setProperty("housegraph.home", storageRoot.toString());
    }

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

    /** Wires a fresh {@link Trigger} into {@code targetPort} of {@code node} and fires it, blocking until the run settles. */
    private static void fire(NodeGraph graph, LlmServerNode node, FlowPort targetPort) {
        Trigger trigger = new Trigger();
        graph.addNode(trigger);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), node, targetPort));
        trigger.execute();
        graph.awaitIdle();
    }

    @Test
    void itHasThePortsItsDocumentationDescribes() {
        LlmServerNode node = new LlmServerNode();

        assertEquals(List.of("Name", "Command", "Directory", "Server", "API", "API Key",
                "Startup Timeout (s)"), Nodes.inputNames(node));
        assertEquals(List.of("Server", "Models", "Running"), Nodes.outputNames(node));
        assertEquals(List.of("Start", "Stop", "Restart"), Nodes.flowInputNames(node));
        assertEquals(List.of("Ready", "Stopped"), Nodes.flowOutputNames(node));
    }

    @Test
    void itArrivesReadyToRunOllamaOnThisMachine() {
        LlmServerNode node = new LlmServerNode();

        assertEquals(LlmServerSpec.DEFAULT_NAME, Nodes.inputOf(node, "Name"));
        assertEquals(LlmServerSpec.DEFAULT_COMMAND, Nodes.inputOf(node, "Command"));
        assertEquals(LocalLlmClient.DEFAULT_SERVER, Nodes.inputOf(node, "Server"));
        assertEquals("ollama", Nodes.inputOf(node, "API"));
        assertEquals(LlmServerSpec.DEFAULT_STARTUP_TIMEOUT_SECONDS,
                (Integer) Nodes.inputOf(node, "Startup Timeout (s)"));
        assertNull(Nodes.inputOf(node, "Directory"), "no directory means HouseGraph's own");
    }

    @Test
    void itsDefaultsMatchTheOnesTheLocalLlmNodeShipsWith() {
        // The pair is meant to work wired together with nothing typed into either.
        LocalLlmPromptNode prompt = new LocalLlmPromptNode();
        LlmServerNode server = new LlmServerNode();

        assertEquals((String) Nodes.inputOf(prompt, "Server"), (String) Nodes.inputOf(server, "Server"));
        assertEquals((String) Nodes.inputOf(prompt, "API"), (String) Nodes.inputOf(server, "API"));
    }

    @Test
    void theApiKeyIsNeverWrittenToASaveFile() {
        LlmServerNode node = new LlmServerNode();

        assertTrue(node.getInputs().stream()
                .filter(input -> input.name.equals("API Key"))
                .allMatch(NodeVariable::isSecret));
    }

    @Test
    void oneLifecycleChangeAtATime() {
        assertEquals(1, new LlmServerNode().getMaxConcurrency());
    }

    @Test
    void aServerThatIsAlreadyRunningIsAdoptedAndReported() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest")) {
            LlmServerNode node = new LlmServerNode();
            // A command that would fail loudly if it ever ran, which is the assertion: it must not.
            Nodes.set(node, "Command", "exit 7");
            Nodes.set(node, "Server", stub.address());

            Nodes.run(node);

            assertEquals(Boolean.TRUE, Nodes.get(node, "Running"));
            assertEquals(stub.address(), Nodes.get(node, "Server"));
            assertEquals(List.of("llama3.2:latest"), Nodes.get(node, "Models"));
        }
    }

    @Test
    void aStartThatCannotHappenFailsTheNodeWithTheReason() {
        LlmServerNode node = new LlmServerNode();
        Nodes.set(node, "Command", "exit 3");
        // Port 1 is privileged and unused, so the readiness check is refused at once.
        Nodes.set(node, "Server", "http://localhost:1");
        Nodes.set(node, "Startup Timeout (s)", 5);

        LlmException failure = assertThrows(LlmException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("failed to start"), failure.getMessage());
        assertEquals(Boolean.FALSE, Nodes.get(node, "Running"),
                "the outputs should say what actually happened, even on the failing path");
    }

    @Test
    void anUnknownApiFailsBeforeAnythingIsSpawned() {
        LlmServerNode node = new LlmServerNode();
        Nodes.set(node, "API", "anthropic");

        assertThrows(LlmException.class, () -> Nodes.run(node));
    }

    @Test
    void stopNeverThrowsEvenWhenNothingWasEverStarted() {
        NodeGraph graph = new NodeGraph();
        LlmServerNode node = new LlmServerNode();
        graph.addNode(node);

        fire(graph, node, node.getFlowInputs().get(1)); // Stop

        assertNull(node.getLastError(), "stopping a server that was never started must be a safe no-op");
    }

    @Test
    void aStoppedServerDoesNotPersistARunningFlag() {
        assertFalse(new LlmServerNode().saveState().containsKey("running"),
                "a server that isn't running must not persist a running flag");
    }

    @Test
    void loadingARunningFlagArmsAutoStart() {
        LlmServerNode node = new LlmServerNode();
        assertFalse(node.wasRunning(), "a fresh node has no pending auto-start");

        node.loadState(Map.of("running", "true"));

        assertTrue(node.wasRunning(), "a graph saved while running reloads with auto-start pending");
    }

    @Test
    void everySettingIsAPortSoNoneOfThemIsSavedTwice() {
        LlmServerNode node = new LlmServerNode();
        Nodes.set(node, "Command", "llama-server -m model.gguf");

        assertFalse(node.saveState().containsKey("command"),
                "an input port is already saved as one; a second copy is a chance to disagree");
    }
}
