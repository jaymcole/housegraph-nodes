package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.store.JsonDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the web-server node captures the data-store handle off its {@code Store} data edge
 * when it starts — the pull-at-Start wiring that replaced the old name-reference — plus the
 * shape/guard rails of its Rebuild flow-in. Uses a synthetic upstream source so it stays headless
 * and off disk (actually running a build command touches the OS, which belongs in a manual/
 * integration check, not the unit suite — see {@code NodeServerNodeTest}'s Restart tests for the
 * same reasoning).
 */
class WebServerNodeTest {

    /** A minimal data node that outputs a fixed store handle — stands in for {@code DataStoreNode}. */
    private static final class StoreSource extends BaseNode {
        private final NodeVariable<JsonDocumentStore> out =
                new NodeVariable<>("Store", JsonDocumentStore.class).transientValue();
        private final JsonDocumentStore store;

        StoreSource(JsonDocumentStore store) {
            this.store = store;
        }

        @Override
        public void process(ProcessContext ctx) {
            out.setValue(store);
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(out);
        }
    }

    /** A minimal data node that outputs a fixed path — stands in for {@code CreateFolderNode}. */
    private static final class FolderSource extends BaseNode {
        private final NodeVariable<String> out = new NodeVariable<>("Folder Path", String.class);
        private final String path;

        FolderSource(String path) {
            this.path = path;
        }

        @Override
        public void process(ProcessContext ctx) {
            out.setValue(path);
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(out);
        }
    }

    @Test
    void capturesWiredStoreWhenResolved(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        StoreSource source = new StoreSource(store);
        WebServerNode web = new WebServerNode();
        graph.addNode(source);
        graph.addNode(web);
        graph.registerEdge(new Edge(source, source.out, web, web.getInputs().get(0)));

        web.beginProcessing();

        assertSame(store, web.resolvedStore(), "web server should capture the store handle off its input edge");
    }

    @Test
    void noStoreWiredLeavesCaptureNull() {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        graph.addNode(web);

        web.beginProcessing();

        assertNull(web.resolvedStore(), "with nothing wired the server serves static-only");
    }

    @Test
    void capturesWiredDirectoryWhenResolved(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        String folderPath = dir.resolve("site").toString();
        FolderSource source = new FolderSource(folderPath);
        WebServerNode web = new WebServerNode();
        graph.addNode(source);
        graph.addNode(web);
        graph.registerEdge(new Edge(source, source.out, web, web.getInputs().get(1)));

        web.beginProcessing();

        assertEquals(folderPath, web.resolvedDirectory(),
                "web server should capture the directory off its Directory input edge");
    }

    @Test
    void legacyDirectoryInSavedStateMigratesOntoTheDirectoryInput() {
        WebServerNode web = new WebServerNode();

        web.loadState(Map.of("name", "site", "directory", "/srv/site"));

        assertEquals("/srv/site", web.getInputs().get(1).getValue(),
                "a pre-input-port save's directory should land on the new Directory input");
    }

    @Test
    void aStoppedServerWritesNoRunningFlag() {
        assertFalse(new WebServerNode().saveState().containsKey("running"),
                "a server that isn't serving must not persist a running flag");
    }

    @Test
    void aRunningFlagInSavedStateSchedulesAutoStart() {
        WebServerNode web = new WebServerNode();
        assertFalse(web.wasRunning(), "a fresh node has no pending auto-start");

        web.loadState(Map.of("name", "site", "port", "8080", "running", "true"));

        assertTrue(web.wasRunning(), "a graph saved while serving reloads with auto-start pending");
    }

    @Test
    void declaresARebuildFlowInput() {
        WebServerNode web = new WebServerNode();

        assertEquals(1, web.getFlowInputs().size());
        assertEquals("Rebuild", web.getFlowInputs().get(0).name);
    }

    @Test
    void rebuildWithNoBuildDirectoryConfiguredIsANoOp() {
        WebServerNode web = new WebServerNode();

        assertDoesNotThrow(() -> web.process(ProcessContext.uncancelled()),
                "with no build step configured, Rebuild should do nothing rather than fail — "
                        + "most sites here are hand-authored, not built from source");
    }

    @Test
    void savesAndReloadsBuildConfiguration() {
        WebServerNode original = new WebServerNode();
        original.loadState(Map.of(
                "name", "site",
                "buildDirectory", "/srv/site-src",
                "buildCommand", "yarn build"));

        Map<String, String> saved = original.saveState();

        WebServerNode reloaded = new WebServerNode();
        reloaded.loadState(saved);

        assertEquals("/srv/site-src", saved.get("buildDirectory"));
        assertEquals("yarn build", saved.get("buildCommand"));
        assertEquals(saved, reloaded.saveState(), "build config should survive a save/load round-trip unchanged");
    }

    @Test
    void aFreshNodeHasNoBuildDirectoryConfigured() {
        assertFalse(new WebServerNode().saveState().containsKey("buildDirectory"),
                "a node nobody has pointed at a source project shouldn't persist a build directory");
    }

    @Test
    void aFreshNodeDefaultsToServingTheDistSubfolder() {
        assertEquals("dist", new WebServerNode().saveState().get("outputFolder"),
                "matches Vite's default build output folder name, so a freshly-wired project root "
                        + "serves its build output rather than raw, untranspiled source");
    }

    @Test
    void servedRootAppendsOutputFolderToTheResolvedDirectory(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        web.loadState(Map.of("name", "site", "directory", dir.toString()));
        graph.addNode(web);

        web.beginProcessing();

        assertEquals(dir.resolve("dist"), web.servedRoot(),
                "with the default outputFolder, the server should serve Directory's dist subfolder");
    }

    @Test
    void blankOutputFolderServesTheDirectoryItself(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        web.loadState(Map.of("name", "site", "directory", dir.toString(), "outputFolder", ""));
        graph.addNode(web);

        web.beginProcessing();

        assertEquals(dir, web.servedRoot(),
                "an explicitly emptied outputFolder should opt back out of the subfolder and serve "
                        + "Directory directly, e.g. for a hand-authored site with no build step");
    }

    @Test
    void anExplicitlyEmptiedOutputFolderRoundTripsAsBlankNotTheDefault() {
        WebServerNode original = new WebServerNode();
        original.loadState(Map.of("name", "site", "outputFolder", ""));

        Map<String, String> saved = original.saveState();

        assertEquals("", saved.get("outputFolder"),
                "opting out must persist as blank, not silently revert to the \"dist\" default");
    }
}
