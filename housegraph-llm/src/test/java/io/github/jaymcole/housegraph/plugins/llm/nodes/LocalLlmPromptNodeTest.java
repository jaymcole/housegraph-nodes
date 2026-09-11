package io.github.jaymcole.housegraph.plugins.llm.nodes;

import com.sun.net.httpserver.HttpServer;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.llm.LlmException;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLlmPromptNodeTest {

    private HttpServer server;
    private String lastBody;
    private final List<String> paths = new ArrayList<>();

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

        assertEquals(List.of("Prompt", "System Prompt", "Conversation", "History Turns", "Forget After (min)",
                "Model", "Server", "API", "Temperature", "API Key", "Timeout (s)"), Nodes.inputNames(node));
        assertEquals(List.of("Response", "Turns"), Nodes.outputNames(node));
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

    @Test
    void withNoConversationNamedEveryRunIsTurnOne() throws IOException {
        String address = serve("{\"response\":\"Frank Herbert.\",\"done\":true}");
        LocalLlmPromptNode node = prompting(address, "Who wrote Dune?");

        Nodes.run(node);
        Nodes.set(node, "Prompt", "What else did he write?");
        Nodes.run(node);

        assertEquals(List.of("/api/generate", "/api/generate"), paths);
        assertFalse(lastBody.contains("messages"), lastBody);
        assertFalse(lastBody.contains("Frank Herbert"), lastBody);
        assertEquals(0, (Integer) Nodes.get(node, "Turns"));
    }

    @Test
    void aNamedConversationSendsWhatWasAlreadySaid() throws IOException {
        String address = serve("{\"message\":{\"role\":\"assistant\",\"content\":\"Frank Herbert.\"},\"done\":true}");
        LocalLlmPromptNode node = prompting(address, "Who wrote Dune?");
        Nodes.set(node, "Conversation", aConversation());

        Nodes.run(node);
        assertEquals("/api/chat", paths.get(0), "turn one belongs to the conversation as much as turn two");
        assertEquals(List.of("user:Who wrote Dune?"), messagesOf(lastBody));
        assertEquals(1, (Integer) Nodes.get(node, "Turns"));

        Nodes.set(node, "Prompt", "What else did he write?");
        Nodes.run(node);

        assertEquals("/api/chat", paths.get(1));
        assertEquals(List.of("user:Who wrote Dune?", "assistant:Frank Herbert.", "user:What else did he write?"),
                messagesOf(lastBody));
        assertEquals(2, (Integer) Nodes.get(node, "Turns"));
    }

    @Test
    void twoNodesNamingTheSameConversationShareIt() throws IOException {
        String address = serve("{\"message\":{\"content\":\"Frank Herbert.\"},\"done\":true}");
        String name = aConversation();
        LocalLlmPromptNode asked = prompting(address, "Who wrote Dune?");
        Nodes.set(asked, "Conversation", name);
        LocalLlmPromptNode followingUp = prompting(address, "What else did he write?");
        Nodes.set(followingUp, "Conversation", name);

        Nodes.run(asked);
        Nodes.run(followingUp);

        assertEquals(List.of("user:Who wrote Dune?", "assistant:Frank Herbert.", "user:What else did he write?"),
                messagesOf(lastBody));
    }

    @Test
    void historyTurnsCapsWhatIsCarriedForward() throws IOException {
        String address = serve("{\"message\":{\"content\":\"ok\"},\"done\":true}");
        LocalLlmPromptNode node = prompting(address, "one");
        Nodes.set(node, "Conversation", aConversation());
        Nodes.set(node, "History Turns", 1);

        Nodes.run(node);
        Nodes.set(node, "Prompt", "two");
        Nodes.run(node);
        Nodes.set(node, "Prompt", "three");
        Nodes.run(node);

        assertEquals(List.of("user:two", "assistant:ok", "user:three"), messagesOf(lastBody));
        assertEquals(1, (Integer) Nodes.get(node, "Turns"));
    }

    @Test
    void zeroHistoryTurnsRemembersNothingWithoutUnwiringTheName() throws IOException {
        String address = serve("{\"response\":\"ok\",\"done\":true}");
        LocalLlmPromptNode node = prompting(address, "one");
        Nodes.set(node, "Conversation", aConversation());
        Nodes.set(node, "History Turns", 0);

        Nodes.run(node);
        Nodes.set(node, "Prompt", "two");
        Nodes.run(node);

        assertEquals(List.of("/api/generate", "/api/generate"), paths);
        assertEquals(0, (Integer) Nodes.get(node, "Turns"));
    }

    @Test
    void aFailedRunRecordsNothing() throws IOException {
        String address = serve("{\"response\":\"first answer\",\"done\":true}");
        String name = aConversation();
        LocalLlmPromptNode node = prompting(address, "Who wrote Dune?");
        Nodes.set(node, "Conversation", name);
        Nodes.run(node);

        // The server goes away between the two runs: the second prompt fails, and the exchange it
        // never completed must not end up in the conversation.
        server.stop(0);
        server = null;
        Nodes.set(node, "Prompt", "What else did he write?");
        assertThrows(LlmException.class, () -> Nodes.run(node));

        ClearConversationNode clear = new ClearConversationNode();
        Nodes.set(clear, "Conversation", name);
        assertEquals(1, clear.forget(name), "only the exchange that succeeded is remembered");
    }

    /** A node pointed at {@code address} with {@code question} on its Prompt input. */
    private LocalLlmPromptNode prompting(String address, String question) {
        LocalLlmPromptNode node = new LocalLlmPromptNode();
        Nodes.set(node, "Server", address);
        Nodes.set(node, "Prompt", question);
        return node;
    }

    /**
     * A conversation name no other test shares. The conversations live in the process-wide registry
     * by design (see {@code LlmConversations}), so tests that made up the same name would leak into
     * each other.
     */
    private static String aConversation() {
        return "test-" + UUID.randomUUID();
    }

    /** A chat request body's messages as {@code role:content}, for asserting on what was sent. */
    private static List<String> messagesOf(String body) {
        JSONArray messages = new JSONObject(body).getJSONArray("messages");
        List<String> flattened = new ArrayList<>(messages.length());
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.getJSONObject(index);
            flattened.add(message.getString("role") + ":" + message.getString("content"));
        }
        return flattened;
    }

    /** Starts a stub Ollama that answers every request with {@code body}, and returns its address. */
    private String serve(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            paths.add(exchange.getRequestURI().getPath());
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
