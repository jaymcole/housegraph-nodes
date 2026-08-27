package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmModels;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;

/**
 * Makes sure an Ollama server has a model, downloading it if it does not — the other half of
 * setting a local LLM up without a terminal. A Local LLM Server node brings the server up; this
 * puts something in it to prompt.
 * <p>
 * <b>Ready</b> fires every time this runs and the model is present afterwards, whether or not
 * anything was downloaded — that is the port to wire a Local LLM node to. <b>Pulled</b> fires only
 * when this run actually fetched the model, so a "the machine downloaded a new model" notification
 * chained off it does not fire on every check. <b>Downloaded</b> carries the same fact as data.
 * That split is the Git Sync node's Checked/Pulled, for the same reason: the common case is a
 * no-op, and a graph should be able to tell the two apart.
 * <p>
 * <b>Ollama only.</b> Ollama has a model registry and an API to fetch from it. llama.cpp, LM Studio
 * and vLLM are pointed at a file or a Hugging Face id that somebody put on the disk themselves and
 * have no equivalent endpoint, so there is no API setting here and pointing this at one of them
 * fails rather than appearing to work.
 * <p>
 * <b>It checks before it pulls</b>, and it checks by the name a person types: Ollama tags its
 * models, so a machine with {@code llama3.2} pulled reports {@code llama3.2:latest}, and comparing
 * those literally would re-download a model that is already there on every run. A Model that names
 * a tag ({@code llama3.2:1b}) is matched exactly, since asking for a specific tag means it.
 * <p>
 * <b>A pull is gigabytes and can take a very long time.</b> <b>Timeout (s)</b> defaults to
 * {@value io.github.jaymcole.housegraph.plugins.llm.LlmModels#DEFAULT_PULL_TIMEOUT_SECONDS}
 * seconds — an hour — because there is no useful smaller number: a 20-minute timeout on a
 * 40-minute download throws away the 20 minutes it already spent. Nothing streams a percentage
 * back, since there is no port a percentage could go out of; the {@code [llm]} lines in
 * HouseGraph's log are the place to watch if the server is one a Local LLM Server node started.
 * <p>
 * <b>One pull at a time.</b> The node ships with a concurrency limit of one — two runs arriving
 * together would have one machine downloading two models over one connection, which finishes both
 * later than doing them in turn.
 */
@Display.Name("Pull Model")
@Display.Description("Makes sure an Ollama server has a model, downloading it if it does not.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"llm", "ai", "ollama", "pull", "download", "fetch", "model", "install", "local"})
@Node.Type("llm.PullModelNode")
public class PullModelNode extends BaseNode {

    private final NodeVariable<String> modelInput =
            withDefault(new NodeVariable<>("Model", String.class, true), LocalLlmClient.DEFAULT_MODEL)
                    .required();
    private final NodeVariable<String> serverInput =
            withDefault(new NodeVariable<>("Server", String.class, true), LocalLlmClient.DEFAULT_SERVER)
                    .required();
    private final NodeVariable<String> apiKeyInput =
            new NodeVariable<>("API Key", String.class, true).markSecret();
    private final NodeVariable<Integer> timeoutInput =
            withDefault(new NodeVariable<>("Timeout (s)", Integer.class, true),
                    LlmModels.DEFAULT_PULL_TIMEOUT_SECONDS);

    private final NodeVariable<String> modelOutput = new NodeVariable<>("Model", String.class);
    private final NodeVariable<Boolean> downloadedOutput = new NodeVariable<>("Downloaded", Boolean.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort ready = new FlowPort("Ready", FlowPort.Direction.OUT);
    private final FlowPort pulled = new FlowPort("Pulled", FlowPort.Direction.OUT);

    public PullModelNode() {
        // One download at a time - see the class documentation. The engine queues the second run on
        // this node's permit rather than putting two models down one connection.
        setMaxConcurrency(1);
    }

    @Override
    public void process(ProcessContext ctx) {
        String model = modelInput.getValue();
        // The last cheap moment to notice a superseded or cancelled run: everything after this is
        // one blocking call that only an interrupt can stop.
        ctx.checkCancelled();
        boolean downloaded = LlmModels.pull(serverInput.getValue(), model, apiKeyInput.getValue(),
                timeoutSeconds());

        modelOutput.setValue(model == null ? "" : model.trim());
        downloadedOutput.setValue(downloaded);
        // Activated after the pull, not before: a pull that threw should leave both ports alone so
        // nothing downstream is told the model is there. Ready before Pulled so that the engine's
        // "activated nothing -> fire everything" default cannot turn a failure into a pull.
        activate(ready);
        if (downloaded) {
            activate(pulled);
        }
    }

    /** The authored timeout, or the default when the field is empty. A zero or negative one is clamped downstream. */
    private int timeoutSeconds() {
        Integer seconds = timeoutInput.getValue();
        return seconds == null ? LlmModels.DEFAULT_PULL_TIMEOUT_SECONDS : seconds;
    }

    @Override
    public void configureInputs() {
        addInput(modelInput);
        addInput(serverInput);
        addInput(apiKeyInput);
        addInput(timeoutInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(modelOutput);
        addOutput(downloadedOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(ready);
        addFlowOutput(pulled);
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
