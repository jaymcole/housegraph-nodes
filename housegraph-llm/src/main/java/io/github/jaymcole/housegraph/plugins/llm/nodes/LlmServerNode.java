package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmApi;
import io.github.jaymcole.housegraph.plugins.llm.LlmException;
import io.github.jaymcole.housegraph.plugins.llm.LlmServerProcess;
import io.github.jaymcole.housegraph.plugins.llm.LlmServerSpec;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts the model server the <b>Local LLM</b> node talks to, and keeps it running for as long as
 * the graph wants it. Without this, a graph that prompts a local model depends on somebody having
 * typed {@code ollama serve} into a terminal first; with it, an unattended machine can bring its
 * own LLM up and take it down again.
 * <p>
 * <b>Out of the box it runs {@code ollama serve}</b> and waits for {@code http://localhost:11434}
 * to answer — the address and model the Local LLM node also ships pre-filled with, so the pair
 * works wired together with nothing typed. <b>Command</b> is an ordinary shell command, so any
 * server that has one works as typed: {@code llama-server -m models/llama.gguf --port 8080},
 * {@code lms server start}, {@code vllm serve mistralai/Mistral-7B-v0.1}. Set <b>API</b> to
 * {@code openai} for those three, since that is what the readiness check has to speak to know the
 * server is up.
 * <p>
 * <b>Wire the Server output into the Local LLM node's Server input.</b> It carries the address this
 * node brought up, so which prompt node uses which server is visible on the canvas instead of being
 * two fields that have to be kept saying the same thing. <b>Models</b> is what the server reported
 * having when it came up, for a Pull Model node or a List node downstream to work from.
 *
 * <h2>A server that is already running is adopted, not fought with</h2>
 * Ollama is normally installed as a background service — the macOS and Windows apps start one at
 * login, most Linux packages install a systemd unit — so on many machines something is already
 * serving {@code localhost:11434}. Starting a second one there just collides with it, so this node
 * looks first: if the address already answers, that server is <b>adopted</b>. The node reports
 * running, <b>Ready</b> fires, and everything downstream works exactly as it would have.
 * <p>
 * <b>What is adopted is not owned.</b> Stop leaves an adopted server running and says so, because a
 * node has no business killing a system service it did not start — and a Restart that took the
 * machine's Ollama down with the graph would be worse than one that does nothing. The consequence
 * to know is that <b>Restart on an adopted server does nothing</b>; the status line says which of
 * the two this node has.
 *
 * <h2>The ports</h2>
 * Three flow-ins drive the lifecycle, told apart with {@link ProcessContext#wasTriggeredVia}:
 * <ul>
 *   <li><b>Start</b> — brings the server up if it is not already. Doing nothing when it is up is
 *       the point rather than an optimisation: relaunching a model server means reading gigabytes
 *       back off disk, so a Start wired to a repeating trigger as a keep-alive costs nothing on the
 *       runs where everything is fine.</li>
 *   <li><b>Restart</b> — stops and starts, for a changed command or a wedged server. The one that
 *       does pay that cost.</li>
 *   <li><b>Stop</b> — takes down the server this node started. Nothing else runs.</li>
 * </ul>
 * <b>Ready</b> fires when the server is up and answering — wire it into a Pull Model node, or
 * straight into whatever prompts it. <b>Stopped</b> fires after a Stop, and after a start that
 * failed, so exactly one of the two always reports what happened.
 * <p>
 * <b>Starting blocks until the server answers its own API</b>, not merely until its port is open: a
 * model server binds the port and then reads its model index, and a graph that prompted in between
 * would get a connection refused from a server that is technically running. A command that loads
 * weights before it listens can take minutes, which is what <b>Startup Timeout (s)</b> is for; it
 * defaults to {@value io.github.jaymcole.housegraph.plugins.llm.LlmServerSpec#DEFAULT_STARTUP_TIMEOUT_SECONDS}
 * seconds.
 * <p>
 * <b>It resumes on load.</b> If the server was running when the graph was saved, the node presses
 * Start for itself once the graph and its wired inputs are fully loaded (see {@link AutoStartable}),
 * and it is torn down when the node is removed or HouseGraph shuts down. A HouseGraph that is
 * killed outright cannot run that teardown, so the spawned process is also recorded on disk and
 * reaped by the next run before it starts a second one.
 * <p>
 * <b>This is the control-versus-action rule's named exception.</b> Start/Stop and state live on one
 * node here because the process lifecycle <em>is</em> what is being managed — the same reason the
 * Web Server and Database nodes own theirs. Nothing here schedules anything: when to prompt the
 * model is a trigger's business, wired into the Local LLM node.
 */
@Display.Name("Local LLM Server")
@Display.Description("Starts a language-model server on this machine and waits until it answers.")
@Node.Kind(NodeKind.RESOURCE)
@Node.Keywords({"llm", "ai", "ollama", "serve", "server", "start", "launch", "run", "local", "model",
        "llamacpp", "lmstudio", "vllm", "openai", "process", "daemon"})
@Node.Type("llm.LlmServerNode")
public class LlmServerNode extends BaseNode implements NodeContentProvider, AutoStartable {

    /**
     * The type the Models port declares. A data port's type is a bare {@link Class}, so a list port
     * is {@code List.class} with its element type erased; laundering it through {@code Class<?>}
     * once here is the same move {@code housegraph-collections} makes.
     */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    private final LlmServerProcess server = new LlmServerProcess();

    private final NodeVariable<String> nameInput =
            withDefault(new NodeVariable<>("Name", String.class, true), LlmServerSpec.DEFAULT_NAME);
    private final NodeVariable<String> commandInput =
            withDefault(new NodeVariable<>("Command", String.class, true), LlmServerSpec.DEFAULT_COMMAND);
    private final NodeVariable<String> directoryInput = new NodeVariable<>("Directory", String.class, true);
    private final NodeVariable<String> serverInput =
            withDefault(new NodeVariable<>("Server", String.class, true), LocalLlmClient.DEFAULT_SERVER);
    private final NodeVariable<String> apiInput = new NodeVariable<>("API", String.class, true);
    private final NodeVariable<String> apiKeyInput =
            new NodeVariable<>("API Key", String.class, true).markSecret();
    private final NodeVariable<Integer> startupTimeoutInput =
            withDefault(new NodeVariable<>("Startup Timeout (s)", Integer.class, true),
                    LlmServerSpec.DEFAULT_STARTUP_TIMEOUT_SECONDS);

    private final NodeVariable<String> serverOutput = new NodeVariable<>("Server", String.class);
    private final NodeVariable<List<?>> modelsOutput = new NodeVariable<>("Models", LIST);
    private final NodeVariable<Boolean> runningOutput = new NodeVariable<>("Running", Boolean.class);

    private final FlowPort start = new FlowPort("Start", FlowPort.Direction.IN);
    private final FlowPort stop = new FlowPort("Stop", FlowPort.Direction.IN);
    private final FlowPort restart = new FlowPort("Restart", FlowPort.Direction.IN);
    private final FlowPort ready = new FlowPort("Ready", FlowPort.Direction.OUT);
    private final FlowPort stopped = new FlowPort("Stopped", FlowPort.Direction.OUT);

    /** The name currently registered in {@link ResourceRegistry}; kept in step with {@link #nameInput}. */
    private String resourceName = LlmServerSpec.DEFAULT_NAME;
    /** True when the server was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    private Button startButton;
    private Button stopButton;
    private Label statusLabel;

    public LlmServerNode() {
        apiInput.setValue(LlmApi.OLLAMA.label());
        // Starting and stopping a server are not things to do two of at once: a Start racing a
        // Restart would have one of them probing an address the other is in the middle of taking
        // down. The engine queues the second run on this node's permit instead.
        setMaxConcurrency(1);
    }

    /**
     * Resolves every input fresh, then does exactly one of three things depending on which flow-in
     * brought control here:
     * <ul>
     *   <li><b>Stop</b> — tears the server down and fires <b>Stopped</b>; nothing else runs.</li>
     *   <li><b>Restart</b> — stops and starts, then fires <b>Ready</b>.</li>
     *   <li>Anything else — <b>Start</b>, and an empty {@code triggeredVia()} (a plain
     *       {@link #beginProcessing()} pull, i.e. the Start button or
     *       {@link #autoStartIfWasRunning()}) — brings the server up if it is not already, then
     *       fires <b>Ready</b>.</li>
     * </ul>
     * A start that fails fires <b>Stopped</b> before throwing, so the failure reaches the node's
     * status line and the log with nothing downstream having been told the server was ready.
     */
    @Override
    public void process(ProcessContext ctx) {
        LlmServerSpec spec = LlmServerSpec.of(
                nameInput.getValue(),
                commandInput.getValue(),
                directoryInput.getValue(),
                apiInput.getValue(),
                serverInput.getValue(),
                apiKeyInput.getValue(),
                startupTimeout());
        syncResourceName(spec.name());

        if (ctx.wasTriggeredVia(stop)) {
            server.stop();
            publish();
            activate(stopped);
            return;
        }

        // The last cheap moment to notice a superseded or cancelled run: what follows is a spawn and
        // then a wait that only an interrupt can cut short.
        ctx.checkCancelled();
        try {
            if (ctx.wasTriggeredVia(restart)) {
                server.restart(spec);
            } else {
                server.start(spec);
            }
        } catch (IOException e) {
            publish();
            activate(stopped);
            // Carry the cause's message in the text, not just the cause: this is what reaches the
            // status label via getLastError().getMessage(). "failed to start" on its own names the
            // node and says nothing about why, and why is the whole point of failing loudly.
            throw new LlmException("The LLM server '" + spec.name() + "' failed to start: "
                    + e.getMessage(), e);
        }
        publish();
        activate(ready);
    }

    /** Copies what the process knows onto the data outputs, so they say the same thing the status line does. */
    private void publish() {
        serverOutput.setValue(server.address());
        modelsOutput.setValue(List.copyOf(server.models()));
        runningOutput.setValue(server.isRunning());
    }

    /** The authored startup timeout, or the default when the field is empty. */
    private int startupTimeout() {
        Integer seconds = startupTimeoutInput.getValue();
        return seconds == null ? LlmServerSpec.DEFAULT_STARTUP_TIMEOUT_SECONDS : seconds;
    }

    /** Re-registers under the current Name if it changed since the last run. */
    private void syncResourceName(String name) {
        if (!name.equals(resourceName)) {
            ResourceRegistry.shared().unregister(resourceName);
            resourceName = name;
            ResourceRegistry.shared().register(resourceName, server);
        }
    }

    @Override
    public void configureInputs() {
        addInput(nameInput);
        addInput(commandInput);
        addInput(directoryInput);
        addInput(serverInput);
        addInput(apiInput);
        addInput(apiKeyInput);
        addInput(startupTimeoutInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(serverOutput);
        addOutput(modelsOutput);
        addOutput(runningOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(start);
        addFlowInput(stop);
        addFlowInput(restart);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(ready);
        addFlowOutput(stopped);
    }

    /**
     * Whether the server was up, and nothing else. Every setting is an input port and is saved as
     * one; duplicating any of them here would be a second copy to disagree with the first.
     */
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
        wasRunning = Boolean.parseBoolean(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (wasRunning) {
            startInBackground();
        }
    }

    @Override
    protected void onActivated() {
        resourceName = authoredName();
        ResourceRegistry.shared().register(resourceName, server);
    }

    /** The Name input, or {@link LlmServerSpec#DEFAULT_NAME} when it is blank — the same rule the spec applies. */
    private String authoredName() {
        String typed = nameInput.getValue();
        return (typed == null || typed.isBlank()) ? LlmServerSpec.DEFAULT_NAME : typed.trim();
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
     * The slow half: signal the process tree, wait for it to go, wait for the address to fall
     * silent. Ten seconds in the worst case, which is why it cannot live in {@link #onRemoved()} —
     * that runs on the shutdown thread with no limit on it, so this could outlast HouseGraph's whole
     * shutdown budget and get the JVM killed mid-teardown, orphaning the very process it was
     * reaping. Here it runs on a worker under the engine's per-node limit, alongside every other
     * node's. A no-op for an adopted server, which was never ours to stop.
     */
    @Override
    protected void releaseResources() {
        server.stop();
    }

    /**
     * Reflects the outcome of any {@code process()} pass in the status label and buttons.
     * Idempotent: every path computes the same text from the process's own state and
     * {@link #getLastError()}, so whichever runs last leaves the correct state either way. Reached
     * on the FX thread; a no-op if the node's UI was never built.
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
            statusLabel.setText((server.isAdopted() ? "Adopted " : "Running at ") + server.address()
                    + " — " + server.detail());
        } else {
            statusLabel.setText("Stopped");
        }
        startButton.setDisable(server.isRunning());
        stopButton.setDisable(!server.isRunning());
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        startButton = new Button("Start");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> startInBackground());

        stopButton = new Button("Stop");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopInBackground());

        statusLabel = new Label("Stopped");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        return new VBox(4, new HBox(6, startButton, stopButton), statusLabel);
    }

    /**
     * Runs {@link #process} via {@link #beginProcessing()} on a background thread — a plain pull, so
     * {@code ctx.triggeredVia()} reads empty and {@link #process} takes its Start branch, exactly
     * like a wired Start arrival. UI feedback is left to {@link #onExecuted()}, which the engine
     * calls once the pass finishes either way.
     */
    private void startInBackground() {
        if (statusLabel != null) {
            startButton.setDisable(true);
            statusLabel.setText("Starting…");
        }
        Thread thread = new Thread(this::beginProcessing, "llm-server-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Tears the server down on a background thread. {@link LlmServerProcess#stop()} waits for the
     * child tree to actually exit and for its address to fall silent — several seconds — so running
     * it inline would freeze the canvas for the duration.
     */
    private void stopInBackground() {
        stopButton.setDisable(true);
        statusLabel.setText("Stopping…");

        Thread thread = new Thread(() -> {
            server.stop();
            Platform.runLater(() -> {
                statusLabel.setText("Stopped");
                startButton.setDisable(false);
            });
        }, "llm-server-stop-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    /** Test seam: whether the loaded graph had this server running, i.e. auto-start is pending. */
    boolean wasRunning() {
        return wasRunning;
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
