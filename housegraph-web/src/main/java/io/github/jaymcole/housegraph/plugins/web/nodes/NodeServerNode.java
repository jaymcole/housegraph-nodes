package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import io.github.jaymcole.housegraph.plugins.web.NodeProcessServer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Launches a <b>Node.js</b> server (an Express app, a Vite dev server — anything a shell command
 * like {@code npm start} runs) from a chosen project directory and advertises it on the LAN at
 * {@code http://<name>.local:<port>/}. The Node-flavoured sibling of {@link WebServerNode}: same
 * long-lived-resource lifecycle (register under a name, user-driven Start/Stop, torn down across
 * {@link #onRemoved()} and {@link #releaseResources()}), but hosting is delegated to a child
 * process instead of the JVM's built-in HTTP server. It backs a {@link NodeProcessServer} rather
 * than a {@code LocalWebServer}.
 * <p>
 * Every setting — name, project directory, start command, port — is an ordinary data input:
 * typed in directly on the node, or wired from an upstream node (e.g. a Create Folder node's
 * output). Only {@code Directory} is required; the rest default sensibly and are resolved fresh
 * on every run.
 * <p>
 * Unlike {@code WebServerNode} there is no {@code Store} data input: a Node app manages its own
 * routes and persistence. The declared port is exported to the child as {@code PORT} and
 * advertised over mDNS, but the Node program is responsible for actually listening on it.
 * <p>
 * Three flow-in ports drive the process's lifecycle, told apart in {@link #process} with
 * {@link ProcessContext#wasTriggeredVia(FlowPort)}:
 * <ul>
 *   <li><b>Start</b> and <b>Restart</b> — both (re)launch the process with the current config,
 *       identically: stop first (idempotent — a no-op if nothing is running), then start. They're
 *       operationally the same action under two names for two different intents — Start for "get
 *       this running", Restart for "pick up new code" (e.g. wired from {@code housegraph-github}'s
 *       Git Sync {@code Pulled} port) — since unlike {@code WebServerNode}'s file server, a Node
 *       process only sees new code by being relaunched.</li>
 *   <li><b>Stop</b> — tears the process down; nothing else runs.</li>
 * </ul>
 * The inline Start/Stop/Copy URL buttons are a convenience, not a separate code path: Start calls
 * {@link #beginProcessing()} (a plain pull, so {@code ctx.triggeredVia()} reads empty), which
 * {@link #process} treats the same as an explicit Start arrival; Stop calls the same teardown
 * {@link #process} uses for a Stop arrival.
 * <p>
 * {@link NodeProcessServer#stop()}/{@code start()} both block: stopping waits for the old process
 * tree to actually release the port, and starting waits for the new one to accept a connection.
 * That is what makes a relaunch survivable — the previous process drains its connections for a few
 * seconds after being signalled, and spawning into that window used to produce a silent
 * {@code EADDRINUSE} death that still reported as a successful start.
 * <p>
 * If it was running when the graph was saved, it resumes automatically on load: the running flag
 * rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses Start for the
 * user once the graph (including every wired input) is fully loaded (see {@link AutoStartable}).
 */
@Display.Name("Node Server")
@Node.Type("web.NodeServerNode")
public class NodeServerNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final String DEFAULT_NAME = "node-app";
    private static final int DEFAULT_PORT = 3000;
    private static final String DEFAULT_COMMAND = "npm start";

    private final NodeProcessServer server = new NodeProcessServer();

    private final NodeVariable<String> nameInput =
            withDefault(new NodeVariable<>("Name", String.class, true), DEFAULT_NAME);
    private final NodeVariable<String> directoryInput =
            new NodeVariable<>("Directory", String.class, true).required();
    private final NodeVariable<String> commandInput =
            withDefault(new NodeVariable<>("Command", String.class, true), DEFAULT_COMMAND);
    private final NodeVariable<Integer> portInput =
            withDefault(new NodeVariable<>("Port", Integer.class, true), DEFAULT_PORT);

    private final FlowPort start = new FlowPort("Start", FlowPort.Direction.IN);
    private final FlowPort stop = new FlowPort("Stop", FlowPort.Direction.IN);
    private final FlowPort restart = new FlowPort("Restart", FlowPort.Direction.IN);

    /** The name currently registered in {@link ResourceRegistry}; kept in sync with {@link #nameInput} at every run. */
    private String resourceName = DEFAULT_NAME;
    private String command = DEFAULT_COMMAND;
    private int port = DEFAULT_PORT;
    /** True when the process was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    private Button startButton;
    private Button stopButton;
    private Button copyUrlButton;
    private Label statusLabel;

    /**
     * Resolves every input fresh, then does exactly one of two things depending on which flow-in
     * port brought control here (see {@link ProcessContext#wasTriggeredVia}):
     * <ul>
     *   <li>{@link #stop} — tears the process down and returns; nothing else runs.</li>
     *   <li>Anything else — {@link #start}, {@link #restart}, and an empty {@code triggeredVia()}
     *       (a plain {@link #beginProcessing()} pull, e.g. the Start button or
     *       {@link #autoStartIfWasRunning()}) — (re)launches the process. Start and Restart are the
     *       same underlying action; see the class Javadoc for why.</li>
     * </ul>
     * A misconfigured node (no directory/command) throws, which the engine surfaces as this node's
     * error for {@link #onExecuted()}/the button handlers to show.
     */
    @Override
    public void process(ProcessContext ctx) {
        String directory = directoryInput.getValue();
        syncResourceName();
        command = valueOr(commandInput.getValue(), DEFAULT_COMMAND);
        port = clampPort(portInput.getValue());

        if (ctx.wasTriggeredVia(stop)) {
            server.stop();
            return;
        }

        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("No Node project directory configured");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("No start command configured");
        }
        // Unconditional, not guarded by isRunning(): that only tracks the launcher shell, so a run
        // whose server died underneath it would skip teardown and leak its mDNS registration. stop()
        // is idempotent, and it waits for the port to be released before start() tries to bind it.
        server.stop();
        try {
            server.start(Path.of(directory), command, resourceName, port);
        } catch (IOException e) {
            // Carry the cause's message in the text, not just the cause: this is what reaches the
            // node's status label via getLastError().getMessage(), and what the engine formats into
            // the log. "start failed for bridge" on its own names the node and says nothing at all
            // about why — and why is the whole point of failing loudly.
            throw new RuntimeException("Node server '" + resourceName + "' failed to start: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public void configureInputs() {
        addInput(nameInput);
        addInput(directoryInput);
        addInput(commandInput);
        addInput(portInput);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(start);
        addFlowInput(stop);
        addFlowInput(restart);
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
        String legacyCommand = emptyToNull(state.get("command"));
        if (legacyCommand != null) {
            commandInput.setValue(legacyCommand);
        }
        Integer legacyPort = parseLegacyPort(state.get("port"));
        if (legacyPort != null) {
            portInput.setValue(legacyPort);
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

    /**
     * The fast half of teardown. Deregistering is a map write; it belongs here precisely because it
     * costs nothing, and doing it first means nothing can look the server up while it is going down.
     */
    @Override
    protected void onRemoved() {
        ResourceRegistry.shared().unregister(resourceName);
    }

    /**
     * The slow half: signal the process tree, wait for it to go, wait for the port. Ten seconds in
     * the worst case, which is exactly why it cannot stay in {@link #onRemoved()} — that runs on the
     * shutdown thread with no limit on it, so this used to be able to outlast the app's whole budget
     * and get the JVM killed mid-teardown, orphaning the very process it was reaping. Here it runs
     * on a worker under the engine's per-node limit, alongside every other node's.
     */
    @Override
    protected void releaseResources() {
        server.stop();
    }

    /**
     * Reflects the outcome of any {@code process()} pass — a Start, Restart or Stop arriving along a
     * wired flow edge, or a plain pull like the button handlers' own {@link #beginProcessing()} call
     * racing to update the same label — in the status label/buttons. Idempotent: both paths compute
     * the same text from {@link NodeProcessServer#isRunning()}/{@link #getLastError()}, so whichever
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
            statusLabel.setText("Running at " + server.url());
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
     * {@code ctx.triggeredVia()} reads empty and {@link #process} defaults to its (re)launch
     * behaviour, exactly like a wired Start/Restart arrival. UI feedback is left to
     * {@link #onExecuted()}, which the engine calls once the pass finishes either way.
     */
    private void start() {
        startButton.setDisable(true);
        statusLabel.setText("Starting…");

        Thread thread = new Thread(this::beginProcessing, "node-server-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Tears the process down on a background thread. {@link NodeProcessServer#stop()} waits for the
     * child tree to actually exit — several seconds for a server that drains its connections first —
     * so running it inline would freeze the canvas for the duration. The controls are settled up
     * front and the status label corrected once teardown returns.
     */
    private void stop() {
        stopButton.setDisable(true);
        copyUrlButton.setDisable(true);
        statusLabel.setText("Stopping…");

        Thread thread = new Thread(() -> {
            server.stop();
            javafx.application.Platform.runLater(() -> {
                statusLabel.setText("Stopped");
                startButton.setDisable(false);
            });
        }, "node-server-stop-" + resourceName);
        thread.setDaemon(true);
        thread.start();
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

    /** Test seam: whether the loaded graph had this server running, i.e. auto-start is pending. */
    boolean wasRunning() {
        return wasRunning;
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
