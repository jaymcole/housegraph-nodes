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
import javafx.scene.control.TextField;
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
 * to graph flow.
 * <p>
 * Configuration — the website name and the port — is authored in the node's inline UI and
 * persisted via {@link #saveState()}; the directory to serve is a data input ({@code Directory}),
 * typed in directly or wired from an upstream node (e.g. a Create Folder node's output), and
 * persisted as an ordinary port value rather than through {@link #saveState()}. The actual
 * bind + mDNS advertisement runs off the UI thread so the app stays responsive, and is
 * torn down when the node is deleted or the app shuts down — the registry entry in
 * {@link #onRemoved()}, the socket and mDNS advertisement in {@link #releaseResources()}.
 * <p>
 * To give the hosted site shared, persisted storage, wire a {@code DataStoreNode}'s output
 * into this node's <b>Store</b> data input; the server then exposes it at {@code /api/data}.
 * The handle is pulled from that edge once, at Start ({@link #beginProcessing()} resolves the
 * input and {@link #process(io.github.jaymcole.housegraph.graph.ProcessContext)} captures it)
 * — so changing the wiring takes effect on the next Start, like the other settings. With
 * nothing wired, the site is served static-only and {@code /api/data} returns 503.
 * <p>
 * If it was serving when the graph was saved, it resumes automatically on load: the running flag
 * rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses Start for the
 * user once the graph (including the {@code Store} edge) is fully loaded (see {@link AutoStartable}).
 * <p>
 * The files actually served live under {@code Directory}'s {@link #outputFolder}, not
 * {@code Directory} itself — {@code Directory} is meant to be wireable to a project's root (e.g.
 * a Create Folder or Git Sync node), while a bundler like Vite writes its build to a subfolder of
 * that root ({@code dist} by default) rather than the root itself. Serving {@code Directory}
 * unmodified would hand the browser raw, untranspiled source (e.g. a Vite {@code index.html}
 * referencing {@code /src/main.tsx}), which fails to load with an unsupported-MIME-type error since
 * static file serving can't transpile JSX/TypeScript. Leave {@code outputFolder} blank to serve
 * {@code Directory} directly, for a project with no build step.
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
 * route for answers {@code 404}.
 */
@Display.Name("Web Server")
@Node.Type("web.WebServerNode")
public class WebServerNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final Logger log = Log.get(WebServerNode.class);
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_BUILD_COMMAND = "npm run build";
    private static final String DEFAULT_OUTPUT_FOLDER = "dist";
    /** Path prefix under which requests are reverse-proxied to {@link #proxyTarget}. */
    private static final String PROXY_PREFIX = "/bridge";

    private final LocalWebServer server = new LocalWebServer();
    private final NodeVariable<JsonDocumentStore> storeInput =
            new NodeVariable<>("Store", JsonDocumentStore.class);
    private final FlowPort rebuild = new FlowPort("Rebuild", FlowPort.Direction.IN);
    private final NodeVariable<String> directoryInput =
            new NodeVariable<>("Directory", String.class, true).required();
    private String resourceName = "housegraph";
    private int port = DEFAULT_PORT;
    /** Optional backend to reverse-proxy at {@code /bridge/*} (e.g. {@code http://localhost:3000}); null = none. */
    private String proxyTarget;
    /** Shell command run in {@code Directory} at Start and on Rebuild; blank skips the build step. */
    private String buildCommand = DEFAULT_BUILD_COMMAND;
    /** Subfolder of {@code Directory} actually served, e.g. {@code dist}; blank serves {@code Directory} itself. */
    private String outputFolder = DEFAULT_OUTPUT_FOLDER;

    /** The store handle captured from the {@code Store} input at Start; null when nothing is wired. */
    private volatile JsonDocumentStore resolvedStore;
    /** The directory resolved from {@link #directoryInput} at the last {@link #beginProcessing()}; null until Start has run once. */
    private volatile String resolvedDirectory;
    /** True when the server was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    private TextField nameField;
    private TextField buildCommandField;
    private TextField outputFolderField;
    private TextField portField;
    private TextField proxyField;
    private Button startButton;
    private Button stopButton;
    private Button copyUrlButton;
    private Label statusLabel;

    @Override
    public void process(ProcessContext ctx) {
        // Runs during beginProcessing() at Start, with the run's value overlay bound, so this
        // is the one place the edge-resolved store handle and directory are readable. Capture
        // them for the server.
        resolvedStore = storeInput.getValue();
        resolvedDirectory = directoryInput.getValue();

        // Also runs the build step (if configured) in the just-resolved directory — both when
        // beginProcessing() is invoked manually at Start and when the Rebuild flow-in fires, since
        // both paths go through this one process() method. A cleared buildCommand (opting out of a
        // build step) or no directory yet resolved is a no-op either way; a build failure throws,
        // which the engine surfaces as this node's error for onExecuted()/start() to show.
        try {
            runBuildIfConfigured();
        } catch (IOException e) {
            throw new RuntimeException("Site build failed for " + resourceName, e);
        }
    }

    @Override
    public void configureInputs() {
        addInput(storeInput);
        addInput(directoryInput);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(rebuild);
    }

    /**
     * Runs {@link #buildCommand} in {@link #resolvedDirectory} — a no-op if the build step was
     * cleared (opted out) or {@code Directory} hasn't resolved to anything yet; {@code start()}'s
     * own directory check reports that case, so this one just quietly skips the build.
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

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("name", resourceName);
        state.put("port", Integer.toString(port));
        if (proxyTarget != null) {
            state.put("proxyTarget", proxyTarget);
        }
        state.put("buildCommand", buildCommand);
        state.put("outputFolder", outputFolder);
        if (server.isRunning()) {
            state.put("running", "true");
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String name = state.get("name");
        if (name != null && !name.isBlank()) {
            resourceName = name;
        }
        // Pre-input-port saves kept the directory in this custom state map; migrate it onto the
        // new "Directory" input so an old graph doesn't lose it (applyValues() no-ops afterward
        // for such a save, since its "inputs" array has no entry for a port that didn't exist yet).
        String legacyDirectory = emptyToNull(state.get("directory"));
        if (legacyDirectory != null) {
            directoryInput.setValue(legacyDirectory);
        }
        port = parsePort(state.get("port"));
        proxyTarget = emptyToNull(state.get("proxyTarget"));
        // Distinct from a plain "fall back to the default if blank" read: an explicitly cleared
        // buildCommand ("skip the build step") must round-trip as blank, not snap back to "npm run
        // build" — only an absent key (a save from before buildCommand existed) defaults to it.
        String savedBuildCommand = state.get("buildCommand");
        if (savedBuildCommand != null) {
            buildCommand = savedBuildCommand;
        }
        // Distinct from the blank checks above: an explicitly emptied outputFolder ("serve
        // Directory directly") must round-trip as blank, not fall back to the "dist" default —
        // only an absent key (a save from before this field existed) should default to "dist".
        String savedOutputFolder = state.get("outputFolder");
        if (savedOutputFolder != null) {
            outputFolder = savedOutputFolder;
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
     * Reflects a Rebuild flow-in firing in the same status label the Start/Stop path drives. Only
     * touches the label when there's something worth reporting — a build failure, or a successful
     * build reflected as the same "Serving at …" text Start itself sets — so it can't stomp on
     * "Starting…"/"Start failed…" text a concurrent Start is in the middle of writing. Reached on
     * the FX thread (the engine's callback executor); a no-op if the node's UI was never built.
     */
    @Override
    protected void onExecuted() {
        if (statusLabel == null) {
            return;
        }
        Throwable error = getLastError();
        if (error != null) {
            statusLabel.setText("Rebuild failed — " + error.getMessage());
            return;
        }
        if (server.isRunning()) {
            statusLabel.setText("Serving at " + server.url());
        }
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        nameField = new TextField(resourceName);
        nameField.setPromptText("Website name (→ name.local)");
        nameField.textProperty().addListener((obs, old, value) -> rename(value));

        portField = new TextField(Integer.toString(port));
        portField.setPromptText("Port");
        portField.setPrefColumnCount(5);
        portField.textProperty().addListener((obs, old, value) -> port = parsePort(value));

        proxyField = new TextField(proxyTarget == null ? "" : proxyTarget);
        proxyField.setPromptText("API proxy target → /bridge (e.g. http://localhost:3000)");
        proxyField.textProperty().addListener((obs, old, value) -> proxyTarget = emptyToNull(value));

        buildCommandField = new TextField(buildCommand);
        buildCommandField.setPromptText("Build command, run in Directory (blank to skip, e.g. npm run build)");
        buildCommandField.textProperty().addListener((obs, old, value) -> buildCommand = value);

        outputFolderField = new TextField(outputFolder);
        outputFolderField.setPromptText("Static files subfolder of Directory (e.g. dist)…");
        outputFolderField.textProperty().addListener((obs, old, value) -> outputFolder = value);

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
        return new VBox(4, nameField, outputFolderField, buildCommandField, portField, proxyField,
                buttons, copyUrlButton, statusLabel);
    }

    private void rename(String newName) {
        ResourceRegistry.shared().unregister(resourceName);
        resourceName = newName;
        ResourceRegistry.shared().register(resourceName, server);
    }

    private void start() {
        setEditingLocked(true);
        startButton.setDisable(true);
        statusLabel.setText("Starting…");

        Thread thread = new Thread(() -> {
            try {
                // Pull the Store and Directory inputs (if wired), capture their resolved values,
                // and run the build step (if configured) — all via process(), before serving. This
                // is also what picks up a freshly-wired Create Folder node even on the very first
                // Start since it was wired.
                beginProcessing();
                Throwable buildError = getLastError();
                if (buildError != null) {
                    throw new RuntimeException(buildError.getMessage(), buildError);
                }
                if (resolvedDirectory == null || resolvedDirectory.isBlank()) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Pick a website directory first");
                        setEditingLocked(false);
                        startButton.setDisable(false);
                    });
                    return;
                }
                server.start(servedRoot(), resourceName, port, documentApi(), proxyRoute());
                Platform.runLater(() -> {
                    statusLabel.setText("Serving at " + server.url());
                    stopButton.setDisable(false);
                    copyUrlButton.setDisable(false);
                });
            } catch (Exception ex) {
                log.error("Web server start failed: {}", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Start failed — " + ex.getMessage());
                    setEditingLocked(false);
                    startButton.setDisable(false);
                });
            }
        }, "web-server-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    private void stop() {
        server.stop();
        resolvedStore = null;
        statusLabel.setText("Stopped");
        setEditingLocked(false);
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

    /** Test seam: the store handle captured from the {@code Store} input at the last {@link #beginProcessing()}. */
    JsonDocumentStore resolvedStore() {
        return resolvedStore;
    }

    /** Test seam: the directory captured from the {@code Directory} input at the last {@link #beginProcessing()}. */
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

    private void setEditingLocked(boolean locked) {
        nameField.setDisable(locked);
        buildCommandField.setDisable(locked);
        outputFolderField.setDisable(locked);
        portField.setDisable(locked);
        proxyField.setDisable(locked);
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return (parsed >= 1 && parsed <= 65535) ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
