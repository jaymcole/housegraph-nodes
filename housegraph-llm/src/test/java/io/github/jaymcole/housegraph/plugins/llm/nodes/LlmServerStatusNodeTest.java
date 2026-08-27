package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.llm.LlmException;
import io.github.jaymcole.housegraph.plugins.llm.LlmModels;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;
import io.github.jaymcole.housegraph.plugins.llm.StubLlmServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmServerStatusNodeTest {

    /** A loopback port with nothing on it, so the check is refused at once. */
    private static final String NOTHING_LISTENING = "http://localhost:1";

    @Test
    void itHasThePortsItsDocumentationDescribes() {
        LlmServerStatusNode node = new LlmServerStatusNode();

        assertEquals(List.of("Server", "API", "API Key", "Timeout (s)"), Nodes.inputNames(node));
        assertEquals(List.of("Running", "Models", "Detail"), Nodes.outputNames(node));
        assertEquals(1, node.getFlowInputs().size());
        assertEquals(List.of("Running", "Not Running"), Nodes.flowOutputNames(node));
    }

    @Test
    void itArrivesPointedAtOllamaOnThisMachine() {
        LlmServerStatusNode node = new LlmServerStatusNode();

        assertEquals(LocalLlmClient.DEFAULT_SERVER, Nodes.inputOf(node, "Server"));
        assertEquals("ollama", Nodes.inputOf(node, "API"));
        assertEquals(LlmModels.DEFAULT_STATUS_TIMEOUT_SECONDS, (Integer) Nodes.inputOf(node, "Timeout (s)"));
    }

    @Test
    void theApiKeyIsNeverWrittenToASaveFile() {
        LlmServerStatusNode node = new LlmServerStatusNode();

        assertTrue(node.getInputs().stream()
                .filter(input -> input.name.equals("API Key"))
                .allMatch(NodeVariable::isSecret));
    }

    @Test
    void aRunningServerReportsItsModels() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest", "qwen2.5:7b")) {
            LlmServerStatusNode node = new LlmServerStatusNode();
            Nodes.set(node, "Server", stub.address());

            Nodes.run(node);

            assertEquals(Boolean.TRUE, Nodes.get(node, "Running"));
            assertEquals(List.of("llama3.2:latest", "qwen2.5:7b"), Nodes.get(node, "Models"));
            assertTrue(((String) Nodes.get(node, "Detail")).contains("llama3.2:latest"),
                    Nodes.get(node, "Detail").toString());
        }
    }

    @Test
    void aServerThatIsDownIsAnOutputRatherThanAFailedNode() {
        LlmServerStatusNode node = new LlmServerStatusNode();
        Nodes.set(node, "Server", NOTHING_LISTENING);

        Nodes.run(node);

        assertEquals(Boolean.FALSE, Nodes.get(node, "Running"));
        assertEquals(List.of(), Nodes.get(node, "Models"));
        assertTrue(((String) Nodes.get(node, "Detail")).contains("listening"),
                Nodes.get(node, "Detail").toString());
    }

    @Test
    void aGraphThatIsWrongStillFailsTheNode() {
        LlmServerStatusNode blankAddress = new LlmServerStatusNode();
        Nodes.set(blankAddress, "Server", "  ");
        assertThrows(LlmException.class, () -> Nodes.run(blankAddress),
                "no amount of waiting turns a blank Server into a running one");

        LlmServerStatusNode unknownApi = new LlmServerStatusNode();
        Nodes.set(unknownApi, "API", "anthropic");
        assertThrows(LlmException.class, () -> Nodes.run(unknownApi));
    }
}
