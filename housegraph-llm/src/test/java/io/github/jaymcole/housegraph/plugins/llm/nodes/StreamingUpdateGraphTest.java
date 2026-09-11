package io.github.jaymcole.housegraph.plugins.llm.nodes;

import com.sun.net.httpserver.HttpServer;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Update port, driven by a real {@link NodeGraph} — the only way to test it, because firing a
 * flow branch repeatedly during one {@code process()} is something only the engine can do. A test
 * calling {@code process()} directly would fail on a node that belongs to no graph.
 * <p>
 * <b>What is being pinned down.</b> Updates arrive in order, during the run rather than after it,
 * and the last thing any consumer sees is the finished answer — the property the whole design
 * turns on, since the case this was built for is a Discord message edited repeatedly in place, and
 * a status update landing after the answer would overwrite the answer with "thinking…".
 * <p>
 * The updates a run happens to publish are <em>not</em> pinned down, because they depend on how
 * the model's output falls across the interval. What every run promises is the order, and the
 * answer at the end.
 */
class StreamingUpdateGraphTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void updatesArriveDuringTheRunAndTheAnswerArrivesLast() throws IOException {
        String address = streaming(
                "{\"response\":\"Frank \",\"done\":false}",
                "{\"response\":\"Herbert \",\"done\":false}",
                "{\"response\":\"wrote it.\",\"done\":false}",
                "{\"response\":\"\",\"done\":true}");

