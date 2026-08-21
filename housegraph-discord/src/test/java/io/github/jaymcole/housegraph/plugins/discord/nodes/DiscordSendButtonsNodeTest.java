package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.DiscordReply;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A minimal data node with a DiscordReply input — stands in for a Discord Reply node. */
    private static final class ReplySink extends BaseNode {
        private final NodeVariable<DiscordReply> in = new NodeVariable<>("Reply", DiscordReply.class).transientValue();

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
            addInput(in);
        }

        @Override
        public void configureOutputs() {
        }
    }

    private static NodeVariable<?> inputNamed(BaseNode node, String name) {
        return node.getInputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No input named \"" + name + "\""));
    }

    private static NodeVariable<?> outputNamed(BaseNode node, String name) {
        return node.getOutputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No output named \"" + name + "\""));
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
    @SuppressWarnings("unchecked")
    void processingSelfHealsWhenOnInputEdgeAddedHasNotCaughtUpYet() {
        // Regression guard: onInputEdgeAdded's callback is dispatched asynchronously and, for an
        // edge restored from a loaded graph, isn't reliably caught up by the time this node can
        // be triggered. Simulate that by setting botInput's value directly rather than through
        // registerEdge (Reply's own wiring, a different hook, is registered normally) — process()
        // should still (re)subscribe from the pulled value. Ephemeral-vs-not is the observable
        // signal here specifically because it discriminates: "false" only happens if the
        // subscription/declaration path actually ran, unlike the ephemeral default.
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
        node.loadState(Map.of("buttonLabels", "Yes"));
        node.reconfigure();
        ReplySink sink = new ReplySink();
        graph.addNode(node);
        graph.addNode(sink);
        graph.registerEdge(new Edge(node, outputNamed(node, "Reply"), sink, inputNamed(sink, "Reply")));
        ((NodeVariable<DiscordBot>) inputNamed(node, "Bot")).setValue(bot);

        node.process(ProcessContext.uncancelled());

        assertFalse(bot.isButtonEphemeral("Yes"),
                "process() should self-heal the click subscription/ephemeral declaration from the pulled Bot value");
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

    @Test
    void withNothingWiredToReplyItsButtonsDeferEphemeral() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
        node.loadState(Map.of("buttonLabels", "Yes,No"));
        node.reconfigure();
        graph.addNode(source);
        graph.addNode(node);

        graph.registerEdge(new Edge(source, source.out, node, inputNamed(node, "Bot")));

        assertTrue(bot.isButtonEphemeral("Yes"));
        assertTrue(bot.isButtonEphemeral("No"));
    }

    @Test
    void wiringReplyToAConsumerMakesItsButtonsNonEphemeral() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
        node.loadState(Map.of("buttonLabels", "Yes,No"));
        node.reconfigure();
        ReplySink sink = new ReplySink();
        graph.addNode(source);
        graph.addNode(node);
        graph.addNode(sink);
        graph.registerEdge(new Edge(source, source.out, node, inputNamed(node, "Bot")));

        graph.registerEdge(new Edge(node, outputNamed(node, "Reply"), sink, inputNamed(sink, "Reply")));

        assertFalse(bot.isButtonEphemeral("Yes"));
        assertFalse(bot.isButtonEphemeral("No"));
    }

    @Test
    void removingTheReplyEdgeRevertsItsButtonsToEphemeral() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
        node.loadState(Map.of("buttonLabels", "Yes"));
        node.reconfigure();
        ReplySink sink = new ReplySink();
        graph.addNode(source);
        graph.addNode(node);
        graph.addNode(sink);
        graph.registerEdge(new Edge(source, source.out, node, inputNamed(node, "Bot")));
        Edge replyEdge = new Edge(node, outputNamed(node, "Reply"), sink, inputNamed(sink, "Reply"));
        graph.registerEdge(replyEdge);
        assertFalse(bot.isButtonEphemeral("Yes"), "sanity check: wired, so non-ephemeral");

        graph.removeEdge(replyEdge);

        assertTrue(bot.isButtonEphemeral("Yes"), "unwiring Reply should revert to the ephemeral default");
    }

    @Test
    void unwiringTheBotWithdrawsItsEphemeralDeclarations() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();
        node.loadState(Map.of("buttonLabels", "Yes"));
        node.reconfigure();
        ReplySink sink = new ReplySink();
        graph.addNode(source);
        graph.addNode(node);
        graph.addNode(sink);
        Edge botEdge = new Edge(source, source.out, node, inputNamed(node, "Bot"));
        graph.registerEdge(botEdge);
        graph.registerEdge(new Edge(node, outputNamed(node, "Reply"), sink, inputNamed(sink, "Reply")));
        assertFalse(bot.isButtonEphemeral("Yes"), "sanity check: wired, so non-ephemeral");

        graph.removeEdge(botEdge);

        assertTrue(bot.isButtonEphemeral("Yes"),
                "unwiring the bot should withdraw this node's declarations from it, not leave them stale");
    }
}
