package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversation;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversations;

import java.util.Optional;

/**
 * Forgets one conversation, from anywhere on the canvas. Give this node the same
 * <b>Conversation ID</b> a Local LLM node is using and flow into it; nothing connects the two but
 * that id, since the history has always lived under it rather than in either node's fields (see
 * {@link LlmConversations}).
 * <p>
 * <b>Local LLM has its own Clear port, and this node is still the one you want for sequencing.</b>
 * Clearing from a trigger of its own — a {@code /reset} command — is what that port is for, and it
 * saves a node. But "clear, then ask, from one trigger" cannot be done on one node: sibling flow
 * edges run concurrently and the node fires once, so the clear can be silently dropped. That is
 * what split Collect Items into Add/Clear Collection and took the Clear port off Stored Value.
 * Putting the clear <em>upstream</em> makes the order a fact rather than a hope:
 * <pre>trigger &rarr; Clear Conversation &rarr; Local LLM</pre>
 * It is also the way to reset a conversation from a graph with no prompt node in it, and the only
 * way to see <b>Forgotten</b> — how much was actually thrown away.
 * <p>
 * <b>Being pulled for data does nothing.</b> A downstream node resolving Forgotten or Found without
 * any flow arriving here answers whether there is a conversation and leaves it alone — the same
 * rule Clear Stored Value and Clear Collection follow, because a value read must never be a side
 * effect. Forgotten is then 0, because nothing was: a count of what is still there would be a lie
 * told by that port's name. It does not start a conversation either — asking whether one exists
 * must not be what creates it.
 * <p>
 * <b>Forgetting a conversation nobody started is not a failure.</b> Found is false, Forgotten is 0,
 * and the flow carries on — which is what a {@code /reset} typed by someone who has not said
 * anything yet should do.
 */
@Display.Name("Clear Conversation")
@Display.Description("Forgets the named LLM conversation, so the next prompt starts fresh.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"llm", "ai", "conversation", "chat", "clear", "reset", "forget", "memory", "history",
        "context", "new"})
@Node.Type("llm.ClearConversationNode")
public class ClearConversationNode extends BaseNode {

    private final NodeVariable<String> conversation =
            new NodeVariable<>("Conversation ID", String.class, true).required();

    private final NodeVariable<Integer> forgotten = new NodeVariable<>("Forgotten", Integer.class);
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        String name = conversationName();
        if (ctx.wasTriggeredVia(in)) {
            publish(forget(name));
            return;
        }
        // Pulled for data: say whether there is one, having forgotten nothing.
        publish(0, exists(name));
    }

    /**
     * Forgets {@code name} and answers how many exchanges went with it, or -1 when there was no
     * such conversation. A blank name forgets nothing: an unwired field must not be able to erase
     * a conversation somebody else's node is holding. Package-private so a test can exercise it
     * without a live {@code NodeGraph} — the {@code ProcessContext} carrying "did flow arrive here"
     * can only be built by the engine — the same split {@code ClearCollectionNode} makes.
     */
    int forget(String name) {
        if (name.isEmpty()) {
            return -1;
        }
        LlmConversations conversations = LlmConversations.shared();
        int exchanges = exchangesOf(name);
        return conversations.forget(name) ? exchanges : -1;
    }

    /** How many exchanges {@code name} is holding, or 0 when there is no such conversation. */
    private int exchangesOf(String name) {
        return conversation(name).map(LlmConversation::exchanges).orElse(0);
    }

    private boolean exists(String name) {
        return conversation(name).isPresent();
    }

    private Optional<LlmConversation> conversation(String name) {
        return name.isEmpty() ? Optional.empty() : LlmConversations.shared().peek(name);
    }

    /** Publishes the outcome of a clear: -1 means there was nothing under that name. */
    private void publish(int exchangesForgotten) {
        publish(Math.max(0, exchangesForgotten), exchangesForgotten >= 0);
    }

    private void publish(int exchanges, boolean wasThere) {
        forgotten.setValue(exchanges);
        found.setValue(wasThere);
    }

    /** The name to act on, trimmed — a field holding spaces names nothing, as on the prompt node. */
    private String conversationName() {
        String name = conversation.getValue();
        return name == null ? "" : name.trim();
    }

    @Override
    public void configureInputs() {
        addInput(conversation);
    }

    @Override
    public void configureOutputs() {
        addOutput(forgotten);
        addOutput(found);
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
