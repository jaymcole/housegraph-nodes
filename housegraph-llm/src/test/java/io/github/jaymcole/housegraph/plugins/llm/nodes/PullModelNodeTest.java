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

class PullModelNodeTest {

    @Test
    void itHasThePortsItsDocumentationDescribes() {
        PullModelNode node = new PullModelNode();

        assertEquals(List.of("Model", "Server", "API Key", "Timeout (s)"), Nodes.inputNames(node));
        assertEquals(List.of("Model", "Downloaded"), Nodes.outputNames(node));
        assertEquals(1, node.getFlowInputs().size());
        assertEquals(List.of("Ready", "Pulled"), Nodes.flowOutputNames(node));
    }

    @Test
    void itArrivesPointedAtOllamaOnThisMachine() {
        PullModelNode node = new PullModelNode();

        assertEquals(LocalLlmClient.DEFAULT_SERVER, Nodes.inputOf(node, "Server"));
        assertEquals(LocalLlmClient.DEFAULT_MODEL, Nodes.inputOf(node, "Model"));
        assertEquals(LlmModels.DEFAULT_PULL_TIMEOUT_SECONDS, (Integer) Nodes.inputOf(node, "Timeout (s)"));
    }

    @Test
    void theApiKeyIsNeverWrittenToASaveFile() {
        PullModelNode node = new PullModelNode();

        assertTrue(node.getInputs().stream()
                .filter(input -> input.name.equals("API Key"))
                .allMatch(NodeVariable::isSecret));
    }

    @Test
    void oneDownloadAtATime() {
        assertEquals(1, new PullModelNode().getMaxConcurrency());
    }

    @Test
    void aModelThatIsAlreadyThereIsNotDownloadedAgain() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest")) {
            PullModelNode node = new PullModelNode();
            Nodes.set(node, "Server", stub.address());
            Nodes.set(node, "Model", "llama3.2");

            Nodes.run(node);

            assertEquals(Boolean.FALSE, Nodes.get(node, "Downloaded"));
            assertEquals("llama3.2", Nodes.get(node, "Model"));
            assertEquals(List.of("/api/tags"), stub.requestedPaths());
        }
    }

    @Test
    void aModelThatIsMissingIsPulledAndSaidSo() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith()) {
            stub.on("/api/pull", 200, "{\"status\":\"success\"}");
            PullModelNode node = new PullModelNode();
            Nodes.set(node, "Server", stub.address());
            Nodes.set(node, "Model", "llama3.2");

            Nodes.run(node);

            assertEquals(Boolean.TRUE, Nodes.get(node, "Downloaded"));
            assertEquals(List.of("/api/tags", "/api/pull"), stub.requestedPaths());
        }
    }

    @Test
    void aServerThatIsDownFailsTheNodeRatherThanClaimingSuccess() {
        PullModelNode node = new PullModelNode();
        // Port 1 is privileged and unused, so the check is refused at once.
        Nodes.set(node, "Server", "http://localhost:1");

        LlmException failure = assertThrows(LlmException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("Local LLM Server"), failure.getMessage());
    }
}
