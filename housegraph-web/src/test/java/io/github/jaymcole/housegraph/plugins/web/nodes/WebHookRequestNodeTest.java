package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.plugins.web.RouteRegistry;
import io.github.jaymcole.housegraph.plugins.web.WebHookEvent;
import io.github.jaymcole.housegraph.plugins.web.WebHookReply;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the hold-and-reply trigger: it declares its route with {@code awaitReply} true and
 * the configured timeout, and captures the event's reply handle onto its {@code Reply} output so
 * a downstream {@link WebHookReplyNode} can answer it.
 */
class WebHookRequestNodeTest {

    @Test
    void declaresARouteThatAwaitsReplyWithTheConfiguredTimeout() {
        NodeGraph graph = new NodeGraph();
        WebHookRequestNode node = new WebHookRequestNode();
        node.loadState(Map.of("resource", "srv-req-a", "path", "/ask", "method", "POST", "timeoutSeconds", "45"));

        graph.addNode(node);

        var route = RouteRegistry.shared().find("srv-req-a", "POST", "/ask");
        assertTrue(route.isPresent());
        assertTrue(route.get().awaitReply());
        assertEquals(45, route.get().timeoutSeconds());
    }

    @Test
    void defaultsToAThirtySecondTimeout() {
        assertEquals(30, new WebHookRequestNode().timeoutSeconds());
        assertEquals("30", new WebHookRequestNode().saveState().get("timeoutSeconds"));
    }

    @Test
    void capturesTheReplyHandleFromTheMatchedEventOntoItsOutput() {
        NodeGraph graph = new NodeGraph();
        WebHookRequestNode node = new WebHookRequestNode();
        node.loadState(Map.of("resource", "srv-req-b", "path", "/ask", "method", "POST"));
        graph.addNode(node);

        AtomicReference<Integer> repliedStatus = new AtomicReference<>();
        WebHookReply reply = (status, contentType, body) -> repliedStatus.set(status);
        ResourceRegistry.shared().publish("srv-req-b",
                new WebHookEvent("POST", "/ask", Map.of(), Map.of(), "{}", reply));
        graph.awaitIdle();

        Object captured = node.getOutputs().stream()
                .filter(output -> output.name.equals("Reply"))
                .findFirst().orElseThrow().getValue();
        assertSame(reply, captured, "the Reply output should be exactly the event's reply handle");

        ((WebHookReply) captured).reply(201, "text/plain", "ok");
        assertEquals(201, repliedStatus.get(), "sanity check: the captured handle really is the caller's");
    }

    @Test
    void savesAndReloadsTheTimeout() {
        WebHookRequestNode original = new WebHookRequestNode();
        original.loadState(Map.of("resource", "srv-req-c", "path", "/ask", "method", "POST", "timeoutSeconds", "5"));

        Map<String, String> saved = original.saveState();

        WebHookRequestNode reloaded = new WebHookRequestNode();
        reloaded.loadState(saved);

        assertEquals(5, reloaded.timeoutSeconds());
    }
}
