package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmApi;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversation;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversations;
import io.github.jaymcole.housegraph.plugins.llm.LlmMessage;
import io.github.jaymcole.housegraph.plugins.llm.LlmRequest;
import io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient;

import java.util.List;

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
 *
 * <h2>Remembering a conversation</h2>
 * <b>Left alone, every run is turn one.</b> Name a conversation in <b>Conversation ID</b> and the
 * node remembers instead: what was asked and what came back are kept under that name, and the next
 * prompt naming it is sent with them. Blank — the default — is the behaviour this node has always
 * had, one question at a time with nothing carried over, and it deliberately does not mean "one
 * shared conversation for everybody": an unwired field must not put every user of a bot into the
 * same conversation with each other.
 * <p>
 * <b>The id is the graph's choice, and that is the point.</b> A Discord slash command's Sender ID
 * gives each person their own conversation across every channel; a channel id gives one shared
 * conversation in a room; Format Text composes anything else. This node knows nothing about Discord
 * and does not need to. Any node naming the same id shares that conversation — two Local LLM nodes,
 * or a {@link ClearConversationNode}, with no edge between them (see {@link LlmConversations}).
 * <p>
 * <b>Two flow-ins, told apart:</b> <b>Ask</b> prompts the model, and <b>Clear</b> forgets this
 * node's conversation without asking anything — a {@code /reset} command wired straight into the
 * node that does the talking. A Clear run publishes Turns 0 and Response {@code ""}, validates
 * nothing, and contacts no server, so a reset from someone who has never said anything is a
 * success rather than an empty-Prompt failure.
 * <p>
 * <b>Do not fan one trigger out to both ports.</b> Sibling flow edges run concurrently and this
 * node fires once, so "clear, then ask" wired that way races: only the arrivals recorded by the
 * time the firing starts are seen, and the clear can be silently dropped (the reason Collect Items
 * and Stored Value were split — see {@link ClearConversationNode}). Sequencing needs the clear to
 * be <em>upstream</em>: trigger &rarr; {@link ClearConversationNode} &rarr; this node, under the
 * same id. Clear is for a trigger of its own, the way Start and Stop are on the server node; both
 * arriving together anyway is handled in the only order that makes sense — forget, then ask — but
 * that is a backstop, not a wiring to rely on.
 * <p>
 * <b>History Turns</b> is how many previous exchanges are re-sent and kept, most recent first to
 * go; it is a rough stand-in for the model's context window, which is measured in tokens this
 * library cannot count, so a very long history against a small model still fails at the server.
 * <b>Forget After (min)</b> drops a conversation nobody has continued for that long — 0 keeps it
 * for as long as HouseGraph runs, leaving Clear as the only thing that ends it. <b>Turns</b> reports how many exchanges the conversation holds
 * after this run, and is 0 when none is named.
 * <p>
 * <b>Nothing is remembered across a restart</b>, and nothing is written to the save file: a graph
 * file is not the place for somebody's private conversation with a bot. <b>A failed run records
 * nothing</b> either, so a timeout or a server that is down leaves the conversation exactly as it
 * was rather than half of an exchange that never happened.
 *
 * <h2>The rest of the inputs</h2>
 * <b>System Prompt</b> sets the standing instruction ("answer in one sentence", "you are a
 * doorbell"), which is the input worth reaching for when the answer is the right idea in the wrong
 * shape. <b>Temperature</b> is left to the server unless you set it: 0 for the most repeatable
 * answer, higher for a more varied one.
 * <p>
 * <b>Context (tokens)</b> is the one to reach for when a conversation answers worse the longer it
 * gets. Ollama gives a model a default context window and <b>silently drops the oldest tokens</b>
 * that do not fit rather than complaining — so a history grown past it loses its earliest turns, and
 * the system prompt with them, which reads as a model that has become confused rather than one that
 * was cut short. Setting this sends {@code num_ctx}; blank keeps the server's own default. It costs
 * memory, so raise it to what the conversation needs rather than to the model's maximum. <b>It is
 * Ollama-only</b>, like Pull Model: an OpenAI-compatible server is told its context size when it is
 * launched (llama.cpp's {@code -c}) rather than per request, so the field does nothing there.
 * <p>
 * <b>API Key</b> stays empty for a normal local server and
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
        "completion", "openai", "lmstudio", "text", "conversation", "memory", "history", "context"})
@Node.Type("llm.LocalLlmPromptNode")
public class LocalLlmPromptNode extends BaseNode {

    /**
     * Eight exchanges — enough for a conversation that refers back to itself, small enough that it
     * still fits a modest context window alongside a long answer. Sixteen messages of chat is also
     * about as far back as anyone expects a bot to remember without being told to.
     */
    public static final int DEFAULT_HISTORY_TURNS = 8;

    /**
     * An hour. A conversation is a thing with a beginning and an end, and picking up yesterday's
     * half-finished exchange is more often a surprise than a feature; 0 keeps it for as long as
     * HouseGraph runs.
     */
    public static final int DEFAULT_FORGET_AFTER_MINUTES = 60;

