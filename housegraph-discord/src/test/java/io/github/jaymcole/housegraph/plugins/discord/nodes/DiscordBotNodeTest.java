package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Discord bot persists whether it was connected so it can auto-connect on load
 * (see {@code AutoStartable}), and that its {@code Bot} output is populated immediately —
 * other Discord nodes wire to this port, not a resource-registry name, so it must carry a
 * live {@link DiscordBot} handle from construction, independent of Connect ever being pressed.
 * Stays on the headless persistence contract otherwise, since connecting talks to the Discord
 * gateway.
 */
class DiscordBotNodeTest {

    @Test
    void aDisconnectedBotWritesNoRunningFlag() {
        assertFalse(new DiscordBotNode().saveState().containsKey("running"),
                "a bot that isn't connected must not persist a running flag");
    }

    @Test
    void aRunningFlagInSavedStateSchedulesAutoConnect() {
        DiscordBotNode bot = new DiscordBotNode();
        assertFalse(bot.wasConnected(), "a fresh node has no pending auto-connect");

        bot.loadState(Map.of("name", "discord", "running", "true"));

        assertTrue(bot.wasConnected(), "a graph saved while connected reloads with auto-connect pending");
    }

    @Test
    void theBotOutputCarriesALiveHandleBeforeConnecting() {
        DiscordBotNode node = new DiscordBotNode();

        NodeVariable<?> botOutput = node.getOutputs().stream()
                .filter(v -> v.name.equals("Bot"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No output named \"Bot\""));

        assertInstanceOf(DiscordBot.class, botOutput.getValue(),
                "downstream Discord nodes wire to this port, so it must be populated without Connect ever running");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void processingReAssertsTheBotOutputALoadWiped() {
        // Bot is transient, so it saves as null and the loader applies that null back onto the
        // variable, wiping what the constructor seeded — after which every downstream pull read
        // null for the life of the node. Re-asserting it happens before the Connect half, so it
        // holds even for this token-less node whose Connect then fails.
        DiscordBotNode node = new DiscordBotNode();
        NodeVariable botOutput = outputNamed(node, "Bot");
        DiscordBot handle = (DiscordBot) botOutput.getValue();
        botOutput.setValue(null);

        assertThrows(IllegalStateException.class, () -> node.process(ProcessContext.uncancelled()),
                "no token is wired, so connecting still fails — what happens before it is the point");

        assertSame(handle, botOutput.getValue(),
                "a pull of Bot must see this node's handle, not the null a load left behind");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theBotOfAnEdgeResolvesEvenWhenALoadWipedTheOutputValue() {
        // The edge-time half: consumers that capture Bot when the wire appears (Command, Slash
        // Command, Send Buttons) are wired up during a load, before anything re-seeds the output.
        NodeGraph graph = new NodeGraph();
        DiscordBotNode node = new DiscordBotNode();
        BotSink sink = new BotSink();
        NodeVariable botOutput = outputNamed(node, "Bot");
        DiscordBot handle = (DiscordBot) botOutput.getValue();
        botOutput.setValue(null);
        graph.addNode(node);
        graph.addNode(sink);
        Edge edge = new Edge(node, botOutput, sink, sink.in);
        graph.registerEdge(edge);

        assertSame(handle, DiscordBotNode.botFrom(edge),
                "the node's own handle is the fallback when its output variable has been wiped");
    }

    @Test
    void declaresItsDataInputsInDisplayOrder() {
        DiscordBotNode node = new DiscordBotNode();

        assertEquals(List.of("Bot Name", "Token Secret", "Guild ID"),
                node.getInputs().stream().map(v -> v.name).toList());
    }

    @Test
    void declaresConnectAndDisconnectFlowInputs() {
        DiscordBotNode node = new DiscordBotNode();

        assertEquals(List.of("Connect", "Disconnect"),
                node.getFlowInputs().stream().map(p -> p.name).toList());
    }

    private static NodeVariable<?> outputNamed(BaseNode node, String name) {
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

    /** A minimal data node that outputs a fixed string — stands in for a Secret Loader node. */
    private static final class StringSource extends BaseNode {
        private final NodeVariable<String> out = new NodeVariable<>("Value", String.class);
        private final String value;

        StringSource(String value) {
            this.value = value;
            out.setValue(value);
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

    /** A minimal flow source: one unnamed OUT port, fired by {@code execute()} — stands in for a trigger node. */
    private static final class Trigger extends BaseNode {
        private final FlowPort out = new FlowPort("Out", FlowPort.Direction.OUT);

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(out);
        }
    }

    private static NodeVariable<?> inputNamed(BaseNode node, String name) {
        return node.getInputs().stream()
                .filter(v -> v.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No input named \"" + name + "\""));
    }

    /** Wires a fresh {@link Trigger} into {@code targetPort} of {@code bot} and fires it, blocking until the run settles. */
    private static void fire(NodeGraph graph, DiscordBotNode bot, FlowPort targetPort) {
        Trigger trigger = new Trigger();
        graph.addNode(trigger);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), bot, targetPort));
        trigger.execute();
        graph.awaitIdle();
    }

    @Test
    void aWiredTokenSecretIsUsedAsTheTokenDirectlyWithNoLookupOfItsOwn() {
        // Regression test: Token Secret used to be a key resolved via Secrets.get(), a holdover
        // from the pre-port dropdown. Wiring a node that already resolves the secret (e.g.
        // HouseGraph's Secret Loader) then failed with "Pick a token secret first", because the
        // node tried to look the *resolved value* up as if it were itself a stored key.
        NodeGraph graph = new NodeGraph();
        StringSource secretLoader = new StringSource("already-resolved-token");
        DiscordBotNode bot = new DiscordBotNode();
        graph.addNode(secretLoader);
        graph.addNode(bot);
        graph.registerEdge(new Edge(secretLoader, secretLoader.out, bot, inputNamed(bot, "Token Secret")));

        fire(graph, bot, bot.getFlowInputs().get(1)); // Disconnect — resolves inputs without touching the network

        assertEquals("already-resolved-token", bot.resolveToken(),
                "Token Secret must be used as-is, not re-resolved as a key");
    }

    @Test
    void aLegacyTokenKeyMigratesToNullWhenTheKeyIsUnknown() {
        DiscordBotNode bot = new DiscordBotNode();

        bot.loadState(Map.of("token", "some-unregistered-key"));

        assertEquals("some-unregistered-key", bot.resolveToken(),
                "with no matching secret in the store, the legacy key itself is the best fallback");
    }
}
