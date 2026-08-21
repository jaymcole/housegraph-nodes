package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the command node's {@code Bot} input is a wired port, not a name lookup: wiring
 * (and unwiring) an edge drives {@link DiscordCommandNode#onInputEdgeAdded}/
 * {@link DiscordCommandNode#onInputEdgeRemoved}, which is the only hook available to react to
 * a data edge outside the pull/process cycle — this node has no flow-in to pull it through.
 * Message delivery itself isn't exercised here since that needs a live JDA connection (see
 * {@code DiscordBotNodeTest}'s reasoning for staying off the network).
 */
class DiscordCommandNodeTest {

    /** A minimal data node that outputs a fixed bot handle — stands in for {@code DiscordBotNode}. */
    private static final class BotSource extends BaseNode {
        private final NodeVariable<DiscordBot> out = new NodeVariable<>("Bot", DiscordBot.class).transientValue();
        private final DiscordBot bot;

        BotSource(DiscordBot bot) {
            this.bot = bot;
            out.setValue(bot);
        }

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(out);
        }
    }

    private static NodeVariable<?> inputNamed(BaseNode node, String name) {
        return node.getInputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No input named \"" + name + "\""));
    }

    @Test
    void wiringABotEdgeCapturesItOnTheBotInput() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordCommandNode command = new DiscordCommandNode();
        graph.addNode(source);
        graph.addNode(command);

        graph.registerEdge(new Edge(source, source.out, command, inputNamed(command, "Bot")));

        assertSame(bot, inputNamed(command, "Bot").getValue(),
                "wiring a Discord Bot node's output should capture it on the command node's Bot input");
    }

    @Test
    void removingTheBotEdgeClearsTheBotInput() {
        NodeGraph graph = new NodeGraph();
        BotSource source = new BotSource(new DiscordBot());
        DiscordCommandNode command = new DiscordCommandNode();
        graph.addNode(source);
        graph.addNode(command);
        Edge edge = new Edge(source, source.out, command, inputNamed(command, "Bot"));
        graph.registerEdge(edge);

        graph.removeEdge(edge);

        assertNull(inputNamed(command, "Bot").getValue(),
                "unwiring the Bot edge should drop the subscription and clear the captured bot");
    }

    @Test
    void noBotWiredLeavesTheInputEmpty() {
        DiscordCommandNode command = new DiscordCommandNode();

        assertNull(inputNamed(command, "Bot").getValue());
    }

    @Test
    void declaresOnlyTheBotDataInput() {
        DiscordCommandNode command = new DiscordCommandNode();

        assertEquals(List.of("Bot"), command.getInputs().stream().map(v -> v.name).toList());
    }
}
