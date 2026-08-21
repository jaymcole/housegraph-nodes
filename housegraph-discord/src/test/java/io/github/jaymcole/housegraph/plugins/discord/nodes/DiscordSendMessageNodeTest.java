package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code DiscordSendMessageNode}'s {@code Bot} input is a wired live resource, not a plain
 * pull-based data value: it's captured eagerly via {@code onInputEdgeAdded}/
 * {@code onInputEdgeRemoved} into a plain field, the same as every other node in this library
 * that consumes a wired {@code DiscordBot} (see {@code DiscordCommandNodeTest}) — {@code
 * process()} reads that field, not {@code botInput.getValue()}, since the normal per-pass
 * data-edge pull isn't reliable for a resource wired in from outside this node's own
 * flow-triggered pass. Actually sending isn't exercised here since {@code DiscordBot#sendMessage}
 * needs a live JDA connection.
 */
class DiscordSendMessageNodeTest {

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
    void declaresBotMessageAndChannelInputsInOrder() {
        DiscordSendMessageNode node = new DiscordSendMessageNode();

        assertEquals(List.of("Bot", "Message", "Channel"),
                node.getInputs().stream().map(v -> v.name).toList());
    }

    @Test
    void processingWithNothingWiredIsAQuietNoOp() {
        DiscordSendMessageNode node = new DiscordSendMessageNode();

        assertDoesNotThrow(() -> node.process(ProcessContext.uncancelled()),
                "with no bot wired there is nothing to send to");
    }

    @Test
    void wiringABotEdgeCapturesItOnTheBotInput() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSendMessageNode node = new DiscordSendMessageNode();
        graph.addNode(source);
        graph.addNode(node);

        graph.registerEdge(new Edge(source, source.out, node, inputNamed(node, "Bot")));

        assertSame(bot, inputNamed(node, "Bot").getValue(),
                "wiring a Discord Bot node's output should capture it on this node's Bot input");
    }

    @Test
    void removingTheBotEdgeClearsTheBotInput() {
        NodeGraph graph = new NodeGraph();
        BotSource source = new BotSource(new DiscordBot());
        DiscordSendMessageNode node = new DiscordSendMessageNode();
        graph.addNode(source);
        graph.addNode(node);
        Edge edge = new Edge(source, source.out, node, inputNamed(node, "Bot"));
        graph.registerEdge(edge);

        graph.removeEdge(edge);

        assertNull(inputNamed(node, "Bot").getValue(),
                "unwiring the Bot edge should clear the captured bot");
    }
}
