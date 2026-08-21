package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Same reasoning as {@code DiscordCommandNodeTest}: the {@code Bot} input is wired, not looked
 * up by name, so wiring/unwiring an edge is what's exercised here. Also covers the part unique
 * to this node — one named flow-out port per configured button id — since that's what a Branch
 * node downstream would key off of. Click delivery itself isn't exercised here since that needs
 * a live JDA connection.
 */
class DiscordButtonClickNodeTest {

    /** A minimal data node that outputs a fixed bot handle — stands in for {@code DiscordBotNode}. */
    private static final class BotSource extends BaseNode {
        private final NodeVariable<DiscordBot> out = new NodeVariable<>("Bot", DiscordBot.class).transientValue();

        BotSource(DiscordBot bot) {
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
        DiscordButtonClickNode click = new DiscordButtonClickNode();
        graph.addNode(source);
        graph.addNode(click);

        graph.registerEdge(new Edge(source, source.out, click, inputNamed(click, "Bot")));

        assertSame(bot, inputNamed(click, "Bot").getValue(),
                "wiring a Discord Bot node's output should capture it on the click node's Bot input");
    }

    @Test
    void removingTheBotEdgeClearsTheBotInput() {
        NodeGraph graph = new NodeGraph();
        BotSource source = new BotSource(new DiscordBot());
        DiscordButtonClickNode click = new DiscordButtonClickNode();
        graph.addNode(source);
        graph.addNode(click);
        Edge edge = new Edge(source, source.out, click, inputNamed(click, "Bot"));
        graph.registerEdge(edge);

        graph.removeEdge(edge);

        assertNull(inputNamed(click, "Bot").getValue(),
                "unwiring the Bot edge should drop the subscription and clear the captured bot");
    }

    @Test
    void noButtonIdsMeansNoFlowOutputs() {
        DiscordButtonClickNode click = new DiscordButtonClickNode();

        assertEquals(List.of(), click.getFlowOutputs().stream().map(p -> p.name).toList());
    }

    @Test
    void loadingButtonIdsGrowsOneNamedFlowOutputPerId() {
        DiscordButtonClickNode click = new DiscordButtonClickNode();

        click.loadState(Map.of("buttonIds", "yes,no"));
        click.reconfigure();

        assertEquals(List.of("yes", "no"), click.getFlowOutputs().stream().map(p -> p.name).toList());
    }

    @Test
    void declaresChannelSenderAndReplyOutputs() {
        DiscordButtonClickNode click = new DiscordButtonClickNode();

        assertEquals(List.of("Channel", "Sender ID", "Sender Name", "Reply"),
                click.getOutputs().stream().map(v -> v.name).toList());
    }
}
