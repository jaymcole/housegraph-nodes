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
import javafx.scene.control.TextField;
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
 * long-lived-resource lifecycle (register under a name, user-driven Start/Stop off the UI thread,
 * torn down in {@link #onRemoved()}), but hosting is delegated to a child process instead of the
 * JVM's built-in HTTP server. It backs a {@link NodeProcessServer} rather than a
 * {@code LocalWebServer}.
 * <p>
 * Configuration — the server name, the start command, and the port — is authored inline and
 * persisted via {@link #saveState()} (never the project files themselves; the app is run live
 * from wherever it lives on disk). The project directory is a data input ({@code Directory}),
 * typed in directly or wired from an upstream node (e.g. a Create Folder node's output), and
 * persisted as an ordinary port value rather than through {@link #saveState()}. The process
 * spawn and mDNS advertisement run off the UI thread so the app stays responsive.
 * <p>
 * Unlike {@code WebServerNode} there is no {@code Store} data input: a Node app manages its own
 * routes and persistence. The declared port is exported to the child as {@code PORT} and
 * advertised over mDNS, but the Node program is responsible for actually listening on it.
 * <p>
 * Unlike {@code WebServerNode} — which serves files straight off disk, so a {@code git pull}
 * shows up on the very next request — a Node process only sees new code by being relaunched.
 * That's what the <b>Restart</b> flow-in is for: wire something's flow-out into it (e.g.
 * {@code housegraph-github}'s Git Sync {@code Pulled} port) to relaunch the process with its
 * current config whenever new commits land. It's additive, not a replacement for the Start/Stop
 * buttons: ensures the process is running with fresh code either way, restarting it if already
 * running or starting it fresh if not, and reflects the result in the same status label the
 * buttons drive.
 * <p>
 * If it was running when the graph was saved, it resumes automatically on load: the running flag
 * rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses Start for the
 * user once the graph (including the {@code Directory} edge) is fully loaded (see
 * {@link AutoStartable}).
 */
@Display.Name("Node Server")
@Node.Type("web.NodeServerNode")
public class NodeServerNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final int DEFAULT_PORT = 3000;
    private static final String DEFAULT_COMMAND = "npm start";

    private final NodeProcessServer server = new NodeProcessServer();
    private final FlowPort restart = new FlowPort("Restart", FlowPort.Direction.IN);
    private final NodeVariable<String> directoryInput =
            new NodeVariable<>("Directory", String.class, true).required();
    private String resourceName = "node-app";
    private String command = DEFAULT_COMMAND;
    private int port = DEFAULT_PORT;
    /** True when the process was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    private TextField nameField;
    private TextField commandField;
    private TextField portField;
    private Button startButton;
    private Button stopButton;
    private Button copyUrlButton;
    private Label statusLabel;

    /**
     * (Re)launches the process with the current config, run on the engine's own execution
     * thread — reached both from a Restart flow-in arrival and, via {@link #beginProcessing()},
     * from the Start button (see {@link #start()}). Either way this is what resolves
     * {@link #directoryInput} from a wired edge before reading it, so a freshly-wired Create
     * Folder node is picked up correctly. A misconfigured node (no directory/command yet) or a
     * spawn failure throws, which the engine surfaces as this node's error for
     * {@link #onExecuted()} to show.
     */
    @Override
    public void process(ProcessContext ctx) {
        String directory = directoryInput.getValue();
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("No Node project directory configured");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("No start command configured");
        }
        if (server.isRunning()) {
            server.stop();
        }
        try {
            server.start(Path.of(directory), command, resourceName, port);
        } catch (IOException e) {
            throw new RuntimeException("Node server start failed for " + resourceName, e);
        }
    }

    @Override
    public void configureInputs() {
        addInput(directoryInput);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(restart);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("name", resourceName);
        state.put("command", command);
        state.put("port", Integer.toString(port));
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
        String savedCommand = state.get("command");
        if (savedCommand != null && !savedCommand.isBlank()) {
            command = savedCommand;
        }
        port = parsePort(state.get("port"));
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

    @Override
    protected void onRemoved() {
        ResourceRegistry.shared().unregister(resourceName);
        server.stop();
    }

    /**
     * Reflects a {@code process()} pass finishing in the status label/buttons — reached both from
     * a Restart flow-in arrival and from the Start button, which now runs through
     * {@link #beginProcessing()} too (see {@link #start()}), so both share this one feedback path.
     * Reached on the FX thread (the engine's callback executor); a no-op if the node's UI was
     * never built.
     */
    @Override
    protected void onExecuted() {
        if (statusLabel == null) {
            return;
        }
        Throwable error = getLastError();
        if (error != null) {
            statusLabel.setText("Failed — " + error.getMessage());
            setEditingLocked(false);
            startButton.setDisable(false);
            stopButton.setDisable(true);
            copyUrlButton.setDisable(true);
            return;
        }
        statusLabel.setText("Running at " + server.url());
        setEditingLocked(true);
        startButton.setDisable(true);
        stopButton.setDisable(false);
        copyUrlButton.setDisable(false);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        nameField = new TextField(resourceName);
        nameField.setPromptText("Server name (→ name.local)");
        nameField.textProperty().addListener((obs, old, value) -> rename(value));

        commandField = new TextField(command);
        commandField.setPromptText("Start command (e.g. npm start)");
        commandField.textProperty().addListener((obs, old, value) -> command = value);

        portField = new TextField(Integer.toString(port));
        portField.setPromptText("Port");
        portField.setPrefColumnCount(5);
        portField.textProperty().addListener((obs, old, value) -> port = parsePort(value));

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
        return new VBox(4, nameField, commandField, portField, buttons, copyUrlButton, statusLabel);
    }

    private void rename(String newName) {
        ResourceRegistry.shared().unregister(resourceName);
        resourceName = newName;
        ResourceRegistry.shared().register(resourceName, server);
    }

    /**
     * Runs {@link #process()} via {@link #beginProcessing()} on a background thread, which
     * resolves {@link #directoryInput} from any wired edge before (re)launching the process —
     * the same path {@code Restart} uses. UI feedback is left to {@link #onExecuted()}, which the
     * engine calls once the pass finishes either way.
     */
    private void start() {
        setEditingLocked(true);
        startButton.setDisable(true);
        statusLabel.setText("Starting…");

        Thread thread = new Thread(this::beginProcessing, "node-server-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    private void stop() {
        server.stop();
        statusLabel.setText("Stopped");
        setEditingLocked(false);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        copyUrlButton.setDisable(true);
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

    private void setEditingLocked(boolean locked) {
        nameField.setDisable(locked);
        commandField.setDisable(locked);
        portField.setDisable(locked);
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
