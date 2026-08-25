package io.github.jaymcole.housegraph.plugins.discord;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.resource.Subscription;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A thin wrapper around a JDA gateway connection — the long-lived resource behind a
 * Discord bot node. JDA keeps the connection alive (heartbeats, reconnects) as long as
 * the instance is held; this class just manages hold/release and adapts JDA's async,
 * multi-threaded world to the simple surface the rest of this library needs:
 * <ul>
 *   <li>{@link #connect} logs in and blocks until the gateway is ready (call it off the
 *       UI thread); {@link #disconnect} shuts it down.</li>
 *   <li>incoming (non-bot) messages are delivered to every {@link #addMessageListener
 *       listener} — one instance is meant to be wired, via the Discord Bot node's output
 *       port, into several command nodes at once, so listeners are a list rather than a
 *       single handler.</li>
 *   <li>slash commands are registered via {@link #syncCommands} and their invocations
 *       delivered to every {@link #addSlashListener listener}, deferred so a slow graph
 *       has time (~15 min) to answer through the {@link DiscordReply} handle.</li>
 *   <li>{@link #sendMessage} posts to a channel by id, optionally with buttons attached;
 *       clicks on those buttons are delivered to every {@link #addButtonListener listener},
 *       deferred the same way as slash commands so a slow graph still gets to answer.</li>
 * </ul>
 * Reading message content needs the privileged <b>MESSAGE_CONTENT</b> intent enabled for
 * the bot in Discord's developer portal; slash commands need no special intent.
 */
public final class DiscordBot {

    private static final Logger log = Log.get(DiscordBot.class);

    private final Object lock = new Object();
    private JDA jda;
    private volatile String guildId;
    /** Command name (lowercase) -> whether its reply is ephemeral; consulted when deferring an interaction. */
    private final Map<String, Boolean> ephemeralByCommand = new ConcurrentHashMap<>();
    /**
     * Button id -> whether a click on it should be deferred ephemerally; consulted when
     * deferring a button interaction. An id absent from this map (e.g. a button this bot didn't
     * send) defaults to ephemeral, the safer choice when nothing declared a preference.
     */
    private final Map<String, Boolean> ephemeralByButton = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<DiscordMessage>> messageListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<DiscordSlashCommand>> slashListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<DiscordButtonClick>> buttonListeners = new CopyOnWriteArrayList<>();

    /**
     * Logs in with {@code token} and blocks until the gateway is ready. Call from a
     * background thread — it waits on the network.
     *
     * @throws InterruptedException if interrupted while awaiting readiness
     * @throws RuntimeException     if the token is invalid or login otherwise fails
     */
    public void connect(String token) throws InterruptedException {
        JDA built = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .addEventListeners(new MessageBridge())
                .build()
                .awaitReady();
        synchronized (lock) {
            this.jda = built;
        }
        log.info("Discord bot connected as \"{}\" ({} guild(s) visible)", built.getSelfUser().getName(), built.getGuilds().size());
    }

    public void disconnect() {
        JDA current;
        synchronized (lock) {
            current = jda;
            jda = null;
        }
        if (current != null) {
            current.shutdownNow();
            log.info("Discord bot disconnected");
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
        }
    }

    /** Adds a listener for incoming (non-bot) messages; call {@link Subscription#cancel()} to stop. Delivered on a JDA thread. */
    public Subscription addMessageListener(Consumer<DiscordMessage> listener) {
        messageListeners.add(listener);
        return () -> messageListeners.remove(listener);
    }

    /** Adds a listener for slash-command invocations (already deferred); call {@link Subscription#cancel()} to stop. Delivered on a JDA thread. */
    public Subscription addSlashListener(Consumer<DiscordSlashCommand> listener) {
        slashListeners.add(listener);
        return () -> slashListeners.remove(listener);
    }

    /** Adds a listener for button clicks (already deferred); call {@link Subscription#cancel()} to stop. Delivered on a JDA thread. */
    public Subscription addButtonListener(Consumer<DiscordButtonClick> listener) {
        buttonListeners.add(listener);
        return () -> buttonListeners.remove(listener);
    }

    /** Declares whether a click on {@code buttonId} should be deferred ephemerally; consulted at defer time. */
    public void setButtonEphemeral(String buttonId, boolean ephemeral) {
        ephemeralByButton.put(buttonId, ephemeral);
    }

    /** Withdraws a previously declared ephemeral preference for {@code buttonId}, reverting it to the default (ephemeral). */
    public void clearButtonEphemeral(String buttonId) {
        ephemeralByButton.remove(buttonId);
    }

    /** Whether a click on {@code buttonId} would currently be deferred ephemerally — the same lookup {@link #setButtonEphemeral} feeds. */
    public boolean isButtonEphemeral(String buttonId) {
        return ephemeralByButton.getOrDefault(buttonId, true);
    }

    /** The guild (server) id to register slash commands to for instant availability; null/blank registers globally (slow to propagate). */
    public void setGuildId(String guildId) {
        this.guildId = guildId == null || guildId.isBlank() ? null : guildId;
    }

    /**
     * Registers exactly {@code specs} as this bot's slash commands, each with one
     * optional text argument, and remembers their ephemeral flags for deferring. Replaces
     * the previous set. Registers to the configured {@link #setGuildId guild} if set
     * (instant), otherwise globally (~1 hour to propagate). A no-op if not connected.
     * <p>
     * A spec with {@link SlashCommandSpec#hiddenByDefault()} set registers with its default
     * member permissions disabled, hiding it from everyone's command picker. Discord only
     * lets a bot control that all-or-nothing default; granting the command back to specific
     * roles is a manual step a server admin does per-guild, in Server Settings ->
     * Integrations — there is no bot-token API left for setting per-role command privileges.
     */
    public void syncCommands(Collection<SlashCommandSpec> specs) {
        JDA current;
        String guild;
        synchronized (lock) {
            current = jda;
            guild = guildId;
        }
        if (current == null) {
            return;
        }
        ephemeralByCommand.clear();
        List<SlashCommandData> data = new ArrayList<>();
        for (SlashCommandSpec spec : specs) {
            String name = spec.name().toLowerCase(Locale.ROOT);
            try {
                SlashCommandData command = Commands.slash(name, spec.description());
                for (CommandOption option : spec.options()) {
                    command.addOption(toJdaType(option.type()), option.name().toLowerCase(Locale.ROOT), option.name(), false);
                }
                if (spec.hiddenByDefault()) {
                    // Hides the command from everyone's picker at registration time. Discord no
                    // longer lets a bot grant it back to specific roles itself — that's a manual
                    // step a server admin does per-guild in Server Settings -> Integrations.
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
        Guild target = guild == null ? null : current.getGuildById(guild);
        if (target != null) {
            target.updateCommands().addCommands(data).queue();
        } else {
            current.updateCommands().addCommands(data).queue();
        }
    }

    /** Posts {@code text} to the message channel with the given id; a no-op if not connected or the channel isn't found. */
    public void sendMessage(String channelId, String text) {
        sendMessage(channelId, text, List.of());
    }

    /**
     * Posts {@code text} to the message channel with the given id, with {@code buttons}
     * attached as a single row (Discord caps a row at 5); a no-op if not connected or the
     * channel isn't found. Clicks are delivered to {@link #addButtonListener listeners} by
     * the button's id.
     */
    public void sendMessage(String channelId, String text, List<DiscordButtonSpec> buttons) {
        JDA current;
        synchronized (lock) {
            current = jda;
        }
        if (current == null) {
            log.warn("Cannot send message to channel \"{}\": bot is not connected", channelId);
            return;
        }
        MessageChannel channel = current.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.warn("Cannot send message to channel \"{}\": no such channel in this bot's cache "
                    + "(check the channel id, and that the bot has been invited to the server and can see the channel)", channelId);
            return;
        }
        MessageCreateAction action = channel.sendMessage(text);
        if (!buttons.isEmpty()) {
            List<Button> jdaButtons = new ArrayList<>();
            for (DiscordButtonSpec button : buttons) {
                jdaButtons.add(Button.primary(button.id(), button.label()));
            }
            action = action.addActionRow(jdaButtons);
        }
        action.queue(
                sent -> log.info("Sent message {} to channel \"{}\"", sent.getId(), channelId),
                failure -> log.error("Discord rejected the message to channel \"{}\": {}", channelId, failure.getMessage()));
    }

    private final class MessageBridge extends ListenerAdapter {
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
            messageListeners.forEach(listener -> listener.accept(message));
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            // Acknowledge within Discord's 3s window; the real answer is sent later
            // through the hook (valid ~15 min), so a slow graph still gets to reply.
            // Ephemeral (invoker-only) is decided here, at defer time.
            boolean ephemeral = ephemeralByCommand.getOrDefault(event.getName(), false);
            event.deferReply(ephemeral).queue();
            InteractionHook hook = event.getHook();
            DiscordReply reply = text -> hook.editOriginal(text).queue();

            Map<String, String> options = new java.util.HashMap<>();
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
            slashListeners.forEach(listener -> listener.accept(slashCommand));
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            // Same defer-then-answer-via-hook treatment as slash commands (~15 min to reply).
            // Ephemeral is decided by whoever declared this button id (see
            // #setButtonEphemeral) — normally a Discord Send Buttons node, deciding based on
            // whether anything is actually wired to its Reply output. An undeclared id (e.g. a
            // button this bot didn't send) defaults to ephemeral, the safer choice.
            event.deferReply(isButtonEphemeral(event.getComponentId())).queue();
            InteractionHook hook = event.getHook();
            DiscordReply reply = text -> hook.editOriginal(text).queue();

            // Disable the clicked message's buttons so it can't be pressed again. A plain
            // message edit, independent of the interaction's own ack/reply above.
            List<Button> disabled = event.getMessage().getButtons().stream().map(Button::asDisabled).toList();
            event.getMessage().editMessageComponents(ActionRow.partitionOf(disabled)).queue();

            DiscordButtonClick click = new DiscordButtonClick(
                    event.getComponentId(),
                    event.getChannel().getId(),
                    event.getUser().getId(),
                    event.getUser().getEffectiveName(),
                    reply);
            buttonListeners.forEach(listener -> listener.accept(click));
        }
    }

    private static OptionType toJdaType(DiscordOptionType type) {
        return switch (type) {
            case TEXT -> OptionType.STRING;
            case INTEGER -> OptionType.INTEGER;
            case BOOLEAN -> OptionType.BOOLEAN;
            case USER -> OptionType.USER;
        };
    }
}
