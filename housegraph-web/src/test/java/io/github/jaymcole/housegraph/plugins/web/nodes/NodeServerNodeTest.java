package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the Node-server node round-trips its inline configuration through {@code saveState}/
 * {@code loadState}, and the shape/guard rails of its Restart flow-in — the only headless
 * surfaces (actually spawning/relaunching a {@code node} process touches the OS and the network,
 * which belongs in a manual/integration check, not the unit suite).
 */
class NodeServerNodeTest {

    @Test
    void declaresARestartFlowInput() {
        NodeServerNode node = new NodeServerNode();

        assertEquals(1, node.getFlowInputs().size());
        assertEquals("Restart", node.getFlowInputs().get(0).name);
    }

    @Test
    void restartRefusesToRunWithoutAProjectDirectory() {
        NodeServerNode node = new NodeServerNode();

        assertThrows(IllegalStateException.class, () -> node.process(ProcessContext.uncancelled()),
                "the Restart flow-in shouldn't silently no-op on a node nobody has configured yet");
    }

    @Test
    void savesAndReloadsConfiguration() {
        NodeServerNode original = new NodeServerNode();
        original.loadState(Map.of(
                "name", "my-app",
                "directory", "/srv/my-app",
                "command", "node server.js",
                "port", "4000"));

        Map<String, String> saved = original.saveState();

        NodeServerNode reloaded = new NodeServerNode();
        reloaded.loadState(saved);

        assertEquals("my-app", saved.get("name"));
        assertEquals("/srv/my-app", saved.get("directory"));
        assertEquals("node server.js", saved.get("command"));
        assertEquals("4000", saved.get("port"));
        assertEquals(saved, reloaded.saveState(), "config should survive a save/load round-trip unchanged");
    }

    @Test
    void invalidPortFallsBackToDefault() {
        NodeServerNode node = new NodeServerNode();
        node.loadState(Map.of("name", "app", "port", "not-a-number"));

        assertEquals("3000", node.saveState().get("port"), "an unparseable port should fall back to the default");
    }
}
