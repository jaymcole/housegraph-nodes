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
