package io.github.jaymcole.housegraph.plugins.llm.nodes;

import com.sun.net.httpserver.HttpServer;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversations;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test in this library that drives a real {@link NodeGraph}, because the bug it exists for
 * is invisible to any test that calls {@code process()} directly.
 * <p>
 * <b>What went wrong.</b> v3.0.0 put a Clear flow-in on the prompt node so a {@code /reset} command
 * could reset a conversation without a second node. Its {@code process()} was correct in isolation
 * and the node's own tests passed. In a graph it cleared the wrong person: a data input takes at
 * most one edge, so <b>Conversation ID</b> had exactly one source — the {@code /ask} command — and a
 * reset arriving at the other port still resolved the id through that edge, naming whoever last
 * asked. Which run resolves which edge is a property of the engine, so only the engine can show it.
 * <p>
 * Two Discord commands are stood in for by {@link Command}: each carries its own sender, as the real
 * node does, and each is wired to the node it triggers.
 */
class ConversationResetGraphTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void oneUsersResetLeavesAnotherUsersConversationAlone() throws IOException {
        String address = serve();
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();

        NodeGraph graph = new NodeGraph();
        Command ask = new Command("Who wrote Dune?", alice);
        Command reset = new Command("", bob);
        LocalLlmPromptNode llm = new LocalLlmPromptNode();
        ClearConversationNode clear = new ClearConversationNode();
        for (BaseNode node : List.of(ask, reset, llm, clear)) {
            graph.addNode(node);
        }
        text(llm, "Server").setValue(address);
        graph.registerEdge(new Edge(ask, ask.question, llm, input(llm, "Prompt")));
        graph.registerEdge(new Edge(ask, ask.senderId, llm, input(llm, "Conversation ID")));
        // The reset reads its own command's sender, which is the whole point of the separate node.
        graph.registerEdge(new Edge(reset, reset.senderId, clear, input(clear, "Conversation ID")));
        graph.registerFlowEdge(new FlowEdge(ask, ask.out, llm, llm.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(reset, reset.out, clear, clear.getFlowInputs().get(0)));

        graph.execute(ask);
        graph.awaitIdle();
        graph.execute(ask);
        graph.awaitIdle();
        assertEquals(2, exchangesOf(alice), "two exchanges, and no error: " + llm.getLastError());

        graph.execute(reset);
        graph.awaitIdle();

        assertEquals(2, exchangesOf(alice), "bob's reset must not touch alice's conversation");
        assertTrue(LlmConversations.shared().peek(bob).isEmpty(), "and must not invent one for bob");
        // It must also not reach the model or blank what the prompt node last published: the reset
        // runs nowhere near it, which is what a Clear port on that node could not promise.
        assertEquals(2, requests.size());
        assertEquals("an answer", Nodes.get(llm, "Response"));
    }

    @Test
    void aUsersOwnResetClearsTheirOwnConversation() throws IOException {
        String address = serve();
        String alice = "alice-" + UUID.randomUUID();

        NodeGraph graph = new NodeGraph();
        Command ask = new Command("Who wrote Dune?", alice);
        Command reset = new Command("", alice);
        LocalLlmPromptNode llm = new LocalLlmPromptNode();
        ClearConversationNode clear = new ClearConversationNode();
        for (BaseNode node : List.of(ask, reset, llm, clear)) {
            graph.addNode(node);
        }
        text(llm, "Server").setValue(address);
        graph.registerEdge(new Edge(ask, ask.question, llm, input(llm, "Prompt")));
        graph.registerEdge(new Edge(ask, ask.senderId, llm, input(llm, "Conversation ID")));
        graph.registerEdge(new Edge(reset, reset.senderId, clear, input(clear, "Conversation ID")));
        graph.registerFlowEdge(new FlowEdge(ask, ask.out, llm, llm.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(reset, reset.out, clear, clear.getFlowInputs().get(0)));

        graph.execute(ask);
        graph.awaitIdle();
        graph.execute(reset);
        graph.awaitIdle();

        assertTrue(LlmConversations.shared().peek(alice).isEmpty());
        assertEquals(1, (Integer) Nodes.get(clear, "Forgotten"));
        // And the next question starts over rather than carrying the forgotten turn.
        graph.execute(ask);
        graph.awaitIdle();
        assertEquals(1, exchangesOf(alice));
        // The reset made no request of its own, so the second one is the question after it: one
        // message, the new turn, with nothing carried over. Asserted on the parsed body because
        // org.json does not promise key order.
        assertEquals(2, requests.size());
        assertEquals(1, new JSONObject(requests.get(1)).getJSONArray("messages").length());
    }

    private static int exchangesOf(String conversation) {
        return LlmConversations.shared().peek(conversation).map(held -> held.exchanges()).orElse(0);
    }

    @SuppressWarnings("rawtypes")
    private static NodeVariable input(BaseNode node, String name) {
        return node.getInputs().stream().filter(variable -> variable.name.equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no " + name + " input"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NodeVariable<String> text(BaseNode node, String name) {
        return (NodeVariable) input(node, name);
    }

    /** A stub Ollama that answers every prompt the same way, recording the message array it saw. */
    private String serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"message\":{\"content\":\"an answer\"},\"done\":true}"
                    .getBytes(StandardCharsets.UTF_8);
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

    /** A Discord slash command's shape: the option it carries, who ran it, and a flow-out. */
    private static class Command extends BaseNode {

        private final NodeVariable<String> question = new NodeVariable<>("question", String.class);
        private final NodeVariable<String> senderId = new NodeVariable<>("Sender ID", String.class);
        private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

        Command(String question, String senderId) {
            this.question.setValue(question);
            this.senderId.setValue(senderId);
        }

        @Override
        public void process(ProcessContext ctx) {
            // As on the real node: the outputs come from the invocation, not from a computation.
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(question);
            addOutput(senderId);
        }

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(out);
        }
    }
}
