package io.github.jaymcole.housegraph.plugins.discord;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.resource.Subscription;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One Discord bot node's handle on a Discord connection — the long-lived resource behind a Discord
 * Bot node. It adapts JDA's async, multi-threaded world to the simple surface the rest of this
 * library needs:
 * <ul>
 *   <li>{@link #connect} logs in and blocks until the gateway is ready (call it off the
 *       UI thread); {@link #disconnect} releases it.</li>
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
 * <p>
 * <b>The connection underneath is shared, this handle is not.</b> A token gets one gateway session
 * per process — see {@link DiscordGateway} for why Discord leaves no choice — and connecting joins
 * that session rather than opening a second one. What stays private to this instance is everything
 * a node wires up for itself: its listeners, the commands it declares, its button preferences. So
 * two Discord Bot nodes on one token both work, each driving its own graph, off one connection;
 * and because a node's handle is never swapped for someone else's, everything captured from it
 * (see {@code DiscordBotNode.botFrom}) stays valid across a connect.
 */
public final class DiscordBot {

    private static final Logger log = Log.get(DiscordBot.class);

    private final Object lock = new Object();
    /** The session this handle has joined, or null while disconnected. */
    private DiscordGateway gateway;
    private volatile String guildId;
    /**
     * Button id -> whether a click on it should be deferred ephemerally, as declared through this
     * handle. Consulted, along with every other handle on the same session, when deferring a
     * button interaction. An id no handle has declared defaults to ephemeral, the safer choice
     * when nothing declared a preference.
     */
    private final Map<String, Boolean> ephemeralByButton = new ConcurrentHashMap<>();
    /** What {@link #syncCommands} was last asked to register for this handle; unioned per session. */
    private volatile List<SlashCommandSpec> declaredCommands = List.of();
    private final CopyOnWriteArrayList<Consumer<DiscordMessage>> messageListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<DiscordSlashCommand>> slashListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<DiscordButtonClick>> buttonListeners = new CopyOnWriteArrayList<>();

    /**
     * Joins this process's gateway session for {@code token}, logging in and blocking until the
     * gateway is ready if this is the first handle to ask for it. Call from a background thread —
     * it waits on the network.
     * <p>
     * A no-op if this handle has already joined a session, even one that is momentarily down: JDA
     * owns reconnection within a session, and a second login here would strand the first. Forcing
     * a fresh login means {@link #disconnect()} first.
     *
     * @throws InterruptedException if interrupted while awaiting readiness
     * @throws RuntimeException     if the token is invalid or login otherwise fails
     */
    public void connect(String token) throws InterruptedException {
        if (session() != null) {
            return;
        }
        // The session sets this handle's own reference as it admits it (see #joined).
        DiscordGateway.join(token, this);
    }

    /**
     * Releases this handle's claim on the connection. The underlying session is only shut down
     * once every handle sharing it has disconnected, so this is safe to call whether this node
     * opened the connection or joined one someone else opened.
     */
    public void disconnect() {
        DiscordGateway current = session();
        if (current != null) {
            current.leave(this);
        }
    }

    public boolean isConnected() {
        DiscordGateway current = session();
        return current != null && current.isConnected();
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

    /**
     * Whether a click on {@code buttonId} would currently be deferred ephemerally — the same
     * lookup {@link #setButtonEphemeral} feeds. While connected this is the session's answer,
     * which takes every handle sharing it into account, since that is what actually happens at
     * defer time.
     */
    public boolean isButtonEphemeral(String buttonId) {
        DiscordGateway current = session();
        if (current != null) {
            return current.isButtonEphemeral(buttonId);
        }
        Boolean preference = ephemeralByButton.get(buttonId);
        return preference == null || preference;
    }

    /** The guild (server) id to register slash commands to for instant availability; null/blank registers globally (slow to propagate). */
    public void setGuildId(String guildId) {
        this.guildId = guildId == null || guildId.isBlank() ? null : guildId;
    }

    /**
     * Declares exactly {@code specs} as this handle's slash commands, each with one optional text
     * argument, and registers them with Discord. Replaces this handle's previous set. Registers to
     * the configured {@link #setGuildId guild} if set (instant), otherwise globally (~1 hour to
     * propagate). A no-op if not connected.
     * <p>
     * What actually reaches Discord is the union across every handle sharing this connection, not
     * this handle's set alone: the command list belongs to the Discord application, and a bare
     * overwrite would mean whichever Discord Bot node synced last silently wiped the others'
     * commands. See {@link DiscordGateway#syncCommands()}.
     */
    public void syncCommands(Collection<SlashCommandSpec> specs) {
        declaredCommands = List.copyOf(specs);
        DiscordGateway current = session();
        if (current != null) {
            current.syncCommands();
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
        sendMessage(channelId, text, buttons, List.of());
    }

    /**
     * Posts {@code text} with {@code buttons} and {@code attachments}; a no-op if not connected
     * or the channel isn't found.
     * <p>
     * The uploads are opened here and handed to JDA, which closes them once the request has been
     * sent — including when it fails, which is why nothing below closes them itself. A file that
     * cannot be opened at all fails before anything is sent, so a message never arrives claiming
     * an attachment it does not have.
     */
    public void sendMessage(String channelId, String text, List<DiscordButtonSpec> buttons,
                            List<DiscordAttachment> attachments) {
        DiscordGateway current = session();
        JDA jda = current == null ? null : current.jda();
        if (jda == null) {
            log.warn("Cannot send message to channel \"{}\": bot is not connected", channelId);
            return;
        }
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.warn("Cannot send message to channel \"{}\": no such channel in this bot's cache "
                    + "(check the channel id, and that the bot has been invited to the server and can see the channel)", channelId);
            return;
        }
        MessageCreateAction action = channel.sendMessage(text);
        if (!attachments.isEmpty()) {
            action = action.setFiles(DiscordUploads.open(attachments));
        }
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

    // --- What the shared session reads and calls back into --------------------------------------

    /** The guild id this handle registers its commands to, or null for globally. */
    String guildId() {
        return guildId;
    }

    /** What {@link #syncCommands} last declared for this handle. */
    List<SlashCommandSpec> declaredCommands() {
        return declaredCommands;
    }

    /** This handle's ephemeral preference for {@code buttonId}, or null if it declared none. */
    Boolean buttonPreference(String buttonId) {
        return ephemeralByButton.get(buttonId);
    }

    /** Called by a session as it admits this handle. */
    void joined(DiscordGateway session) {
        synchronized (lock) {
            gateway = session;
        }
    }

    /** Called by a session as it drops this handle; ignores a session this handle isn't on. */
    void left(DiscordGateway session) {
        synchronized (lock) {
            if (gateway == session) {
                gateway = null;
            }
        }
    }

    void deliverMessage(DiscordMessage message) {
        messageListeners.forEach(listener -> listener.accept(message));
    }

    void deliverSlashCommand(DiscordSlashCommand command) {
        slashListeners.forEach(listener -> listener.accept(command));
    }

    void deliverButtonClick(DiscordButtonClick click) {
        buttonListeners.forEach(listener -> listener.accept(click));
    }

    private DiscordGateway session() {
        synchronized (lock) {
            return gateway;
        }
    }
}
