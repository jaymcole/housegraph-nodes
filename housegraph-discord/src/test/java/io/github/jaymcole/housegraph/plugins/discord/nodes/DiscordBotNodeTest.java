package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
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
}
