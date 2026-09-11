package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmAnswer;
import io.github.jaymcole.housegraph.plugins.llm.LlmApi;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversation;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversations;
import io.github.jaymcole.housegraph.plugins.llm.LlmMessage;
import io.github.jaymcole.housegraph.plugins.llm.LlmPhase;
import io.github.jaymcole.housegraph.plugins.llm.LlmProgress;
import io.github.jaymcole.housegraph.plugins.llm.LlmRequest;
import io.github.jaymcole.housegraph.plugins.llm.LlmUpdate;
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
 * <h2>Watching it work</h2>
 * <b>Left alone, the node is silent until it has the whole answer.</b> Set <b>Update Every (ms)</b>
 * and it streams instead: the answer is read as the model writes it, and the <b>Update</b> flow
 * output fires about that often with what has arrived so far. 0 - the default - is the behaviour
 * this node has always had, one request and one reply, and is what an unwired Update port should
 * cost. 1000 is a sensible first value; below a few hundred you are mostly measuring whatever the
 * Update branch does rather than the model.
 * <p>
 * <b>Update fires during the run; the unnamed flow output fires once, at the end.</b> That is the
 * one to wire the real answer to. Every Update branch has run to completion before it fires, so a
 * status display cannot land after the answer that replaces it - this node drives each update as
 * its own sub-run and waits for it, the way a For Each node drives its body.
 * <p>
 * <b>Four outputs describe a run in progress.</b> <b>Answer So Far</b> is everything answered up to
 * that update - the one to wire where a whole message is replaced each time, such as an edit to a
 * Discord reply. <b>New Text</b> is only what arrived since the last update, for somewhere that
 * appends rather than replaces. <b>Thinking</b> is the model's reasoning, and <b>Phase</b> says
 * which of the two the model is producing right now: {@code thinking}, then {@code answering}, and
 * {@code done} on the final firing. A model that cannot think is {@code answering} from its first
 * update, so a graph needs no special case for one.
 * <p>
 * <b>Response is empty on an Update.</b> A run that has not finished has no answer, and publishing
 * the <em>previous</em> run's would be worse than publishing nothing - a status update that briefly
 * shows the last person's reply. Read Answer So Far on an Update and Response on the end.
 * <p>
 * <b>The last update of a run is sometimes the whole answer.</b> Nothing can tell that a piece of
 * an answer is the final one until the server says so a moment later, so an update that happens to
 * land on the last of them publishes text the end of the run then publishes again. It costs one
 * redundant edit, at the end, on the runs where the timing falls that way - which is the cheaper
 * side of the trade against holding every update back by one piece to be sure.
 * <p>
 * <b>Updates coalesce rather than queue.</b> Tokens arriving while an Update branch is still
 * running are accumulated, and the next update carries the newest state - so a slow branch (a
 * Discord edit is a network round trip) makes updates less frequent rather than putting the node
 * further and further behind. An interval that passes with no new text publishes nothing at all.
 * <p>
 * <b>Streaming is also what makes a cancelled run stop.</b> Cancellation is checked at each piece
 * of the answer, so a superseded or timed-out run ends within a token or so; the non-streaming
 * call has nothing to check at and runs until the model is finished whatever the engine wants.
 * <p>
 * <b>Think</b> turns a thinking model's reasoning on. Blank - the default - sends nothing, which
 * <em>is</em> the important case: Ollama answers HTTP 400 for a model that cannot think, so a
 * setting sent by default would break every graph pointed at an ordinary model. {@code true},
 * {@code false}, or a level ({@code low}, {@code medium}, {@code high}) are passed through for the
 * server to interpret. <b>It is Ollama-only</b>, like Context (tokens): an OpenAI-compatible server
 * has no agreed request field for it. Reasoning is read back from a streamed answer either way
 * where the server sends it - llama.cpp and vLLM both stream it - so Thinking can be populated
 * without this field, by a model that reasons of its own accord.
 * <p>
 * <b>A failure part-way through leaves what was already shown.</b> Nothing un-sends a Discord edit,
 * so a run that streamed half an answer and then failed has half an answer on screen and a failed
 * node. The conversation is still untouched - see above - so the exchange that never finished is
 * not remembered, and a retry starts clean.
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
        "completion", "openai", "lmstudio", "text", "conversation", "memory", "history", "context",
        "stream", "streaming", "progress", "update", "thinking", "reasoning", "partial", "live"})
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

    /**
     * Off. Streaming costs an Update branch of the graph every interval, and a node whose Update
     * port nobody wired should cost nothing at all - so this is the one pre-filled field that is
     * pre-filled with "don't". It is also what keeps a graph saved before this existed doing
     * exactly what it did: one request, one reply, no sub-runs.
     */
    public static final int DEFAULT_UPDATE_EVERY_MILLIS = 0;

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
    private final NodeVariable<String> think = new NodeVariable<>("Think", String.class, true);
    private final NodeVariable<Integer> updateEvery = new NodeVariable<>("Update Every (ms)", Integer.class, true);
    private final NodeVariable<String> apiKey = new NodeVariable<>("API Key", String.class, true).markSecret();
    private final NodeVariable<Integer> timeout = new NodeVariable<>("Timeout (s)", Integer.class, true);

    private final NodeVariable<String> response = new NodeVariable<>("Response", String.class);
    private final NodeVariable<String> thinking = new NodeVariable<>("Thinking", String.class);
    private final NodeVariable<String> answerSoFar = new NodeVariable<>("Answer So Far", String.class);
    private final NodeVariable<String> newText = new NodeVariable<>("New Text", String.class);
    private final NodeVariable<String> phase = new NodeVariable<>("Phase", String.class);
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

    /**
     * Fired repeatedly <em>during</em> a streamed run, and never when Update Every (ms) is 0.
     * <p>
     * <b>Its existence is why {@link #process} now ends with {@code activate(out)}.</b> A node that
     * activates no port fires every port it has, which was free while there was only one: with two,
     * the default would fire a last Update alongside the answer at the end of every run - the
     * status display flickering back to the half-answer it had just replaced.
     */
    private final FlowPort update = new FlowPort("Update", FlowPort.Direction.OUT);

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

        // Off, and pre-filled rather than blank so the field says out loud that 0 is a setting
        // and not an empty box somebody forgot to fill in. Think is left blank on purpose: blank
        // and "false" are different requests, and only blank is safe on every model.
        updateEvery.setValue(DEFAULT_UPDATE_EVERY_MILLIS);

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
        int everyMillis = updateEveryMillis();
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
                timeoutSeconds(),
                everyMillis > 0,
                think.getValue());
        // The last cheap moment to notice a superseded or cancelled run. A streamed request can
        // check again at every piece of the answer; an unstreamed one is still one blocking call
        // that only an interrupt can stop.
        ctx.checkCancelled();
        LlmAnswer answer = request.streaming()
                ? streamed(ctx, request, everyMillis, chat)
                : LocalLlmClient.answer(request);

        response.setValue(answer.response());
        thinking.setValue(answer.thinking());
        // The same text as Response, so a graph reading Answer So Far on every firing gets the
        // finished answer on the last one rather than a snapshot that stops a token short.
        answerSoFar.setValue(answer.response());
        newText.setValue("");
        phase.setValue(LlmPhase.DONE.label());
        // Only now, with an answer in hand: a run that threw above leaves the conversation as it
        // was, so a retry doesn't ask the same question twice.
        if (chat != null) {
            chat.record(request.prompt(), answer.response(), keep);
        }
        turns.setValue(chat == null ? 0 : chat.exchanges());
        // Explicitly, now that there are two flow outputs - see the Update port.
        activate(out);
    }

    /**
     * Reads a streamed answer, firing {@link #update} with what has arrived so far about every
     * {@code everyMillis}.
     *
     * @param ctx         the run's context, checked for cancellation at every piece of the answer
     * @param request     what to ask, already built and streaming
     * @param everyMillis the shortest gap between two updates
     * @param chat        the conversation this run belongs to, or null when none is named
     * @return the finished answer
     */
    private LlmAnswer streamed(ProcessContext ctx, LlmRequest request, int everyMillis, LlmConversation chat) {
        LlmProgress progress = new LlmProgress(System.nanoTime());
        // Read before the answer starts and carried into every update: an update is published from
        // inside a sub-run, whose context cannot see this run's values, so everything it sets has
        // to be captured here rather than read there.
        int exchangesSoFar = chat == null ? 0 : chat.exchanges();
        LocalLlmClient.stream(request, progress, chunk -> {
            ctx.checkCancelled();
            // The chunk that says "done" publishes nothing. It carries no text of its own - both
            // protocols end with timings, not words - and the finished answer is a moment away on
            // the unnamed flow output, so an update here would be one more edit of a message that
            // is about to be replaced.
            if (chunk.done()) {
                return;
            }
            LlmUpdate tick = progress.tick(System.nanoTime(), everyMillis);
            if (tick != null) {
                publish(tick, exchangesSoFar);
            }
        });
        return new LlmAnswer(progress.answer(), progress.thinking());
    }

    /**
     * Fires the Update branch once, with {@code tick} on this node's outputs, and waits for it to
     * finish — the mechanism a For Each node's Body port uses, for the same reason: a flow cascade
     * fires each node at most once per run, so a port cannot be activated twice, and each update
     * has to be its own isolated sub-run.
     * <p>
     * <b>Waiting is the point, not a cost.</b> It is what orders the updates against each other and
     * against the final answer, and it is what makes a slow consumer coalesce - the tokens that
     * arrive while this blocks are still accumulating, and the next update carries them all.
     */
    private void publish(LlmUpdate tick, int exchangesSoFar) {
        runFlowBranchToCompletion(update, () -> {
            // Deliberately blank: a run that has not finished has no answer, and the previous
            // run's would be worse than none. See the class documentation.
            response.setValue("");
            thinking.setValue(tick.thinking());
            answerSoFar.setValue(tick.answer());
            newText.setValue(tick.newText());
            phase.setValue(tick.phase().label());
            turns.setValue(exchangesSoFar);
        });
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

    /**
     * How often to publish an update, or 0 for a run that does not stream at all. An empty field is
     * the default (off), and a negative one is off too rather than a nonsense interval.
     */
    private int updateEveryMillis() {
        Integer millis = updateEvery.getValue();
        return millis == null ? DEFAULT_UPDATE_EVERY_MILLIS : Math.max(0, millis);
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
        addInput(think);
        addInput(updateEvery);
        addInput(apiKey);
        addInput(timeout);
    }

    @Override
    public void configureOutputs() {
        addOutput(response);
        addOutput(thinking);
        addOutput(answerSoFar);
        addOutput(newText);
        addOutput(phase);
        addOutput(turns);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(ask);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
        addFlowOutput(update);
    }
}
