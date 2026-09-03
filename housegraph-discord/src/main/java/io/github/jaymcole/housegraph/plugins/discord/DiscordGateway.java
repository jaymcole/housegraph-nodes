package io.github.jaymcole.housegraph.plugins.discord;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One Discord gateway session, shared by every {@link DiscordBot} in this process that carries the
 * same token.
 *
 * <h2>Why this exists</h2>
 * Discord allows a bot token exactly one unsharded gateway session. A second login on the same
 * token doesn't run alongside the first — Discord replaces the old session, so the first
 * connection is dropped. Two Discord Bot nodes on one token therefore don't get two bots; they get
 * one working bot and one that keeps being kicked (and, while both are retrying, neither is
 * reliably up). On top of that, each session would separately receive every event, race for the
 * single-use interaction acknowledgment, and overwrite the application's command list on sync.
 * <p>
 * So the session is the thing that gets deduplicated, not the {@link DiscordBot} handle: bots join
 * a token's session rather than each opening their own. Every joined bot keeps its own identity,
 * its own listeners, and its own declared commands — which is what lets several nodes (in one
 * graph, or in several graphs loaded into one app) each drive their own wiring off a single
 * connection.
 *
 * <h2>What the session owns</h2>
 * Everything that must happen once per connection rather than once per node: the {@link JDA}
 * instance, the single event bridge (so an interaction is deferred exactly once, then handed to
 * every joined bot), the {@link #syncCommands() union} of all joined bots' slash commands, and the
 * ephemeral flags consulted at defer time.
 *
 * <h2>Scope: this process only</h2>
 * This dedupes within one JVM, which is one graph <em>file</em>: every Discord Bot node in a file,
 * however disjoint the clusters they sit in, shares its session. HouseGraph's daemon runs
 * <em>one JVM per file</em>, so two files that both carry a Discord Bot node on the same token are
 * two processes, and nothing here can see across them — that case still ends with one of the two
 * sessions holding the connection and the other going quiet.
 */
final class DiscordGateway {

    private static final Logger log = Log.get(DiscordGateway.class);

    /** token -> its live session; an entry exists exactly while at least one bot has joined it. */
    private static final Map<String, DiscordGateway> BY_TOKEN = new HashMap<>();

    /**
     * How a session logs in. A seam, so tests can exercise joining, sharing and teardown without
     * talking to Discord — everything about who shares a session is decided here, and none of it
     * should need a network to test.
     */
    @FunctionalInterface
    interface Login {
        /**
         * @param token    the bot token to log in with
         * @param listener the session's event bridge, to be registered on the connection
         * @return the connected JDA instance, or null in tests standing in for one
         */
        JDA logIn(String token, Object listener) throws InterruptedException;
    }

    private final String token;
    /** The bots sharing this session, in join order — first joiner wins any per-name conflict. */
    private final CopyOnWriteArrayList<DiscordBot> members = new CopyOnWriteArrayList<>();
    /** Command name (lowercase) -> whether its reply is ephemeral; rebuilt by {@link #syncCommands()}. */
    private final Map<String, Boolean> ephemeralByCommand = new ConcurrentHashMap<>();
    private volatile JDA jda;

    private DiscordGateway(String token) {
        this.token = token;
    }

    /**
     * Joins {@code bot} to this process's session for {@code token}, logging in first if this is
     * the first bot to ask for it.
     *
     * @throws InterruptedException if interrupted while awaiting readiness
     * @throws RuntimeException     if the token is invalid or login otherwise fails
     */
    static DiscordGateway join(String token, DiscordBot bot) throws InterruptedException {
        return join(token, bot, DiscordGateway::logIn);
    }

    /**
     * The seam behind {@link #join(String, DiscordBot)}. Serialized across every token: a login
     * takes seconds and this blocks the others meanwhile, which at the scale of a graph's worth of
     * bot nodes is a fair price for the guarantee that two bots can never both decide they are the
     * first for a token and open two sessions.
     */
    static DiscordGateway join(String token, DiscordBot bot, Login login) throws InterruptedException {
        synchronized (BY_TOKEN) {
            DiscordGateway gateway = BY_TOKEN.get(token);
            if (gateway == null) {
                // Not registered until the login succeeds: a failed attempt must leave nothing
                // behind for the next bot to join and believe it is connected.
                gateway = new DiscordGateway(token);
                gateway.jda = gateway.logIn(login);
                BY_TOKEN.put(token, gateway);
            } else {
                log.info("Sharing this process's existing Discord session ({} node(s) already on it)",
                        gateway.members.size());
            }
            // addIfAbsent, and the bot is told which session it is on from in here: the map lock
            // is the one place that knows the answer, so a bot can't end up joined twice or
            // pointing at a session it isn't a member of.
            gateway.members.addIfAbsent(bot);
            bot.joined(gateway);
            return gateway;
        }
    }

    /**
     * Drops {@code bot} from this session, shutting the connection down once the last one leaves.
     * Leaving does not re-sync the remaining bots' commands: what is registered with Discord stays
     * as it was until something syncs again, in keeping with the library's existing rule that a
     * command change takes effect on the next connect.
     */
    void leave(DiscordBot bot) {
        JDA closing;
        synchronized (BY_TOKEN) {
            members.remove(bot);
            bot.left(this);
            if (!members.isEmpty()) {
                return;
            }
            BY_TOKEN.remove(token, this);
            closing = jda;
            jda = null;
        }
        if (closing != null) {
            closing.shutdownNow();
            log.info("Discord bot disconnected");
        }
    }

    boolean isConnected() {
        JDA current = jda;
        return current != null && current.getStatus() == JDA.Status.CONNECTED;
    }

    /** The live connection, or null when this session isn't (or is no longer) connected. */
    JDA jda() {
        return jda;
    }

    /**
     * Registers the union of every joined bot's declared commands, so one bot syncing can no
     * longer wipe another's: Discord's command list belongs to the application, not to the node
     * that happened to sync last. Commands are grouped by the guild id each bot is configured
     * with, since registering to a guild replaces only that guild's list; bots with no guild id —
     * and any whose guild this bot can't see — are registered globally. A name declared by two
     * bots is registered once, from the bot that joined first. A no-op if not connected.
     */
    void syncCommands() {
        JDA current = jda;
        if (current == null) {
            return;
        }
        ephemeralByCommand.clear();
        Map<String, List<SlashCommandSpec>> byGuild = commandGroups();

        boolean syncGlobal = byGuild.containsKey(null);
        List<SlashCommandSpec> global = byGuild.remove(null);
        if (global == null) {
            global = new ArrayList<>();
        }
        for (Map.Entry<String, List<SlashCommandSpec>> entry : byGuild.entrySet()) {
            Guild target = current.getGuildById(entry.getKey());
            if (target == null) {
                // Same fallback as before the split: an unresolvable guild id registers globally
                // rather than dropping the commands on the floor.
                syncGlobal = true;
                for (SlashCommandSpec spec : entry.getValue()) {
                    addUnlessNamed(global, spec);
                }
                continue;
            }
            target.updateCommands().addCommands(toCommandData(entry.getValue())).queue();
        }
        if (syncGlobal) {
            current.updateCommands().addCommands(toCommandData(global)).queue();
        }
    }

    /**
     * What every joined bot has declared, grouped by the guild id it registers to — a null key
     * being the global group. Commands are collected in join order and a name already taken is
     * dropped, so the union is stable however many times it is rebuilt.
     */
    Map<String, List<SlashCommandSpec>> commandGroups() {
        Map<String, List<SlashCommandSpec>> byGuild = new LinkedHashMap<>();
        for (DiscordBot member : members) {
            List<SlashCommandSpec> group = byGuild.computeIfAbsent(member.guildId(), key -> new ArrayList<>());
            for (SlashCommandSpec spec : member.declaredCommands()) {
                addUnlessNamed(group, spec);
            }
        }
        return byGuild;
    }

    /**
     * Whether a click on {@code buttonId} is deferred ephemerally: the preference of the first
     * joined bot that declared one, or the default (ephemeral) when none did — the safer choice
     * for a button this session didn't send.
     */
    boolean isButtonEphemeral(String buttonId) {
        for (DiscordBot member : members) {
            Boolean preference = member.buttonPreference(buttonId);
            if (preference != null) {
                return preference;
            }
        }
        return true;
    }

    /**
     * Whether a click on {@code buttonId} disables the clicked message's buttons: the preference of
     * the first joined bot that declared one, or the default (disable) when none did — what a click
     * did unconditionally before the preference existed.
     */
    boolean isButtonDisableOnClick(String buttonId) {
        for (DiscordBot member : members) {
            Boolean preference = member.buttonDisableOnClickPreference(buttonId);
            if (preference != null) {
                return preference;
            }
        }
        return true;
    }

    private static void addUnlessNamed(List<SlashCommandSpec> specs, SlashCommandSpec candidate) {
        for (SlashCommandSpec existing : specs) {
            if (existing.name().equalsIgnoreCase(candidate.name())) {
                log.warn("Two Discord Bot nodes on one token both declare /{}; keeping the first",
                        candidate.name());
                return;
            }
        }
        specs.add(candidate);
    }

    /**
     * Turns declared specs into JDA command data, recording each one's ephemeral flag for
     * {@link Bridge} to consult at defer time. A spec with
     * {@link SlashCommandSpec#hiddenByDefault()} set registers with its default member permissions
     * disabled, hiding it from everyone's command picker; Discord only lets a bot control that
     * all-or-nothing default, so granting it back to specific roles is a manual step a server
     * admin does per-guild in Server Settings -&gt; Integrations.
     */
    private List<SlashCommandData> toCommandData(List<SlashCommandSpec> specs) {
        List<SlashCommandData> data = new ArrayList<>();
        for (SlashCommandSpec spec : specs) {
            String name = spec.name().toLowerCase(Locale.ROOT);
            try {
                SlashCommandData command = Commands.slash(name, spec.description());
                for (CommandOption option : spec.options()) {
                    command.addOption(toJdaType(option.type()), option.name().toLowerCase(Locale.ROOT), option.name(), false);
                }
                if (spec.hiddenByDefault()) {
                    command.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
                }
                data.add(command);
                ephemeralByCommand.put(name, spec.ephemeral());
            } catch (IllegalArgumentException e) {
                // Discord requires lowercase names of letters/digits/-/_ ; skip a bad one
                // rather than failing registration of every command.
                log.warn("Skipping invalid slash command '{}': {}", name, e.getMessage());
            }
        }
        return data;
    }

    private JDA logIn(Login login) throws InterruptedException {
        return login.logIn(token, new Bridge());
    }

    private static JDA logIn(String token, Object listener) throws InterruptedException {
        JDA built = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .addEventListeners(listener)
                .build()
                .awaitReady();
        log.info("Discord bot connected as \"{}\" ({} guild(s) visible)",
                built.getSelfUser().getName(), built.getGuilds().size());
        return built;
    }

    private static OptionType toJdaType(DiscordOptionType type) {
        return switch (type) {
            case TEXT -> OptionType.STRING;
            case INTEGER -> OptionType.INTEGER;
            case BOOLEAN -> OptionType.BOOLEAN;
            case USER -> OptionType.USER;
        };
    }

    /**
     * The session's single JDA listener: adapts each gateway event once and hands the result to
     * every joined bot. One listener per session rather than one per bot is the point — an
     * interaction may only be acknowledged once, so deferring has to happen here, before the fan
     * out, not in each bot.
     */
    private final class Bridge extends ListenerAdapter {

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) {
                return; // ignore our own and other bots' messages
            }
            DiscordMessage message = new DiscordMessage(
                    event.getMessage().getContentDisplay(),
                    event.getChannel().getId(),
                    event.getAuthor().getId(),
                    event.getAuthor().getEffectiveName());
            members.forEach(member -> member.deliverMessage(message));
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            // Acknowledge within Discord's 3s window; the real answer is sent later
            // through the hook (valid ~15 min), so a slow graph still gets to reply.
            // Ephemeral (invoker-only) is decided here, at defer time.
            boolean ephemeral = ephemeralByCommand.getOrDefault(event.getName(), false);
            event.deferReply(ephemeral).queue();
            InteractionHook hook = event.getHook();
            DiscordReply reply = (text, attachments) -> (attachments.isEmpty()
                    ? hook.editOriginal(text)
                    : hook.editOriginal(text).setFiles(DiscordUploads.open(attachments))).queue();

            Map<String, String> options = new HashMap<>();
            for (OptionMapping option : event.getOptions()) {
                options.put(option.getName(), option.getAsString());
            }
            DiscordSlashCommand slashCommand = new DiscordSlashCommand(
                    event.getName(),
                    options,
                    event.getChannel().getId(),
                    event.getUser().getId(),
                    event.getUser().getEffectiveName(),
                    reply);
            members.forEach(member -> member.deliverSlashCommand(slashCommand));
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            // Same defer-then-answer-via-hook treatment as slash commands (~15 min to reply).
            // Ephemeral is decided by whichever joined bot declared this button id (see
            // DiscordBot#setButtonEphemeral) — normally a Discord Send Buttons node, deciding based
            // on whether anything is actually wired to its Reply output. An undeclared id (e.g. a
            // button this session didn't send) defaults to ephemeral, the safer choice.
            event.deferReply(isButtonEphemeral(event.getComponentId())).queue();
            InteractionHook hook = event.getHook();
            DiscordReply reply = (text, attachments) -> (attachments.isEmpty()
                    ? hook.editOriginal(text)
                    : hook.editOriginal(text).setFiles(DiscordUploads.open(attachments))).queue();

            // Disable the clicked message's buttons so it can't be pressed again — unless whichever
            // joined bot declared this button id opted out (see DiscordBot#setButtonDisableOnClick),
            // as a node enforcing its own per-person click budget does: the buttons have to stay
            // live for everyone else. A plain message edit, independent of the interaction's own
            // ack/reply above.
            if (isButtonDisableOnClick(event.getComponentId())) {
                List<Button> disabled = event.getMessage().getButtons().stream().map(Button::asDisabled).toList();
                event.getMessage().editMessageComponents(ActionRow.partitionOf(disabled)).queue();
            }

            DiscordButtonClick click = new DiscordButtonClick(
                    event.getComponentId(),
                    event.getChannel().getId(),
                    event.getUser().getId(),
                    event.getUser().getEffectiveName(),
                    reply);
            members.forEach(member -> member.deliverButtonClick(click));
        }
    }
}
