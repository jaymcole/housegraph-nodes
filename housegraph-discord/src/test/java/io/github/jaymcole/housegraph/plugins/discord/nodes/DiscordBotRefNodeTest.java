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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the Discord Bot Ref node does what a second Discord Bot node must not: give the graph
 * another place to wire the <em>same</em> bot into. Resolution is by name and repeated on every
 * read, which is what makes it independent of load order — the case that decides whether a loaded
 * graph's command nodes ever subscribe.
 * <p>
 * Names are per-test, since the resource registry these share is process-wide.
 */
class DiscordBotRefNodeTest {

    @Test
    void resolvesTheHandleOfTheBotNodeItNames() {
        NodeGraph graph = new NodeGraph();
        DiscordBotNode bot = named("resolves-bot");
        DiscordBotRefNode ref = refTo("resolves-bot");
        graph.addNode(bot);
        graph.addNode(ref);

        ref.process(ProcessContext.uncancelled());

        assertSame(handleOf(bot), outputNamed(ref, "Bot").getValue(),
                "the reference must hand out the bot node's own handle, not a copy");
    }

    @Test
    void resolvesToNothingWhenNoBotAnswersToTheName() {
        NodeGraph graph = new NodeGraph();
        DiscordBotRefNode ref = refTo("nobody-by-this-name");
        graph.addNode(ref);

        ref.process(ProcessContext.uncancelled());

        assertNull(outputNamed(ref, "Bot").getValue(),
                "a name with no bot behind it resolves to nothing rather than to some other bot");
    }

    @Test
    void aConsumerWiredToTheReferenceCapturesTheBotEvenWhenTheReferenceLoadedFirst() {
        // The load-order case, and the reason resolution can't be cached at activation: every node
        // is built and activated before any edge is wired, in file order — so a reference activated
        // before the bot node it names finds nothing at that moment, and must resolve again when
        // the consumer asks.
        NodeGraph graph = new NodeGraph();
        DiscordBotRefNode ref = refTo("late-bot");
        graph.addNode(ref);
        DiscordBotNode bot = named("late-bot");
        graph.addNode(bot);
        BotSink sink = new BotSink();
        graph.addNode(sink);

        Edge edge = new Edge(ref, outputNamed(ref, "Bot"), sink, sink.in);
        graph.registerEdge(edge);

        assertSame(handleOf(bot), DiscordBotNode.botFrom(edge),
                "a Command node captures the bot when the wire appears, so this is the only chance it gets");
    }

    @Test
    void followsTheBotNodeWhenEitherEndIsRenamed() {
        NodeGraph graph = new NodeGraph();
        DiscordBotNode bot = named("original-name");
        DiscordBotRefNode ref = refTo("original-name");
        graph.addNode(bot);
        graph.addNode(ref);
        assertSame(handleOf(bot), resolved(ref), "sanity check: wired up by name to start with");

        inputNamed(bot, "Bot Name").setValue("renamed");
        // A rename moves the registration on the node's next run. This one has no token wired so
        // its Connect half then fails — publishing happens first, which is the part under test.
        assertThrows(IllegalStateException.class, () -> bot.process(ProcessContext.uncancelled()));

        assertNull(resolved(ref), "the reference still names the old name, which nothing answers to now");

        inputNamed(ref, "Bot Name").setValue("renamed");

        assertSame(handleOf(bot), resolved(ref), "pointing the reference at the new name reconnects it");
    }

    @Test
    void aDeletedBotNodeTakesItsNameWithIt() {
        NodeGraph graph = new NodeGraph();
        DiscordBotNode bot = named("deleted-bot");
        DiscordBotRefNode ref = refTo("deleted-bot");
        graph.addNode(bot);
        graph.addNode(ref);
        assertSame(handleOf(bot), resolved(ref));

        graph.removeNode(bot);

        assertNull(resolved(ref), "a reference to a bot that is gone must resolve to nothing, not to a stale handle");
    }

    @Test
    void declaresOneNameInputAndOneBotOutputAndNoFlowPorts() {
        DiscordBotRefNode ref = new DiscordBotRefNode();

        assertEquals(List.of("Bot Name"), ref.getInputs().stream().map(v -> v.name).toList());
        assertEquals(List.of("Bot"), ref.getOutputs().stream().map(v -> v.name).toList());
        assertEquals(List.of(), ref.getFlowInputs().stream().map(p -> p.name).toList(),
                "it owns no connection, so there is no lifecycle to drive");
        assertEquals(List.of(), ref.getFlowOutputs().stream().map(p -> p.name).toList());
    }

    private static DiscordBotNode named(String name) {
        DiscordBotNode node = new DiscordBotNode();
        inputNamed(node, "Bot Name").setValue(name);
        return node;
    }

    private static DiscordBotRefNode refTo(String name) {
        DiscordBotRefNode node = new DiscordBotRefNode();
        inputNamed(node, "Bot Name").setValue(name);
        return node;
    }

    private static DiscordBot handleOf(DiscordBotNode node) {
        return (DiscordBot) outputNamed(node, "Bot").getValue();
    }

    private static DiscordBot resolved(DiscordBotRefNode ref) {
        return ref.resolve();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NodeVariable inputNamed(BaseNode node, String name) {
        return node.getInputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No input named \"" + name + "\""));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NodeVariable outputNamed(BaseNode node, String name) {
        return node.getOutputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No output named \"" + name + "\""));
    }

    /** A minimal data node with a Bot input — stands in for any node wired to the bot. */
    private static final class BotSink extends BaseNode {
        private final NodeVariable<DiscordBot> in = new NodeVariable<>("Bot", DiscordBot.class).transientValue();

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
}
