package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;

/**
 * Posts a message to a Discord channel when triggered — the action side of the pattern.
 * Wire a Discord Bot node's {@code Bot} output into this node's {@code Bot} input and it
 * sends its {@code Message} to the channel given by its {@code Channel} input. Every input
 * can be typed in place or wired: wire a command node's {@code Channel} output in to reply
 * in the channel the command came from, or type a fixed channel id for a set destination.
 * Control flows through, so you can chain more work after sending.
 * <p>
 * {@code Bot} is captured eagerly via {@link #onInputEdgeAdded}/{@link #onInputEdgeRemoved}
 * into a plain field, the same way every other node in this library that consumes a wired
 * {@code DiscordBot} handle does (see {@code DiscordCommandNode}) — {@code process()} reads
 * that field, not {@code botInput.getValue()}. A {@code DiscordBot} is a live, long-lived
 * resource wired in from a node that isn't part of this node's own flow-triggered pass (it was
 * connected in a separate, already-finished pass), so relying on the normal per-pass data-edge
 * pull to resolve it here is unreliable — it would also re-invoke the Bot node's own
 * {@code process()} as a side effect of resolving the dependency, attempting a fresh reconnect
 * on every trigger.
 */
@Display.Name("Discord Send Message")
@Node.Type("discord.DiscordSendMessageNode")
public class DiscordSendMessageNode extends BaseNode {

    private static final Logger log = Log.get(DiscordSendMessageNode.class);

    private final NodeVariable<DiscordBot> botInput = new NodeVariable<>("Bot", DiscordBot.class).transientValue().required();
    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true).required();
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class, true).required();
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private DiscordBot bot;

    public DiscordSendMessageNode() {
        log.info("Discord Send Message [{}] constructed", System.identityHashCode(this));
    }

    @Override
    public void process(ProcessContext ctx) {
        String text = message.getValue();
        String channelId = channel.getValue();
        if (bot == null) {
            log.warn("Discord Send Message [{}] did nothing: no Bot wired in (botInput.getValue()={})",
                    System.identityHashCode(this), botInput.getValue());
            return;
        }
        if (channelId == null || channelId.isBlank()) {
            log.warn("Discord Send Message did nothing: Channel is empty");
            return;
        }
        if (text == null) {
            log.warn("Discord Send Message did nothing: Message is empty");
            return;
        }
        bot.sendMessage(channelId, text);
    }

    @Override
    public void configureInputs() {
        addInput(botInput);
        addInput(message);
        addInput(channel);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        if (edge.getTargetVariable() == botInput) {
            bot = (DiscordBot) edge.getSourceVariable().getValue();
            botInput.setValue(bot);
            log.info("Discord Send Message [{}] captured Bot from a wired edge (bot={})",
                    System.identityHashCode(this), bot);
        }
    }

    @Override
    protected void onInputEdgeRemoved(Edge edge) {
        if (edge.getTargetVariable() == botInput) {
            log.info("Discord Send Message [{}] Bot edge removed, clearing captured bot", System.identityHashCode(this));
            bot = null;
            botInput.setValue(null);
        }
    }
}
