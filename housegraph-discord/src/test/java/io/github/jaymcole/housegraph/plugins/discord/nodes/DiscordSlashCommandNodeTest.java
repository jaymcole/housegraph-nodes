package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.SlashCommandRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the slash command node's {@code Bot} input is a wired port: wiring an edge both
 * captures the bot on the input (same mechanism as {@link DiscordCommandNodeTest}) and
 * declares the command into {@link SlashCommandRegistry} against that bot instance, keyed by
 * identity rather than by name (see {@link SlashCommandRegistry}'s javadoc for why).
 */
class DiscordSlashCommandNodeTest {

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
    void wiringABotEdgeCapturesItAndDeclaresTheCommand() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSlashCommandNode slash = new DiscordSlashCommandNode();
        slash.loadState(java.util.Map.of("command", "deploy"));
        graph.addNode(source);
        graph.addNode(slash);

        graph.registerEdge(new Edge(source, source.out, slash, inputNamed(slash, "Bot")));

        assertSame(bot, inputNamed(slash, "Bot").getValue(),
                "wiring a Discord Bot node's output should capture it on the slash command node's Bot input");
        assertTrue(SlashCommandRegistry.shared().commandsFor(bot).stream().anyMatch(spec -> spec.name().equals("deploy")),
                "wiring the bot should declare the command against that bot instance");
    }

    @Test
    void removingTheBotEdgeWithdrawsTheDeclaration() {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        DiscordSlashCommandNode slash = new DiscordSlashCommandNode();
        slash.loadState(java.util.Map.of("command", "undeploy"));
        graph.addNode(source);
        graph.addNode(slash);
        Edge edge = new Edge(source, source.out, slash, inputNamed(slash, "Bot"));
        graph.registerEdge(edge);

        graph.removeEdge(edge);

        assertNull(inputNamed(slash, "Bot").getValue());
        assertTrue(SlashCommandRegistry.shared().commandsFor(bot).isEmpty(),
                "unwiring the bot should withdraw the declared command");
    }
}
