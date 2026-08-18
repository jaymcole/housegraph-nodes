package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.store.JsonDocumentStore;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import io.github.jaymcole.housegraph.plugins.web.DocumentApi;
import io.github.jaymcole.housegraph.plugins.web.LocalWebServer;
import io.github.jaymcole.housegraph.plugins.web.LocalWebServer.ProxyRoute;
import io.github.jaymcole.housegraph.plugins.web.SiteBuilder;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Hosts a directory of static files as a website on the local network, reachable at
 * {@code http://<name>.local:<port>/}. A long-lived resource node managed exactly like
 * the Discord bot node: it registers a {@link LocalWebServer} under its chosen name so
 * other nodes can find it, and its liveness is user-driven (Start/Stop) rather than tied
 * to graph flow — but "user-driven" now includes another node's flow-out, not just the
 * inline buttons; see below.
 * <p>
 * Every setting — name, directory, output folder, build command, port, proxy target — is
 * an ordinary data input: typed in directly on the node, or wired from an upstream node
 * (e.g. a Create Folder or Git Sync output). Only {@code Directory} is required; the rest
 * default sensibly and are resolved fresh every time this node runs, so rewiring one takes
 * effect on the next Start/Restart without reopening the node.
 * <p>
 * Three flow-in ports drive the server's lifecycle, all landing in {@link #process}, which
 * tells them apart with {@link ProcessContext#wasTriggeredVia(FlowPort)}:
 * <ul>
 *   <li><b>Start</b> — resolves every input, runs the build step (if configured), and
 *       (re)binds the HTTP server.</li>
 *   <li><b>Stop</b> — tears the server down; nothing else runs.</li>
 *   <li><b>Rebuild</b> — runs the build step only, without touching the running server.
 *       {@link LocalWebServer} already rereads the served directory from disk on every
 *       request, so a fresh build is all a content update needs; wire something's flow-out
 *       into it (e.g. {@code housegraph-github}'s Git Sync {@code Pulled} port) to rebuild
 *       whenever new source lands.</li>
 * </ul>
 * The inline Start/Stop/Copy URL buttons are a convenience, not a separate code path: Start
 * calls {@link #beginProcessing()} (a plain pull, so {@code ctx.triggeredVia()} reads empty),
 * which {@link #process} treats the same as an explicit Start arrival; Stop calls the same
 * teardown {@link #process} uses for a Stop arrival. Either a button or a wired trigger reaches
 * the same code.
 * <p>
 * To give the hosted site shared, persisted storage, wire a {@code DataStoreNode}'s output
 * into this node's <b>Store</b> data input; the server then exposes it at {@code /api/data}.
 * With nothing wired, the site is served static-only and {@code /api/data} returns 503.
 * <p>
 * If it was serving when the graph was saved, it resumes automatically on load: the running flag
 * rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses Start for the
 * user once the graph (including every wired input) is fully loaded (see {@link AutoStartable}).
 * <p>
 * Files are served straight off disk, so an edit that lands directly in the served directory — a
 * hand-authored HTML/CSS/JS site, say — shows up on the very next request with nothing further
 * needed. A site built from source (a React app compiled into the served directory by a bundler)
 * is different: the served files only change when something reruns that build. That's why
 * {@link #buildCommand} (e.g. {@code npm run build}, the default) runs in {@code Directory} both
 * at Start and via the <b>Rebuild</b> flow-in — wire something's flow-out into Rebuild (e.g.
 * {@code housegraph-github}'s Git Sync {@code Pulled} port) to rerun the build whenever new source
 * lands, via {@link io.github.jaymcole.housegraph.plugins.web.SiteBuilder}. Neither ever restarts
 * the HTTP server itself — {@link LocalWebServer} already rereads the served directory from disk
 * on every request, so a fresh build is all it takes to serve the update. Clear
 * {@code buildCommand} to opt out for a project with no build step (nothing to build, same as
 * leaving the {@code Store} input unwired).
 * <p>
 * {@code /hooks/*} is always mounted, independent of anything wired into this node — it's where
 * {@code WebHookNode} and {@code WebHookRequestNode} answer requests for whatever routes they've
 * declared under this node's name (see
 * {@link io.github.jaymcole.housegraph.plugins.web.RouteRegistry}). A path nobody has declared a
 * route for answers {@code 404}. */
@Display.Name("Web Server")
@Node.Type("web.WebServerNode")
public class WebServerNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final Logger log = Log.get(WebServerNode.class);
    private static final String DEFAULT_NAME = "housegraph";
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_BUILD_COMMAND = "npm run build";
    private static final String DEFAULT_OUTPUT_FOLDER = "dist";
    /** Path prefix under which requests are reverse-proxied to {@link #proxyTarget}. */
    private static final String PROXY_PREFIX = "/bridge";

    private final LocalWebServer server = new LocalWebServer();

    private final NodeVariable<String> nameInput =
            withDefault(new NodeVariable<>("Name", String.class, true), DEFAULT_NAME);
    private final NodeVariable<String> directoryInput =
            new NodeVariable<>("Directory", String.class, true).required();
    private final NodeVariable<String> outputFolderInput =
            withDefault(new NodeVariable<>("Output Folder", String.class, true), DEFAULT_OUTPUT_FOLDER);
    private final NodeVariable<String> buildCommandInput =
            withDefault(new NodeVariable<>("Build Command", String.class, true), DEFAULT_BUILD_COMMAND);
    private final NodeVariable<Integer> portInput =
            withDefault(new NodeVariable<>("Port", Integer.class, true), DEFAULT_PORT);
    private final NodeVariable<String> proxyInput =
            new NodeVariable<>("Proxy Target", String.class, true);
    private final NodeVariable<JsonDocumentStore> storeInput =
            new NodeVariable<>("Store", JsonDocumentStore.class);

    private final FlowPort start = new FlowPort("Start", FlowPort.Direction.IN);
    private final FlowPort stop = new FlowPort("Stop", FlowPort.Direction.IN);
    private final FlowPort rebuild = new FlowPort("Rebuild", FlowPort.Direction.IN);

    /** The name currently registered in {@link ResourceRegistry}; kept in sync with {@link #nameInput} at every run. */
    private String resourceName = DEFAULT_NAME;
    private int port = DEFAULT_PORT;
    /** Optional backend to reverse-proxy at {@code /bridge/*} (e.g. {@code http://localhost:3000}); null = none. */
    private String proxyTarget;
    /** Shell command run in {@code Directory} at Start and on Rebuild; blank skips the build step. */
    private String buildCommand = DEFAULT_BUILD_COMMAND;
    /** Subfolder of {@code Directory} actually served, e.g. {@code dist}; blank serves {@code Directory} itself. */
    private String outputFolder = DEFAULT_OUTPUT_FOLDER;

    /** The store handle captured from the {@code Store} input at the last run; null when nothing is wired. */
    private volatile JsonDocumentStore resolvedStore;
    /** The directory captured from {@link #directoryInput} at the last run; null until one has happened. */
    private volatile String resolvedDirectory;
    /** True when the server was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    private Button startButton;
    private Button stopButton;
    private Button copyUrlButton;
    private Label statusLabel;

    /**
     * Resolves every input fresh, then does exactly one of three things depending on which flow-in
     * port brought control here (see {@link ProcessContext#wasTriggeredVia}):
     * <ul>
     *   <li>{@link #stop} — tears the server down and returns; nothing else runs.</li>
     *   <li>{@link #rebuild} only (not also {@link #start}) — runs the build step and returns
     *       without touching the server's bind state.</li>
     *   <li>Anything else — including {@link #start}, and an empty {@code triggeredVia()} (a plain
     *       {@link #beginProcessing()} pull, e.g. the Start button or {@link #autoStartIfWasRunning()})
     *       — runs the build step and (re)binds the server. This is the default because a pull
     *       carries no port identity to check, and defaulting to "try to run" is what a bare Start
     *       button click needs.</li>
     * </ul>
     * A misconfigured {@code Directory} throws when actually trying to bind (not on a rebuild-only
     * pass, which quietly no-ops with nothing configured yet — see {@link #runBuildIfConfigured()}),
     * which the engine surfaces as this node's error for {@link #onExecuted()}/the button handlers.
     */
    @Override
    public void process(ProcessContext ctx) {
        resolvedStore = storeInput.getValue();
        resolvedDirectory = directoryInput.getValue();
        syncResourceName();
        port = clampPort(portInput.getValue());
        proxyTarget = emptyToNull(proxyInput.getValue());
        buildCommand = buildCommandInput.getValue();
        outputFolder = outputFolderInput.getValue();

        if (ctx.wasTriggeredVia(stop)) {
            stopServer();
            return;
        }

        try {
            runBuildIfConfigured();
        } catch (IOException e) {
            throw new RuntimeException("Site build failed for " + resourceName, e);
        }

        boolean rebuildOnly = ctx.wasTriggeredVia(rebuild) && !ctx.wasTriggeredVia(start);
        if (rebuildOnly) {
            return;
        }

        if (resolvedDirectory == null || resolvedDirectory.isBlank()) {
            throw new IllegalStateException("Pick a website directory first");
        }
        bindServer();
    }

    @Override
    public void configureInputs() {
        addInput(nameInput);
        addInput(directoryInput);
        addInput(outputFolderInput);
        addInput(buildCommandInput);
        addInput(portInput);
        addInput(proxyInput);
        addInput(storeInput);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(start);
        addFlowInput(stop);
        addFlowInput(rebuild);
    }

    /** Re-registers under {@link #nameInput}'s current value if it changed since the last run. */
    private void syncResourceName() {
        String newName = valueOr(nameInput.getValue(), DEFAULT_NAME);
        if (!newName.equals(resourceName)) {
            ResourceRegistry.shared().unregister(resourceName);
            resourceName = newName;
            ResourceRegistry.shared().register(resourceName, server);
        }
    }

    /**
     * Runs {@link #buildCommand} in {@link #resolvedDirectory} — a no-op if the build step was
     * cleared (opted out) or {@code Directory} hasn't resolved to anything yet; the {@code Directory}
     * check in {@link #process} is what reports that case to the user for an actual Start.
     */
    private void runBuildIfConfigured() throws IOException {
        if (buildCommand == null || buildCommand.isBlank()) {
            return;
        }
        if (resolvedDirectory == null || resolvedDirectory.isBlank()) {
            return;
        }
        SiteBuilder.run(Path.of(resolvedDirectory), buildCommand);
    }

    /** Stops and (re)starts the HTTP server against the currently-resolved settings. */
    private void bindServer() {
        server.stop();
        try {
            server.start(servedRoot(), resourceName, port, documentApi(), proxyRoute());
        } catch (IOException e) {
            throw new RuntimeException("Web server '" + resourceName + "' failed to start: " + e.getMessage(), e);
        }
    }

    private void stopServer() {
        server.stop();
        resolvedStore = null;
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        if (server.isRunning()) {
            state.put("running", "true");
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        // Pre-input-port saves kept these fields in this custom state map; migrate each onto its
        // new port so an old graph doesn't lose its settings (applyValues() no-ops afterward for
        // such a save, since its "inputs" array has no entry for a port that didn't exist yet).
        String legacyDirectory = emptyToNull(state.get("directory"));
        if (legacyDirectory != null) {
            directoryInput.setValue(legacyDirectory);
        }
        String legacyName = emptyToNull(state.get("name"));
        if (legacyName != null) {
            nameInput.setValue(legacyName);
        }
        Integer legacyPort = parseLegacyPort(state.get("port"));
        if (legacyPort != null) {
            portInput.setValue(legacyPort);
        }
        String legacyProxyTarget = emptyToNull(state.get("proxyTarget"));
        if (legacyProxyTarget != null) {
            proxyInput.setValue(legacyProxyTarget);
        }
        // Distinct from a plain "fall back to the default if blank" read: an explicitly cleared
        // buildCommand/outputFolder ("skip the build step" / "serve Directory directly") must
        // migrate as blank, not snap back to the default — only an absent key (a save from before
        // the field existed at all) should leave the port's constructor default untouched.
        String legacyBuildCommand = state.get("buildCommand");
        if (legacyBuildCommand != null) {
            buildCommandInput.setValue(legacyBuildCommand);
        }
        String legacyOutputFolder = state.get("outputFolder");
        if (legacyOutputFolder != null) {
            outputFolderInput.setValue(legacyOutputFolder);
        }
        wasRunning = Boolean.parseBoolean(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (wasRunning) {
            start();
        }
    }

    @Override
    protected void onActivated() {
        resourceName = valueOr(nameInput.getValue(), DEFAULT_NAME);
        ResourceRegistry.shared().register(resourceName, server);
    }

    /** The fast half of teardown — see {@link #releaseResources()} for the rest. */
    @Override
    protected void onRemoved() {
        ResourceRegistry.shared().unregister(resourceName);
    }

    /**
     * The slow half: closing the HTTP server's executor and withdrawing the mDNS advertisement both
     * wait on the network, so they run here — on a worker under the engine's per-node limit — rather
     * than on the unbounded shutdown thread where they would delay every node behind them.
     */
    @Override
    protected void releaseResources() {
        server.stop();
    }

    /**
     * Reflects the outcome of any {@code process()} pass — a Start, Stop or Rebuild arriving along a
     * wired flow edge, or a plain pull like the button handlers' own {@link #beginProcessing()} call
     * racing to update the same label — in the status label/buttons. Idempotent: both paths compute
     * the same text from {@link LocalWebServer#isRunning()}/{@link #getLastError()}, so whichever
     * runs last leaves the correct state either way. Reached on the FX thread (the engine's callback
     * executor); a no-op if the node's UI was never built.
     */
    @Override
    protected void onExecuted() {
        if (statusLabel == null) {
            return;
        }
        Throwable error = getLastError();
        if (error != null) {
            statusLabel.setText("Failed — " + error.getMessage());
        } else if (server.isRunning()) {
            statusLabel.setText("Serving at " + server.url());
        } else {
            statusLabel.setText("Stopped");
        }
        startButton.setDisable(server.isRunning());
        stopButton.setDisable(!server.isRunning());
        copyUrlButton.setDisable(!server.isRunning());
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        startButton = new Button("Start");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> start());

        stopButton = new Button("Stop");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stop());

        copyUrlButton = new Button("Copy URL");
        copyUrlButton.setMaxWidth(Double.MAX_VALUE);
        copyUrlButton.setDisable(true);
        copyUrlButton.setOnAction(e -> copyUrl());

        statusLabel = new Label("Stopped");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        HBox buttons = new HBox(6, startButton, stopButton);
        return new VBox(4, buttons, copyUrlButton, statusLabel);
    }

    /**
     * Runs {@link #process} via {@link #beginProcessing()} on a background thread — a plain pull, so
     * {@code ctx.triggeredVia()} reads empty and {@link #process} defaults to its Start behaviour,
     * exactly like a wired Start arrival. UI feedback is handled here rather than left to
     * {@link #onExecuted()} so a failure can report the specific exception message immediately.
     */
    private void start() {
        startButton.setDisable(true);
        statusLabel.setText("Starting…");

        Thread thread = new Thread(() -> {
            try {
                beginProcessing();
                Throwable error = getLastError();
                if (error != null) {
                    throw new RuntimeException(error.getMessage(), error);
                }
                Platform.runLater(() -> {
                    statusLabel.setText("Serving at " + server.url());
                    stopButton.setDisable(false);
                    copyUrlButton.setDisable(false);
                });
            } catch (Exception ex) {
                log.error("Web server start failed: {}", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Start failed — " + ex.getMessage());
                    startButton.setDisable(false);
                });
            }
        }, "web-server-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    private void stop() {
        stopServer();
        statusLabel.setText("Stopped");
        startButton.setDisable(false);
        stopButton.setDisable(true);
        copyUrlButton.setDisable(true);
    }

    /**
     * The directory actually served: {@link #resolvedDirectory} with {@link #outputFolder}
     * resolved onto it, or {@code resolvedDirectory} itself when {@code outputFolder} is blank.
     * Package-private: also a test seam.
     */
    Path servedRoot() {
        Path root = Path.of(resolvedDirectory);
        return (outputFolder == null || outputFolder.isBlank()) ? root : root.resolve(outputFolder);
    }

    /**
     * The {@code /api/data} bridge to the store captured from the {@code Store} input. Reads the
     * captured handle live per request; if nothing was wired ({@code resolvedStore == null}) the
     * endpoint answers 503 (via {@link IllegalStateException}) while static files keep serving.
     */
    private DocumentApi documentApi() {
        return new DocumentApi() {
            @Override
            public String read() {
                return requireStore().get();
            }

            @Override
            public void write(String json) {
                requireStore().set(json);
            }
        };
    }

    /**
     * Builds the reverse-proxy route from {@link #proxyTarget}, mounting it at {@code /bridge/*}.
     * Returns {@code null} (no proxy) when unset or blank; logs and skips an unparseable target
     * rather than failing the whole server start.
     */
    private ProxyRoute proxyRoute() {
        if (proxyTarget == null || proxyTarget.isBlank()) {
            log.info("Web server '{}' starting with NO API proxy (proxy target field is empty)", resourceName);
            return null;
        }
        // Be lenient: a bare "host:port" parses as scheme=host with a null URI host, which we'd
        // reject. Default to http:// when no scheme is present so "127.0.0.1:3000" just works.
        String raw = proxyTarget.trim();
        if (!raw.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            raw = "http://" + raw;
        }
        try {
            ProxyRoute route = new ProxyRoute(PROXY_PREFIX, URI.create(raw));
            log.info("Web server '{}' reverse-proxying {}/* -> {}", resourceName, PROXY_PREFIX, route.target());
            return route;
        } catch (IllegalArgumentException e) {
            log.warn("Web server '{}' ignoring invalid proxy target '{}': {}", resourceName, proxyTarget, e.getMessage());
            return null;
        }
    }

    private JsonDocumentStore requireStore() {
        JsonDocumentStore store = resolvedStore;
        if (store == null) {
            throw new IllegalStateException("No data store wired into this web server");
        }
        return store;
    }

    /** Test seam: the store handle captured from the {@code Store} input at the last run. */
    JsonDocumentStore resolvedStore() {
        return resolvedStore;
    }

    /** Test seam: the directory captured from the {@code Directory} input at the last run. */
    String resolvedDirectory() {
        return resolvedDirectory;
    }

    /** Test seam: whether the loaded graph had this server running, i.e. auto-start is pending. */
    boolean wasRunning() {
        return wasRunning;
    }

    private void copyUrl() {
        String url = server.url();
        if (url == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied " + url);
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }

    private static String valueOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static int clampPort(Integer value) {
        if (value == null) {
            return DEFAULT_PORT;
        }
        return (value >= 1 && value <= 65535) ? value : DEFAULT_PORT;
    }

    private static Integer parseLegacyPort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return (parsed >= 1 && parsed <= 65535) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
