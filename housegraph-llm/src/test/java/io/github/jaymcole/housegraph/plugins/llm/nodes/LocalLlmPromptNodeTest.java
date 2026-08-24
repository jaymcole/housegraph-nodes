package io.github.jaymcole.housegraph.plugins.llm.nodes;

import com.sun.net.httpserver.HttpServer;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.llm.LlmException;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLlmPromptNodeTest {

    private HttpServer server;
    private String lastBody;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void itHasThePortsItsDocumentationDescribes() {
        LocalLlmPromptNode node = new LocalLlmPromptNode();

        assertEquals(List.of("Prompt", "System Prompt", "Model", "Server", "API", "Temperature", "API Key",
                "Timeout (s)"), Nodes.inputNames(node));
        assertEquals(List.of("Response"), Nodes.outputNames(node));
        assertEquals(1, node.getFlowInputs().size());
        assertEquals(1, node.getFlowOutputs().size());
        assertEquals(FlowPort.Direction.IN, node.getFlowInputs().get(0).direction);
    }

    @Test
    void itArrivesPointedAtOllamaOnThisMachine() {
        LocalLlmPromptNode node = new LocalLlmPromptNode();

        assertEquals(LocalLlmClient.DEFAULT_SERVER, Nodes.inputOf(node, "Server"));
        assertEquals(LocalLlmClient.DEFAULT_MODEL, Nodes.inputOf(node, "Model"));
        assertEquals("ollama", Nodes.inputOf(node, "API"));
        assertEquals(LocalLlmClient.DEFAULT_TIMEOUT_SECONDS, (Integer) Nodes.inputOf(node, "Timeout (s)"));
    }

    @Test
    void oneRunAtATimeUnlessTheGraphSaysOtherwise() {
        assertEquals(1, new LocalLlmPromptNode().getMaxConcurrency());
    }

    @Test
    void theApiKeyIsNeverWrittenToASaveFile() {
        LocalLlmPromptNode node = new LocalLlmPromptNode();

        assertEquals(1, node.getInputs().stream().filter(input -> input.name.equals("API Key")).count());
        assertTrue(node.getInputs().stream()
                .filter(input -> input.name.equals("API Key"))
                .allMatch(NodeVariable::isSecret));
    }

    @Test
    void aPromptComesBackAsTheResponseOutput() throws IOException {
        String address = serve("{\"response\":\"Because of the air.\",\"done\":true}");
        LocalLlmPromptNode node = new LocalLlmPromptNode();
        Nodes.set(node, "Server", address);
        Nodes.set(node, "Prompt", "Why is the sky blue?");
        Nodes.set(node, "System Prompt", "Be brief.");

        Nodes.run(node);

        assertEquals("Because of the air.", Nodes.get(node, "Response"));
        assertTrue(lastBody.contains("\"system\":\"Be brief.\""), lastBody);
    }

    @Test
    void anEmptyPromptFailsTheNodeRatherThanAskingTheModelNothing() {
        LocalLlmPromptNode node = new LocalLlmPromptNode();

        LlmException failure = assertThrows(LlmException.class, () -> Nodes.run(node));
        assertTrue(failure.getMessage().contains("Prompt"), failure.getMessage());
    }

    @Test
    void anEmptyTimeoutFallsBackToTheDefaultRatherThanNoTimeAtAll() throws IOException {
        String address = serve("{\"response\":\"ok\",\"done\":true}");
        LocalLlmPromptNode node = new LocalLlmPromptNode();
        Nodes.set(node, "Server", address);
        Nodes.set(node, "Prompt", "hello");
        Nodes.set(node, "Timeout (s)", null);

        Nodes.run(node);

        assertEquals("ok", Nodes.get(node, "Response"));
    }

    @Test
    void anUnknownApiFailsTheNode() {
        LocalLlmPromptNode node = new LocalLlmPromptNode();
        Nodes.set(node, "Prompt", "hello");
        Nodes.set(node, "API", "claude");

        assertThrows(LlmException.class, () -> Nodes.run(node));
    }

    /** Starts a stub Ollama that answers every request with {@code body}, and returns its address. */
    private String serve(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }
}
