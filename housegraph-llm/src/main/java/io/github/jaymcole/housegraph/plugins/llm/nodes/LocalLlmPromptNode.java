package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmApi;
import io.github.jaymcole.housegraph.plugins.llm.LlmRequest;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;

/**
 * Sends <b>Prompt</b> to a language model running on this machine and puts what it generated on
 * <b>Response</b>. Text in, text out: everything else on the node is about which model, where, and
 * how patient to be.
 * <p>
 * <b>Out of the box it talks to Ollama on this machine</b> — Server is pre-filled with
 * {@code http://localhost:11434} and Model with {@code llama3.2}, so the node works as soon as
 * {@code ollama serve} is running and that model is pulled. Set <b>API</b> to {@code openai} for
 * anything speaking OpenAI's {@code /v1/chat/completions} instead: llama.cpp's server, LM Studio,
 * vLLM, LocalAI. Naming the server works there too — "lm studio" selects the same thing (see
 * {@link LlmApi}).
 * <p>
 * <b>Nothing leaves the machine unless you point it somewhere else.</b> There is no hosted service
 * behind this node and no key to obtain: the address is the address you type. Pointing Server at a
 * remote host is allowed and sometimes what you want (a beefier machine on the LAN), but it is
 * then no longer a local model, and the prompt travels there in the clear over {@code http://}.
 * <p>
 * <b>The first prompt of the day is the slow one.</b> A model that is not resident is loaded from
 * disk when it is first asked, so the first call can take minutes where later ones take seconds.
 * That is what <b>Timeout (s)</b> is for; it defaults to
 * {@value io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient#DEFAULT_TIMEOUT_SECONDS}
 * seconds and applies to the whole answer, not to each token.
 * <p>
 * <b>One prompt at a time.</b> The node ships with a concurrency limit of one, so two runs
 * arriving together queue instead of asking the model twice at once — a local server has one GPU
 * to share and answers two concurrent prompts slower than two consecutive ones. Raise it in the
 * node's settings if the server is genuinely able to batch.
 * <p>
 * <b>System Prompt</b> sets the standing instruction ("answer in one sentence", "you are a
 * doorbell"), which is the input worth reaching for when the answer is the right idea in the wrong
 * shape. <b>Temperature</b> is left to the server unless you set it: 0 for the most repeatable
 * answer, higher for a more varied one. <b>API Key</b> stays empty for a normal local server and
 * exists for one started behind a token (llama.cpp's {@code --api-key}); it is marked secret, so
 * it is never written into a save file — wire a Secret Loader into it rather than typing it in.
 * <p>
 * <b>A failure fails the node</b> rather than emitting empty text: no server listening, no such
 * model, a reply in the other protocol's shape, or a timeout each stop the run with a message
 * saying which (see {@link LocalLlmClient}). An answer that came back empty is not a failure — the
 * model was asked and said nothing — so Response is {@code ""} and the flow carries on.
 */
@Display.Name("Local LLM")
@Display.Description("Prompts a language model running on this machine and returns its reply as text.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"llm", "ai", "ollama", "llama", "local", "model", "prompt", "generate", "chat",
        "completion", "openai", "lmstudio", "text"})
@Node.Type("llm.LocalLlmPromptNode")
public class LocalLlmPromptNode extends BaseNode {

    private final NodeVariable<String> prompt = new NodeVariable<>("Prompt", String.class, true).required();
    private final NodeVariable<String> system = new NodeVariable<>("System Prompt", String.class, true);
    private final NodeVariable<String> model = new NodeVariable<>("Model", String.class, true).required();
    private final NodeVariable<String> server = new NodeVariable<>("Server", String.class, true).required();
    private final NodeVariable<String> api = new NodeVariable<>("API", String.class, true);
    private final NodeVariable<Float> temperature = new NodeVariable<>("Temperature", Float.class, true);
    private final NodeVariable<String> apiKey = new NodeVariable<>("API Key", String.class, true).markSecret();
    private final NodeVariable<Integer> timeout = new NodeVariable<>("Timeout (s)", Integer.class, true);

    private final NodeVariable<String> response = new NodeVariable<>("Response", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    public LocalLlmPromptNode() {
        // Pre-filled rather than blank: these four are what "a local LLM" means on a machine where
        // nothing has been moved, and a field showing a value is also how someone discovers what
        // belongs in it. Every one is still just text to edit or wire over.
        server.setValue(LocalLlmClient.DEFAULT_SERVER);
        model.setValue(LocalLlmClient.DEFAULT_MODEL);
        api.setValue(LlmApi.OLLAMA.label());
        timeout.setValue(LocalLlmClient.DEFAULT_TIMEOUT_SECONDS);

        // One prompt at a time by default - see the class documentation. The engine queues the
        // second run on this node's permit rather than sending both at the machine's one GPU.
        setMaxConcurrency(1);
    }

    @Override
    public void process(ProcessContext ctx) {
        LlmRequest request = new LlmRequest(
                LlmApi.parse(api.getValue()),
                server.getValue(),
                model.getValue(),
                system.getValue(),
                prompt.getValue(),
                temperature.getValue(),
                apiKey.getValue(),
                timeoutSeconds());
        // The last cheap moment to notice a superseded or cancelled run: everything after this is
        // one blocking call that only an interrupt can stop.
        ctx.checkCancelled();
        response.setValue(LocalLlmClient.generate(request));
    }

    /** The authored timeout, or the default when the field is empty. A zero or negative one is clamped by {@link LlmRequest}. */
    private int timeoutSeconds() {
        Integer seconds = timeout.getValue();
        return seconds == null ? LocalLlmClient.DEFAULT_TIMEOUT_SECONDS : seconds;
    }

    @Override
    public void configureInputs() {
        addInput(prompt);
        addInput(system);
        addInput(model);
        addInput(server);
        addInput(api);
        addInput(temperature);
        addInput(apiKey);
        addInput(timeout);
    }

    @Override
    public void configureOutputs() {
        addOutput(response);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