    private final NodeVariable<String> prompt = new NodeVariable<>("Prompt", String.class, true).required();
    private final NodeVariable<String> system = new NodeVariable<>("System Prompt", String.class, true);
    private final NodeVariable<String> conversation = new NodeVariable<>("Conversation ID", String.class, true);
    private final NodeVariable<Integer> historyTurns = new NodeVariable<>("History Turns", Integer.class, true);
    private final NodeVariable<Integer> forgetAfter = new NodeVariable<>("Forget After (min)", Integer.class, true);
    private final NodeVariable<String> model = new NodeVariable<>("Model", String.class, true).required();
    private final NodeVariable<String> server = new NodeVariable<>("Server", String.class, true).required();
    private final NodeVariable<String> api = new NodeVariable<>("API", String.class, true);
    private final NodeVariable<Float> temperature = new NodeVariable<>("Temperature", Float.class, true);
    private final NodeVariable<Integer> contextTokens = new NodeVariable<>("Context (tokens)", Integer.class, true);
    private final NodeVariable<String> apiKey = new NodeVariable<>("API Key", String.class, true).markSecret();
    private final NodeVariable<Integer> timeout = new NodeVariable<>("Timeout (s)", Integer.class, true);

    private final NodeVariable<String> response = new NodeVariable<>("Response", String.class);
    private final NodeVariable<Integer> turns = new NodeVariable<>("Turns", Integer.class);

    /**
     * <b>Named, though it is the only flow-in again.</b> The convention for a single-purpose
     * flow-in is a bare anchor, and this port held one until it briefly had a Clear port beside it
     * in v3.0.0. Going back to blank would drop the flow edge of every graph saved against that
     * version — a blank-named port is referenced by position and a named one by name, and the name
     * is what those saves recorded. Keeping it costs a label; changing it costs somebody's wiring.
     */
    private final FlowPort ask = new FlowPort("Ask", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    public LocalLlmPromptNode() {
        // Pre-filled rather than blank: these four are what "a local LLM" means on a machine where
        // nothing has been moved, and a field showing a value is also how someone discovers what
        // belongs in it. Every one is still just text to edit or wire over.
        server.setValue(LocalLlmClient.DEFAULT_SERVER);
        model.setValue(LocalLlmClient.DEFAULT_MODEL);
        api.setValue(LlmApi.OLLAMA.label());
        timeout.setValue(LocalLlmClient.DEFAULT_TIMEOUT_SECONDS);

        // Conversation is left blank on purpose - see the class documentation. These two only do
        // anything once it isn't, and are pre-filled for the same reason the four above are: a
        // field showing a value is how someone discovers what belongs in it.
        historyTurns.setValue(DEFAULT_HISTORY_TURNS);
        forgetAfter.setValue(DEFAULT_FORGET_AFTER_MINUTES);

        // One prompt at a time by default - see the class documentation. The engine queues the
        // second run on this node's permit rather than sending both at the machine's one GPU.
        setMaxConcurrency(1);
    }

    /**
     * Looks up what this conversation already holds, prompts the model with it, and records the
     * exchange — in that order, and with the recording last on purpose.
     * <p>
     * The conversation is read and written under its own monitor but the call in between is made
     * outside it, because a prompt can take minutes and nothing should be queued behind a lock for
     * that long. Two runs on one conversation therefore both send the history as it was and both
     * append to it; see {@link LlmConversation}.
     */
    @Override
    public void process(ProcessContext ctx) {
        LlmConversation chat = conversation();
        int keep = historyTurns();
        List<LlmMessage> history = chat == null ? List.of() : chat.history(keep);
        LlmRequest request = new LlmRequest(
                LlmApi.parse(api.getValue()),
                server.getValue(),
                model.getValue(),
                system.getValue(),
                history,
                chat != null,
                prompt.getValue(),
                temperature.getValue(),
                contextTokens.getValue(),
                apiKey.getValue(),
                timeoutSeconds());
        // The last cheap moment to notice a superseded or cancelled run: everything after this is
        // one blocking call that only an interrupt can stop.
        ctx.checkCancelled();
        String reply = LocalLlmClient.generate(request);
        response.setValue(reply);
        // Only now, with an answer in hand: a run that threw above leaves the conversation as it
        // was, so a retry doesn't ask the same question twice.
        if (chat != null) {
            chat.record(request.prompt(), reply, keep);
        }
        turns.setValue(chat == null ? 0 : chat.exchanges());
    }

    /**
     * The conversation this run belongs to, or null when none is named — the one-shot behaviour
     * this node had before conversations existed. A name is trimmed, so a field holding spaces is
     * blank rather than a conversation called " ".
     */
    private LlmConversation conversation() {
        String name = conversationId();
        if (name.isEmpty() || historyTurns() <= 0) {
            return null;
        }
        return LlmConversations.shared().get(name, forgetAfterMinutes());
    }

    /** The authored conversation, trimmed — a field holding spaces names nothing rather than " ". */
    private String conversationId() {
        String name = conversation.getValue();
        return name == null ? "" : name.trim();
    }

    /**
     * How many exchanges to re-send and keep. An empty field is the default; 0 turns memory off
     * for this node without having to unwire the name it was given.
     */
    private int historyTurns() {
        Integer exchanges = historyTurns.getValue();
        return exchanges == null ? DEFAULT_HISTORY_TURNS : Math.max(0, exchanges);
    }

    /** How long the conversation may sit idle. An empty field is the default; 0 or less means never. */
    private int forgetAfterMinutes() {
        Integer minutes = forgetAfter.getValue();
        return minutes == null ? DEFAULT_FORGET_AFTER_MINUTES : minutes;
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
        addInput(conversation);
        addInput(historyTurns);
        addInput(forgetAfter);
        addInput(model);
        addInput(server);
        addInput(api);
        addInput(temperature);
        addInput(contextTokens);
        addInput(apiKey);
        addInput(timeout);
    }

    @Override
    public void configureOutputs() {
        addOutput(response);
        addOutput(turns);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(ask);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
