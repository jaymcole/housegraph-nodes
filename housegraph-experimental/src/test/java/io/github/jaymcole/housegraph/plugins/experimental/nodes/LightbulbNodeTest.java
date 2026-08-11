package io.github.jaymcole.housegraph.plugins.experimental.nodes;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless coverage only — the bulb itself is a {@link NodeContentProvider} JavaFX scene graph,
 * which needs a live FX toolkit and isn't exercised here (see other libraries' node tests for
 * the same split). This stays focused on the node's declared ports and its no-op, always-passes-
 * through process().
 */
class LightbulbNodeTest {

    @Test
    void isAContentProvider() {
        assertTrue(new LightbulbNode() instanceof NodeContentProvider);
    }

    @Test
    void declaresOneUnnamedFlowInAndOneUnnamedFlowOut() {
        LightbulbNode node = new LightbulbNode();

        assertEquals(1, node.getFlowInputs().size());
        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowInputs().get(0).name);
        assertEquals("", node.getFlowOutputs().get(0).name);
    }

    @Test
    void hasNoDataPorts() {
        LightbulbNode node = new LightbulbNode();

        assertTrue(node.getInputs().isEmpty());
        assertTrue(node.getOutputs().isEmpty());
    }

    @Test
    void processDoesNotThrowAndActivatesNothingExplicitly() {
        // No ExecutionContext bound outside a graph, so activate() is a no-op here; the engine's
        // "activated nothing -> fire everything" default is what actually makes the flow-out fire,
        // and that's graph-cascade behavior covered elsewhere, not this node's own logic.
        new LightbulbNode().process(ProcessContext.uncancelled());
    }
}
