package io.github.jaymcole.housegraph.plugins.discord;

import java.util.List;

/**
 * How a slash command should be registered and answered: its {@code name},
 * {@code description}, {@code options}, whether its reply is {@code ephemeral}
 * (visible only to the person who ran it), and whether it's {@code hiddenByDefault}
 * from everyone. Ephemeral has to be known when the interaction is <em>deferred</em> —
 * before the graph runs — so it travels with the command's registration rather than
 * being decided at reply time.
 * <p>
 * {@code hiddenByDefault} sets the command's default member permissions to disabled at
 * registration, which hides it from every member's command picker until a server admin
 * grants it back to specific roles — a manual, per-server step in Discord's own
 * Server Settings → Integrations page, since Discord no longer lets a bot set per-role
 * command privileges itself (see {@link io.github.jaymcole.housegraph.plugins.discord.DiscordBot#syncCommands}).
 *
 * @param name            the command name Discord registers it under
 * @param description     the command's description, shown in Discord's command picker
 * @param ephemeral       whether the reply is visible only to the person who ran the command
 * @param hiddenByDefault whether the command is hidden from everyone until a server admin grants it to roles in Discord
 * @param options         the options (arguments) the command declares
 */
public record SlashCommandSpec(String name, String description, boolean ephemeral, boolean hiddenByDefault,
                                List<CommandOption> options) {
}
