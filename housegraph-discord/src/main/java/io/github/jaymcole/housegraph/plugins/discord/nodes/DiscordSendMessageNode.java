package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
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
 * {@code Bot} is read via the normal {@code botInput.getValue()} pull, not captured eagerly
 * through {@code onInputEdgeAdded} — this node only needs the current value at the moment it
 * sends, and that hook's callback is dispatched asynchronously (and, for an edge restored from
 * a loaded graph rather than freshly drawn, isn't reliably caught up by the time a node can be
 * triggered), so relying on it left this node seeing a stale/absent Bot right after a graph
 * load. The plain pull is re-evaluated fresh on every {@code process()} call regardless. This is
 * safe now that {@code DiscordBotNode#connectBot} is idempotent — resolving Bot as a data
 * dependency no longer forces a reconnect as a side effect.
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

    @Override
    public void process(ProcessContext ctx) {
        DiscordBot bot = botInput.getValue();
        String text = message.getValue();
        String channelId = channel.getValue();
        if (bot == null) {
            log.warn("Discord Send Message did nothing: no Bot wired in");
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
}
