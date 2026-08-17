package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.web.RouteRegistry;
import io.github.jaymcole.housegraph.plugins.web.WebHookEvent;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the fire-and-forget trigger: it declares its route (with {@code awaitReply} false),
 * fires only on a matching method/path, and sets its outputs from the event. Driven through
 * {@code ResourceRegistry.publish} directly rather than real HTTP, since {@code LocalWebServer}'s
 * dispatcher is covered by {@code LocalWebServerTest}.
 */
class WebHookNodeTest {

    /** A downstream node whose {@code process()} records whether it ran, for the flow-out assertion. */
    private static final class Downstream extends BaseNode {
        boolean ran;

        @Override
        public void process(ProcessContext ctx) {
            ran = true;
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(new FlowPort("", FlowPort.Direction.IN));
        }
    }

    private static WebHookEvent event(String method, String path) {
        return new WebHookEvent(method, path, Map.of("X-Signal", "chime"), Map.of("ring", "1"), "{\"note\":\"hi\"}", null);
    }

    @Test
    void firesOnMatchingEventAndSetsOutputs() {
        NodeGraph graph = new NodeGraph();
        WebHookNode hook = new WebHookNode();
        hook.loadState(Map.of("resource", "srv-a", "path", "/doorbell", "method", "POST"));
        Downstream downstream = new Downstream();
        graph.addNode(hook);
        graph.addNode(downstream);
        graph.registerFlowEdge(new FlowEdge(hook, hook.getFlowOutputs().get(0), downstream, downstream.getFlowInputs().get(0)));

        ResourceRegistry.shared().publish("srv-a", event("POST", "/doorbell"));
        graph.awaitIdle();

        assertTrue(downstream.ran, "a matching request should fire the flow-out");
        assertEquals("POST", hook.method.getValue());
        assertEquals("/doorbell", hook.path.getValue());
        assertEquals("chime", hook.headers.getValue().get("X-Signal"));
        assertEquals("1", hook.query.getValue().get("ring"));
        assertEquals("{\"note\":\"hi\"}", hook.body.getValue());
    }

    @Test
    void ignoresAnEventForADifferentPath() {
        NodeGraph graph = new NodeGraph();
        WebHookNode hook = new WebHookNode();
        hook.loadState(Map.of("resource", "srv-b", "path", "/doorbell", "method", "POST"));
        Downstream downstream = new Downstream();
        graph.addNode(hook);
        graph.addNode(downstream);
        graph.registerFlowEdge(new FlowEdge(hook, hook.getFlowOutputs().get(0), downstream, downstream.getFlowInputs().get(0)));

        ResourceRegistry.shared().publish("srv-b", event("POST", "/other"));
        graph.awaitIdle();

        assertFalse(downstream.ran, "a non-matching path must not fire this node's route");
    }

    @Test
    void ignoresAnEventForADifferentMethod() {
        NodeGraph graph = new NodeGraph();
        WebHookNode hook = new WebHookNode();
        hook.loadState(Map.of("resource", "srv-c", "path", "/doorbell", "method", "POST"));
        Downstream downstream = new Downstream();
        graph.addNode(hook);
        graph.addNode(downstream);
        graph.registerFlowEdge(new FlowEdge(hook, hook.getFlowOutputs().get(0), downstream, downstream.getFlowInputs().get(0)));

        ResourceRegistry.shared().publish("srv-c", event("GET", "/doorbell"));
        graph.awaitIdle();

        assertFalse(downstream.ran, "a non-matching method must not fire this node's route");
    }

    @Test
    void declaresARouteWithoutAwaitingReplyOnceActivated() {
        NodeGraph graph = new NodeGraph();
        WebHookNode hook = new WebHookNode();
        hook.loadState(Map.of("resource", "srv-d", "path", "/lights", "method", "PUT"));

        graph.addNode(hook);

        var route = RouteRegistry.shared().find("srv-d", "PUT", "/lights");
        assertTrue(route.isPresent(), "activating the node should declare its route");
        assertFalse(route.get().awaitReply(), "a plain Web Hook never holds the response");
    }

    @Test
    void withdrawsItsRouteWhenRemoved() {
        NodeGraph graph = new NodeGraph();
        WebHookNode hook = new WebHookNode();
        hook.loadState(Map.of("resource", "srv-e", "path", "/lights", "method", "PUT"));
        graph.addNode(hook);

        graph.removeNode(hook);

        assertTrue(RouteRegistry.shared().find("srv-e", "PUT", "/lights").isEmpty(),
                "removing the node should withdraw its declared route");
    }

    @Test
    void defaultsToAPostRouteNamedHook() {
        Map<String, String> saved = new WebHookNode().saveState();

        assertEquals("/hook", saved.get("path"));
        assertEquals("POST", saved.get("method"));
    }

    @Test
    void savesAndReloadsPathMethodAndResourceName() {
        WebHookNode original = new WebHookNode();
        original.loadState(Map.of("resource", "srv-f", "path", "/trigger", "method", "GET"));

        Map<String, String> saved = original.saveState();

        WebHookNode reloaded = new WebHookNode();
        reloaded.loadState(saved);

        assertEquals(saved, reloaded.saveState(), "path/method/resource should survive a save/load round-trip");
        assertEquals("srv-f", saved.get("resource"));
        assertEquals("/trigger", saved.get("path"));
        assertEquals("GET", saved.get("method"));
    }
}