        NodeGraph graph = new NodeGraph();
        Trigger trigger = new Trigger();
        LocalLlmPromptNode llm = new LocalLlmPromptNode();
        Recorder updates = new Recorder();
        Recorder finished = new Recorder();
        for (BaseNode node : List.of(trigger, llm, updates, finished)) {
            graph.addNode(node);
        }
        text(llm, "Server").setValue(address);
        text(llm, "Prompt").setValue("Who wrote Dune?");
        // Every piece gets its own update: one millisecond is shorter than a socket round trip.
        number(llm, "Update Every (ms)").setValue(1);

        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, llm, llm.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(llm, flowOut(llm, "Update"), updates, updates.in));
        graph.registerFlowEdge(new FlowEdge(llm, flowOut(llm, ""), finished, finished.in));
        // Each recorder reads what a status display would: the running total, and the answer.
        graph.registerEdge(new Edge(llm, output(llm, "Answer So Far"), updates, updates.text));
        graph.registerEdge(new Edge(llm, output(llm, "Response"), finished, finished.text));

        graph.execute(trigger);
        graph.awaitIdle();

        assertNull(llm.getLastError(), "the run failed: " + llm.getLastError());
        assertFalse(updates.seen().isEmpty(), "the Update port never fired");
        // Every update carried strictly more of the answer than the one before it, in order - which
        // is what makes it safe to edit one message repeatedly with them.
        assertEquals(sorted(updates.seen()), updates.seen(), "updates arrived out of order: " + updates.seen());
        assertEquals("Frank ", updates.seen().get(0), "the first update should be a partial answer");
        // And the run ended with the whole thing, exactly once, after every update had landed.
        assertEquals(List.of("Frank Herbert wrote it."), finished.seen());
    }

    @Test
    void anUpdateCarriesNoResponseSoNothingDownstreamShowsTheLastRunsAnswer() throws IOException {
        String address = streaming(
                "{\"response\":\"Frank Herbert.\",\"done\":false}",
                "{\"response\":\"\",\"done\":true}");

        NodeGraph graph = new NodeGraph();
        Trigger trigger = new Trigger();
        LocalLlmPromptNode llm = new LocalLlmPromptNode();
        Recorder updates = new Recorder();
        for (BaseNode node : List.of(trigger, llm, updates)) {
            graph.addNode(node);
        }
        text(llm, "Server").setValue(address);
        text(llm, "Prompt").setValue("Who wrote Dune?");
        number(llm, "Update Every (ms)").setValue(1);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, llm, llm.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(llm, flowOut(llm, "Update"), updates, updates.in));
        graph.registerEdge(new Edge(llm, output(llm, "Response"), updates, updates.text));

        graph.execute(trigger);
        graph.awaitIdle();
        graph.execute(trigger);
        graph.awaitIdle();

        // A run in progress has no answer, and publishing the previous one would put the last
        // person's reply into this person's status message.
        for (String seen : updates.seen()) {
            assertEquals("", seen, "an Update published a Response: " + updates.seen());
        }
    }

    @Test
    void aRunThatDoesNotStreamNeverFiresUpdate() throws IOException {
        String address = streaming("{\"response\":\"Frank Herbert.\",\"done\":true}");

        NodeGraph graph = new NodeGraph();
        Trigger trigger = new Trigger();
        LocalLlmPromptNode llm = new LocalLlmPromptNode();
        Recorder updates = new Recorder();
        Recorder finished = new Recorder();
        for (BaseNode node : List.of(trigger, llm, updates, finished)) {
            graph.addNode(node);
        }
        text(llm, "Server").setValue(address);
        text(llm, "Prompt").setValue("Who wrote Dune?");
        // Update Every (ms) left at its default.
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, llm, llm.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(llm, flowOut(llm, "Update"), updates, updates.in));
        graph.registerFlowEdge(new FlowEdge(llm, flowOut(llm, ""), finished, finished.in));
        graph.registerEdge(new Edge(llm, output(llm, "Response"), finished, finished.text));

        graph.execute(trigger);
        graph.awaitIdle();

        // The engine fires every flow-out of a node that activated none, so without the node's own
        // activate() this would fire one Update alongside the answer at the end of every run.
        assertEquals(List.of(), updates.seen(), "Update fired on a run that does not stream");
        assertEquals(List.of("Frank Herbert."), finished.seen());
    }

    @Test
    void reasoningIsPublishedWhileItHappensAndTheAnswerFollowsIt() throws IOException {
        String address = streaming(
                "{\"thinking\":\"Dune. \",\"response\":\"\",\"done\":false}",
                "{\"thinking\":\"A book.\",\"response\":\"\",\"done\":false}",
                "{\"thinking\":\"\",\"response\":\"Frank Herbert.\",\"done\":false}",
                "{\"done\":true}");

        NodeGraph graph = new NodeGraph();
        Trigger trigger = new Trigger();
        LocalLlmPromptNode llm = new LocalLlmPromptNode();
        Recorder phases = new Recorder();
        for (BaseNode node : List.of(trigger, llm, phases)) {
            graph.addNode(node);
        }
        text(llm, "Server").setValue(address);
        text(llm, "Prompt").setValue("Who wrote Dune?");
        text(llm, "Think").setValue("true");
        number(llm, "Update Every (ms)").setValue(1);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, llm, llm.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(llm, flowOut(llm, "Update"), phases, phases.in));
        graph.registerEdge(new Edge(llm, output(llm, "Phase"), phases, phases.text));

        graph.execute(trigger);
        graph.awaitIdle();

        assertTrue(phases.seen().contains("thinking"), "no thinking phase: " + phases.seen());
        assertTrue(phases.seen().contains("answering"), "no answering phase: " + phases.seen());
        // Thinking comes first and never comes back once the answer has started.
        assertEquals(phases.seen().lastIndexOf("thinking") + 1, phases.seen().indexOf("answering"),
                "the phases interleaved: " + phases.seen());
        assertEquals("Dune. A book.", Nodes.get(llm, "Thinking"));
        assertEquals("Frank Herbert.", Nodes.get(llm, "Response"));
    }

    private static List<String> sorted(List<String> seen) {
        List<String> ordered = new ArrayList<>(seen);
        Collections.sort(ordered);
        return ordered;
    }

    @SuppressWarnings("rawtypes")
    private static NodeVariable input(BaseNode node, String name) {
        return node.getInputs().stream().filter(variable -> variable.name.equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no " + name + " input"));
    }

    @SuppressWarnings("rawtypes")
    private static NodeVariable output(BaseNode node, String name) {
        return node.getOutputs().stream().filter(variable -> variable.name.equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no " + name + " output"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NodeVariable<String> text(BaseNode node, String name) {
        return (NodeVariable) input(node, name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NodeVariable<Integer> number(BaseNode node, String name) {
        return (NodeVariable) input(node, name);
    }

    private static FlowPort flowOut(BaseNode node, String name) {
        return node.getFlowOutputs().stream().filter(port -> port.name.equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no \"" + name + "\" flow output"));
    }

    /** A stub that writes {@code lines} one at a time, flushing each, as a streaming server does. */
    private String streaming(String... lines) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String line : lines) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // A real model does not emit its whole answer in one burst, and these tests are
                    // about what happens between pieces.
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Something to fire the run from, standing in for a Discord command or a button. */
    private static class Trigger extends BaseNode {

        private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

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

    /** A Discord Reply node's shape: a flow-in, a text input, and a record of what it was sent. */
    private static class Recorder extends BaseNode {

        private final NodeVariable<String> text = new NodeVariable<>("Message", String.class, true);
        private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
        private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void process(ProcessContext ctx) {
            String message = text.getValue();
            seen.add(message == null ? "" : message);
        }

        List<String> seen() {
            return List.copyOf(seen);
        }

        @Override
        public void configureInputs() {
            addInput(text);
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(in);
        }
    }
}
