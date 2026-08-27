package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmApi;
import io.github.jaymcole.housegraph.plugins.llm.LlmModels;
import io.github.jaymcole.housegraph.plugins.llm.LlmServerStatus;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;

import java.util.List;

/**
 * Asks whether a model server is up, and branches on the answer. The node to put in front of
 * anything that assumes one is: a Local LLM Server node's Start is cheap when the server is already
 * running, but a notification, a fallback to a hosted model, or an alert about a machine that lost
 * its LLM all need the question asked and answered rather than assumed.
 * <p>
 * <b>A server that is down is not a failure.</b> Every other node in this library throws when it
 * cannot reach a server, because a prompt that cannot be sent has nothing to hand downstream. This
 * one is asked precisely that question, so "nothing is listening" comes out of <b>Not Running</b>
 * with the reason on <b>Detail</b>, and the run carries on. What does fail the node is a Server
 * that is blank or not a usable address, or an API naming a protocol this library does not know —
 * a graph that is wrong, which no amount of waiting fixes.
 * <p>
 * <b>Models</b> is what the server says it has — Ollama's pulled models, or whatever an
 * OpenAI-compatible server lists — and is empty both when the server is down and when it is up with
 * nothing installed; <b>Running</b> tells those apart. A List Contains node against Models is how a
 * graph checks for the model it is about to prompt with, though note that Ollama tags its models,
 * so a machine with {@code llama3.2} pulled reports {@code llama3.2:latest}. The Pull Model node
 * does that comparison properly and is usually the better answer.
 * <p>
 * <b>The check is cheap and loads nothing.</b> It reads the list of models the server already has
 * in hand — Ollama's {@code /api/tags}, an OpenAI-compatible server's {@code /v1/models} — so it is
 * safe to wire to a repeating trigger. <b>Timeout (s)</b> defaults to
 * {@value io.github.jaymcole.housegraph.plugins.llm.LlmModels#DEFAULT_STATUS_TIMEOUT_SECONDS}
 * seconds because a local server that has not answered in that time is not going to.
 * <p>
 * <b>Purely flow-driven, like everything else here that is not the server node itself</b>: it has
 * no timer. Wire a Repeating Trigger, a Daily Trigger or a web hook into the flow input and the
 * same node serves all three.
 */
@Display.Name("LLM Server Status")
@Display.Description("Checks whether a model server is up, and branches on the answer.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"llm", "ai", "ollama", "server", "status", "health", "check", "running", "up",
        "models", "local", "openai", "branch"})
@Node.Type("llm.LlmServerStatusNode")
public class LlmServerStatusNode extends BaseNode {

    /** See {@code LlmServerNode.LIST} — a list port's element type is erased, so this is the type. */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    private final NodeVariable<String> serverInput =
            withDefault(new NodeVariable<>("Server", String.class, true), LocalLlmClient.DEFAULT_SERVER);
    private final NodeVariable<String> apiInput = new NodeVariable<>("API", String.class, true);
    private final NodeVariable<String> apiKeyInput =
            new NodeVariable<>("API Key", String.class, true).markSecret();
    private final NodeVariable<Integer> timeoutInput =
            withDefault(new NodeVariable<>("Timeout (s)", Integer.class, true),
                    LlmModels.DEFAULT_STATUS_TIMEOUT_SECONDS);

    private final NodeVariable<Boolean> runningOutput = new NodeVariable<>("Running", Boolean.class);
    private final NodeVariable<List<?>> modelsOutput = new NodeVariable<>("Models", LIST);
    private final NodeVariable<String> detailOutput = new NodeVariable<>("Detail", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort running = new FlowPort("Running", FlowPort.Direction.OUT);
    private final FlowPort notRunning = new FlowPort("Not Running", FlowPort.Direction.OUT);

    public LlmServerStatusNode() {
        apiInput.setValue(LlmApi.OLLAMA.label());
    }

    @Override
    public void process(ProcessContext ctx) {
        LlmApi api = LlmApi.parse(apiInput.getValue());
        // The last cheap moment to notice a superseded or cancelled run before the HTTP call.
        ctx.checkCancelled();
        LlmServerStatus status = LlmModels.status(api, serverInput.getValue(), apiKeyInput.getValue(),
                timeoutSeconds());

        runningOutput.setValue(status.running());
        modelsOutput.setValue(status.models());
        detailOutput.setValue(status.detail());
        // Exactly one of the two, always: the engine fires every flow output when a run activated
        // none, so leaving both alone on either branch would fire both.
        activate(status.running() ? running : notRunning);
    }

    /** The authored timeout, or the default when the field is empty. A zero or negative one is clamped downstream. */
    private int timeoutSeconds() {
        Integer seconds = timeoutInput.getValue();
        return seconds == null ? LlmModels.DEFAULT_STATUS_TIMEOUT_SECONDS : seconds;
    }

    @Override
    public void configureInputs() {
        addInput(serverInput);
        addInput(apiInput);
        addInput(apiKeyInput);
        addInput(timeoutInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(runningOutput);
        addOutput(modelsOutput);
        addOutput(detailOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(running);
        addFlowOutput(notRunning);
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
