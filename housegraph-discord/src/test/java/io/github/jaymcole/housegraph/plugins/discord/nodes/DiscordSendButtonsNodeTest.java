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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This node is both an action (send, via its flow-in) and an event source (a button click, via
 * a wired bot's listener). Unlike {@code DailyTriggerNode}'s Start/Stop, a click can't be told
 * apart from a plain flow-in trigger via {@code ProcessContext#wasTriggeredVia} — both a real
 * flow edge arrival at {@code process()} and a manual host-UI test-run produce a populated-or-
 * empty {@code triggeredVia()} depending only on how the node was reached, and this node's own
 * click re-entry would look identical to the latter. So {@code process()} unconditionally means
 * "send" and a click never reaches it at all, instead firing its own branch directly via
 * {@code runFlowBranchToCompletion}. Actually sending/receiving isn't exercised here since that
 * needs a live JDA connection (see {@code DiscordSendMessageNodeTest}'s reasoning); the
 * {@code Bot}-wiring and flow-port-shape behaviors, which don't, are.
 */
class DiscordSendButtonsNodeTest {

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
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();

        assertEquals(List.of("Bot", "Message", "Channel"),
                node.getInputs().stream().map(v -> v.name).toList());
    }

    @Test
    void declaresSenderAndReplyOutputs() {
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();

        assertEquals(List.of("Sender ID", "Sender Name", "Reply"),
                node.getOutputs().stream().map(v -> v.name).toList());
    }

    @Test
    void withNoButtonsConfiguredOnlyThePlainFlowOutputExists() {
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();

        assertEquals(List.of(""), node.getFlowOutputs().stream().map(p -> p.name).toList());
    }

    @Test
    void loadingButtonLabelsGrowsOneNamedFlowOutputPerLabelAfterThePlainOne() {
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();

        node.loadState(Map.of("buttonLabels", "Yes,No"));
        node.reconfigure();

        assertEquals(List.of("", "Yes", "No"), node.getFlowOutputs().stream().map(p -> p.name).toList());
    }

    @Test
    void processingWithNothingWiredIsAQuietNoOp() {
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();

        assertDoesNotThrow(() -> node.process(ProcessContext.uncancelled()),
                "with no bot wired there is nothing to send to");
    }

    @Test
    @SuppressWarnings("unchecked")
    void processingWithAnEmptyTriggeredViaStillAttemptsToSend() {
        // Regression guard: process() must not gate sending on ctx.wasTriggeredVia(in) — a
        // manual host-UI test-run of this node also produces an empty triggeredVia(), same as
        // a real flow-in edge arrival looks from inside process() (see the class doc).
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot(); // unconnected: sendMessage() on it is a safe no-op
        BotSource source = new BotSource(bot);
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
        graph.addNode(source);
        graph.addNode(node);
        graph.registerEdge(new Edge(source, source.out, node, inputNamed(node, "Bot")));
        ((NodeVariable<String>) inputNamed(node, "Message")).setValue("hello");
        ((NodeVariable<String>) inputNamed(node, "Channel")).setValue("123");

        assertDoesNotThrow(() -> node.process(ProcessContext.uncancelled()));
    }

    @Test
    void wiringABotEdgeCapturesItOnTheBotInput() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
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
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
        graph.addNode(source);
        graph.addNode(node);
        Edge edge = new Edge(source, source.out, node, inputNamed(node, "Bot"));
        graph.registerEdge(edge);

        graph.removeEdge(edge);

        assertNull(inputNamed(node, "Bot").getValue(),
                "unwiring the Bot edge should drop the click subscription and clear the captured bot");
    }
}
