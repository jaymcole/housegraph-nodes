package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.store.JsonDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the web-server node's port-driven configuration and its three-way flow-in branch
 * (Start / Stop / Rebuild, told apart via {@code ProcessContext.wasTriggeredVia}) — staying off
 * the network by routing every input-resolution test through Rebuild, which never binds the HTTP
 * server, and by only exercising Start through its guard rail (a missing Directory throws before
 * ever reaching the bind). Actually binding a socket and advertising mDNS is deliberately left to
 * a manual/integration check, the same reasoning {@code buildCommand} execution already followed
 * here and {@code NodeServerNodeTest} follows for spawning a real process.
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

    /** A minimal flow source: one unnamed OUT port, fired by {@code execute()} — stands in for a trigger node. */
    private static final class Trigger extends BaseNode {
        private final FlowPort out = new FlowPort("Out", FlowPort.Direction.OUT);

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

    private static NodeVariable<?> inputNamed(BaseNode node, String name) {
        return node.getInputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No input named \"" + name + "\""));
    }

    /** Wires a fresh {@link Trigger} into {@code targetPort} of {@code web} and fires it, blocking until the run settles. */
    private static void fire(NodeGraph graph, WebServerNode web, FlowPort targetPort) {
        Trigger trigger = new Trigger();
        graph.addNode(trigger);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), web, targetPort));
        trigger.execute();
        graph.awaitIdle();
    }

    @Test
    void declaresItsDataInputsInDisplayOrder() {
        WebServerNode web = new WebServerNode();

        assertEquals(List.of("Name", "Directory", "Output Folder", "Build Command", "Port", "Proxy Target", "Store"),
                web.getInputs().stream().map(v -> v.name).toList());
    }

    @Test
    void declaresStartStopAndRebuildFlowInputs() {
        WebServerNode web = new WebServerNode();

        assertEquals(List.of("Start", "Stop", "Rebuild"),
                web.getFlowInputs().stream().map(p -> p.name).toList());
    }

    @Test
    void capturesWiredStoreWhenResolvedViaRebuild(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        StoreSource source = new StoreSource(store);
        WebServerNode web = new WebServerNode();
        graph.addNode(source);
        graph.addNode(web);
        graph.registerEdge(new Edge(source, source.out, web, inputNamed(web, "Store")));

        fire(graph, web, web.getFlowInputs().get(2)); // Rebuild — resolves inputs without binding

        assertSame(store, web.resolvedStore(), "web server should capture the store handle off its input edge");
    }

    @Test
    void noStoreWiredLeavesCaptureNull() {
        WebServerNode web = new WebServerNode();

        assertNull(web.resolvedStore(), "with nothing wired the server serves static-only");
    }

    @Test
    void capturesWiredDirectoryViaRebuild(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        String folderPath = dir.resolve("site").toString();
        FolderSource source = new FolderSource(folderPath);
        WebServerNode web = new WebServerNode();
        graph.addNode(source);
        graph.addNode(web);
        graph.registerEdge(new Edge(source, source.out, web, inputNamed(web, "Directory")));

        fire(graph, web, web.getFlowInputs().get(2)); // Rebuild — resolves inputs without binding

        assertEquals(folderPath, web.resolvedDirectory(),
                "web server should capture the directory off its Directory input edge");
    }

    @Test
    void legacyDirectoryInSavedStateMigratesOntoTheDirectoryInput() {
        WebServerNode web = new WebServerNode();

        web.loadState(Map.of("name", "site", "directory", "/srv/site"));

        assertEquals("/srv/site", inputNamed(web, "Directory").getValue(),
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
    void aStartAttemptWithNoDirectoryConfiguredFailsLoudly() {
        WebServerNode web = new WebServerNode();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> web.process(ProcessContext.uncancelled()),
                "an empty triggeredVia() defaults to a Start attempt, which must report a missing "
                        + "Directory rather than silently doing nothing");
        assertEquals("Pick a website directory first", error.getMessage());
    }

    @Test
    void rebuildWithNoDirectoryResolvedYetIsAQuietNoOp(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        graph.addNode(web);

        fire(graph, web, web.getFlowInputs().get(2)); // Rebuild, nothing wired at all

        assertNull(web.getLastError(),
                "with no Directory wired or typed in yet, there's nothing to build in — Rebuild "
                        + "should quietly skip rather than fail (Start's own directory check is what "
                        + "reports that case to the user)");
    }

    @Test
    void stopNeverThrowsEvenWhenNothingWasEverStarted() {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        graph.addNode(web);

        fire(graph, web, web.getFlowInputs().get(1)); // Stop

        assertNull(web.getLastError(), "stopping a server that was never running must be a safe no-op");
    }

    @Test
    void rebuildWithClearedBuildCommandDoesNotAttemptABuild(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        web.loadState(Map.of("name", "site", "directory", dir.toString(), "buildCommand", ""));
        graph.addNode(web);

        fire(graph, web, web.getFlowInputs().get(2)); // Rebuild

        assertNull(web.getLastError(),
                "clearing buildCommand opts out of the build step, e.g. for a hand-authored site "
                        + "with no build tooling — Rebuild must not try to run a blank command");
    }

    @Test
    void savesAndReloadsBuildConfiguration() {
        WebServerNode original = new WebServerNode();
        original.loadState(Map.of("name", "site", "buildCommand", "yarn build"));

        assertEquals("yarn build", inputNamed(original, "Build Command").getValue());
    }

    @Test
    void anExplicitlyEmptiedBuildCommandLandsAsBlankNotTheDefault() {
        WebServerNode original = new WebServerNode();
        original.loadState(Map.of("name", "site", "buildCommand", ""));

        assertEquals("", inputNamed(original, "Build Command").getValue(),
                "opting out must migrate as blank, not silently revert to the \"npm run build\" default");
    }

    @Test
    void aFreshNodeDefaultsToBuildingWithNpmRunBuild() {
        assertEquals("npm run build", inputNamed(new WebServerNode(), "Build Command").getValue(),
                "matches Vite's/most JS bundlers' default build script, so a freshly-wired project "
                        + "root builds and serves without extra configuration");
    }

    @Test
    void aFreshNodeDefaultsToServingTheDistSubfolder() {
        assertEquals("dist", inputNamed(new WebServerNode(), "Output Folder").getValue(),
                "matches Vite's default build output folder name, so a freshly-wired project root "
                        + "serves its build output rather than raw, untranspiled source");
    }

    @Test
    void aFreshNodeDefaultsToPort8080() {
        assertEquals(8080, inputNamed(new WebServerNode(), "Port").getValue());
    }

    @Test
    void aFreshNodeDefaultsToTheHousegraphName() {
        assertEquals("housegraph", inputNamed(new WebServerNode(), "Name").getValue());
    }

    @Test
    void servedRootAppendsOutputFolderToTheResolvedDirectory(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        web.loadState(Map.of("name", "site", "directory", dir.toString()));
        graph.addNode(web);

        fire(graph, web, web.getFlowInputs().get(2)); // Rebuild — resolves without binding

        assertEquals(dir.resolve("dist"), web.servedRoot(),
                "with the default outputFolder, the server should serve Directory's dist subfolder");
    }

    @Test
    void blankOutputFolderServesTheDirectoryItself(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        web.loadState(Map.of("name", "site", "directory", dir.toString(), "outputFolder", ""));
        graph.addNode(web);

        fire(graph, web, web.getFlowInputs().get(2)); // Rebuild — resolves without binding

        assertEquals(dir, web.servedRoot(),
                "an explicitly emptied outputFolder should opt back out of the subfolder and serve "
                        + "Directory directly, e.g. for a hand-authored site with no build step");
    }

    @Test
    void anExplicitlyEmptiedOutputFolderLandsAsBlankNotTheDefault() {
        WebServerNode original = new WebServerNode();
        original.loadState(Map.of("name", "site", "outputFolder", ""));

        assertEquals("", inputNamed(original, "Output Folder").getValue(),
                "opting out must migrate as blank, not silently revert to the \"dist\" default");
    }
}
