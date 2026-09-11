package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.ChoiceMode;
import io.github.jaymcole.housegraph.plugins.discord.CommandOption;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.DiscordOptionType;
import io.github.jaymcole.housegraph.plugins.discord.SlashCommandRegistry;
import io.github.jaymcole.housegraph.plugins.discord.SlashCommandSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the slash command node's {@code Bot} input is a wired port: wiring an edge both
 * captures the bot on the input (same mechanism as {@link DiscordCommandNodeTest}) and
 * declares the command into {@link SlashCommandRegistry} against that bot instance, keyed by
 * identity rather than by name (see {@link SlashCommandRegistry}'s javadoc for why).
 * <p>
 * Also that what a saved graph holds survives a round trip — including an option's values and
 * whether they restrict or merely suggest, which a graph saved before those existed doesn't
 * carry at all — and that the round trip holds in both directions across versions of this
 * library: a graph saved today has to load on a build that predates the JSON option format,
 * because one that couldn't read it split it on commas into option names Discord refuses, and
 * refusing an option name used to take every slash command in the graph down with it.
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
    void anOptionsValuesAndWhatTheyMeanSurviveASaveAndReload() {
        DiscordSlashCommandNode saved = new DiscordSlashCommandNode();
        saved.loadState(Map.of("command", "deploy", "options",
                "[{\"name\":\"env\",\"type\":\"text\",\"values\":[\"prod\",\"staging, eu\"],\"mode\":\"restricted\"},"
                        + "{\"name\":\"host\",\"type\":\"text\",\"values\":[\"web-1\",\"web-2\"],\"mode\":\"suggested\"},"
                        + "{\"name\":\"count\",\"type\":\"integer\"}]"));

        DiscordSlashCommandNode reloaded = new DiscordSlashCommandNode();
        reloaded.loadState(saved.saveState());

        assertEquals(List.of(
                        new CommandOption("env", DiscordOptionType.TEXT, List.of("prod", "staging, eu"), ChoiceMode.RESTRICTED),
                        new CommandOption("host", DiscordOptionType.TEXT, List.of("web-1", "web-2"), ChoiceMode.SUGGESTED),
                        new CommandOption("count", DiscordOptionType.INTEGER)),
                declaredOptions(reloaded),
                "values are free text, and the comma-separated form this used to save would have "
                        + "split one carrying a comma into two");
    }

    @Test
    void aGraphSavedBeforeOptionsCouldOfferValuesStillLoads() {
        DiscordSlashCommandNode node = new DiscordSlashCommandNode();

        node.loadState(Map.of("command", "deploy", "options", "env, count:integer"));

        assertEquals(List.of(new CommandOption("env", DiscordOptionType.TEXT),
                        new CommandOption("count", DiscordOptionType.INTEGER)),
                declaredOptions(node),
                "the old comma-separated text is still what an existing saved graph holds");
    }

    @Test
    void aGraphSavedTodayStillLoadsOnABuildThatPredatesTheJsonFormat() {
        DiscordSlashCommandNode saved = new DiscordSlashCommandNode();
        saved.loadState(Map.of("command", "deploy", "optionsJson",
                "[{\"name\":\"env\",\"type\":\"text\",\"values\":[\"prod\",\"staging, eu\"],\"mode\":\"restricted\"},"
                        + "{\"name\":\"count\",\"type\":\"integer\"}]"));

        // What an older build reads is the older key, and it must find the old format there: it
        // splits that value on commas, so JSON would come back as options named `[{"name"`.
        String legacy = saved.saveState().get("options");
        DiscordSlashCommandNode older = new DiscordSlashCommandNode();
        older.loadState(Map.of("command", "deploy", "options", legacy));

        assertEquals("env:text, count:integer", legacy);
        assertEquals(List.of(new CommandOption("env", DiscordOptionType.TEXT),
                        new CommandOption("count", DiscordOptionType.INTEGER)),
                declaredOptions(older),
                "an older build loses an option's values, which are newer than the format it "
                        + "reads - but it must still get the options themselves");
    }

    @Test
    void optionsAnOlderBuildMangledLoadAsNoneRatherThanAsNamesDiscordRefuses() {
        DiscordSlashCommandNode node = new DiscordSlashCommandNode();

        // What a build predating the JSON format wrote back after splitting it on commas: names
        // Discord refuses, which used to cost the node every one of its commands.
        node.loadState(Map.of("command", "ask", "optionsJson",
                "[{\"name\":\"[{\\\"name\\\"\",\"type\":\"text\"},{\"name\":\"prompt\",\"type\":\"text\"}]"));

        assertEquals(List.of(new CommandOption("prompt", DiscordOptionType.TEXT)), declaredOptions(node),
                "an unusable name is dropped on its own; the options around it still load");
    }

    @Test
    void unreadableSavedOptionsCostTheirNodeRatherThanTheGraph() {
        DiscordSlashCommandNode node = new DiscordSlashCommandNode();

        node.loadState(Map.of("command", "deploy", "optionsJson", "[{\"name\": truncated"));

        assertEquals(List.of(), declaredOptions(node));
    }

    /** The options {@code node} would register, read back from the registry by wiring a bot in. */
    private static List<CommandOption> declaredOptions(DiscordSlashCommandNode node) {
        NodeGraph graph = new NodeGraph();
        DiscordBot bot = new DiscordBot();
        BotSource source = new BotSource(bot);
        graph.addNode(source);
        graph.addNode(node);
        graph.registerEdge(new Edge(source, source.out, node, inputNamed(node, "Bot")));
        return SlashCommandRegistry.shared().commandsFor(bot).stream()
                .findFirst()
                .map(SlashCommandSpec::options)
                .orElseThrow(() -> new AssertionError("nothing was declared for this bot"));
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
