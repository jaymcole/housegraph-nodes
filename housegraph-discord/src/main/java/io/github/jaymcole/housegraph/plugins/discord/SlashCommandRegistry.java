package io.github.jaymcole.housegraph.plugins.discord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where slash-command nodes <em>declare</em> the commands they provide, keyed by the
 * {@link DiscordBot} instance they're wired to, so registration doesn't depend on node
 * load order: a command node declares its command whenever it likes, and the bot reads
 * the full set for itself when it connects (and re-registers). Declaring/withdrawing here
 * does not itself talk to Discord — the bot node syncs the declared set to Discord on
 * Connect.
 * <p>
 * The natural rule that follows: set up your command nodes, then connect the bot;
 * changing a command (name, ephemeral flag, …) afterward means a reconnect to apply it.
 */
public final class SlashCommandRegistry {

    private static final SlashCommandRegistry SHARED = new SlashCommandRegistry();

    /** bot -> (commandName -> spec); identity-keyed since a {@link DiscordBot} has no natural equality. */
    private final Map<DiscordBot, Map<String, SlashCommandSpec>> byBot = new ConcurrentHashMap<>();

    public static SlashCommandRegistry shared() {
        return SHARED;
    }

    public void declare(DiscordBot bot, SlashCommandSpec spec) {
        byBot.computeIfAbsent(bot, key -> new ConcurrentHashMap<>()).put(spec.name(), spec);
    }

    public void withdraw(DiscordBot bot, String command) {
        Map<String, SlashCommandSpec> commands = byBot.get(bot);
        if (commands != null) {
            commands.remove(command);
        }
    }

    /** A snapshot of the specs declared for {@code bot}. */
    public Collection<SlashCommandSpec> commandsFor(DiscordBot bot) {
        List<SlashCommandSpec> specs = new ArrayList<>(byBot.getOrDefault(bot, Map.of()).values());
        specs.sort((a, b) -> a.name().compareTo(b.name()));
        return specs;
    }
}
