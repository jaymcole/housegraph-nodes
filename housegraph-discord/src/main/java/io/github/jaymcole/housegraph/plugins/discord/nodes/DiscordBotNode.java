package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.SlashCommandRegistry;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import io.github.jaymcole.housegraph.sdk.Secrets;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * The Discord bot resource: a long-lived {@link DiscordBot} connection, managed like a
 * resource-node pattern node, but real. Every setting — name, token secret, guild id — is
 * an ordinary data input. {@code Token Secret} carries the token itself, not a key to look
 * up: wire a secret-resolving node (e.g. HouseGraph's Secret Loader) into it so the actual
 * value never sits in this node's own state, or type it in directly for a quick local test —
 * either way {@link #process} reads it as-is at Connect, with no lookup of its own. Once
 * connected, the {@code Bot} output carries this node's {@link DiscordBot} handle to any Discord Command,
 * Discord Slash Command, or Discord Send Message node wired to it — those subscribe or send
 * directly against the wired instance rather than looking a bot up by name.
 * <p>
 * Two flow-in ports drive the connection lifecycle, both landing in {@link #process}, which
 * tells them apart with {@link ProcessContext#wasTriggeredVia(FlowPort)}: {@code Connect}
 * (also the default for a plain pull, e.g. the Connect button or {@link #autoStartIfWasRunning()})
 * resolves the token and guild id and logs in; {@code Disconnect} tears the gateway connection
 * down. The inline Connect/Disconnect buttons are a convenience, not a separate code path —
 * either reaches the same {@link #process}.
 * <p>
 * The {@code Bot} output is seeded at construction and re-asserted at the top of {@link #process},
 * because it is {@link NodeVariable#transientValue() transient}: it persists as null, and a load
 * applies that null back onto the variable, wiping the seeding. {@link #botFrom(Edge)} covers the
 * window before the first {@code process()}, when a loaded graph's edges are wired up.
 * <p>
 * Liveness is otherwise user-driven, independent of graph flow; the actual gateway login runs
 * off the UI thread so the app stays responsive. The connection is torn down on
 * {@link #onRemoved()} (node deleted or app shutdown).
 * <p>
 * If it was connected when the graph was saved, it reconnects automatically on load: the
 * connected flag rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses
 * Connect for the user, reading whatever is currently wired into {@code Token Secret} as usual
 * (see {@link AutoStartable}).
 * <p>
 * Two Discord Bot nodes wired to the same token — in this graph or another one loaded into the
 * same running app — do not each open their own gateway connection. Discord gives a token one
 * gateway session and replaces the old one when a second logs in, so a second connection wouldn't
 * be a second bot, it would be the first bot being kicked. Deduplication happens a level below
 * this node, inside {@link DiscordBot}: connecting joins this process's session for the token, and
 * the session is only torn down once every node using it has disconnected. This node's own handle
 * is never swapped for another node's, so everything captured from it stays valid.
 * <p>
 * That covers one process. HouseGraph's daemon runs one JVM per graph, so two <em>graphs</em> on
 * one token are two processes with no way to see each other, and there Discord's one-session rule
 * still bites: see {@code docs/design/discord-one-token-one-session.md}.
 */
@Display.Name("Discord Bot")
@Node.Type("discord.DiscordBotNode")
public class DiscordBotNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final Logger log = Log.get(DiscordBotNode.class);
    private static final String DEFAULT_NAME = "discord";

    /**
     * This node's own {@link DiscordBot} handle, for its whole life. Sharing a connection with
     * another node on the same token happens inside the handle, not by exchanging handles: other
     * nodes capture this instance when the wire appears (see {@link #botFrom(Edge)}), so swapping
     * it at Connect would leave them holding one that never connects.
     */
    private final DiscordBot bot = new DiscordBot();

    private final NodeVariable<String> nameInput =
            withDefault(new NodeVariable<>("Bot Name", String.class, true), DEFAULT_NAME);
    private final NodeVariable<String> tokenSecretInput =
            new NodeVariable<>("Token Secret", String.class, true).required().markSecret();
    private final NodeVariable<String> guildIdInput =
            new NodeVariable<>("Guild ID", String.class, true);
    private final NodeVariable<DiscordBot> botOutput =
            withDefault(new NodeVariable<>("Bot", DiscordBot.class).transientValue(), bot);

    private final FlowPort connectIn = new FlowPort("Connect", FlowPort.Direction.IN);
    private final FlowPort disconnectIn = new FlowPort("Disconnect", FlowPort.Direction.IN);

    /** True when the bot was connected at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasConnected;

    private Button connectButton;
    private Button disconnectButton;
    private Label statusLabel;

    /**
     * Resolves the token and guild id fresh, then does exactly one of two things depending on
     * which flow-in port brought control here: {@link #disconnectIn} tears the gateway down;
     * anything else — including {@link #connectIn}, and an empty {@code triggeredVia()} (a plain
     * {@link #beginProcessing()} pull, e.g. the Connect button or {@link #autoStartIfWasRunning()})
     * — logs in. A missing token secret or a login failure throws with a message safe to show in
     * the UI (never the token itself), which the engine surfaces as this node's error for
     * {@link #onExecuted()}/the button handlers.
     */
    @Override
    public void process(ProcessContext ctx) {
        // Re-assert the handle before anything else: a graph load writes the saved null over this
        // output (it's transient, so it persists as null and the loader applies that null back onto
        // the variable), leaving the constructor's seeding wiped and every downstream pull reading
        // null forever. See botFrom(Edge) for the edge-time half of the same problem.
        botOutput.setValue(bot);
        if (ctx.wasTriggeredVia(disconnectIn)) {
            disconnectBot();
        } else {
            connectBot();
        }
    }

    /**
     * The {@link DiscordBot} on the source side of a data edge wired into another node's
     * {@code Bot} input — for the nodes that must capture it the moment the wire appears
     * (Command, Slash Command, Send Buttons) rather than pull it during {@code process()}.
     * <p>
     * Reads the source output's value, and falls back to the source node's own handle when that
     * value is null. That fallback is what makes a <em>loaded</em> graph work: {@code Bot} is a
     * transient output, so it is saved as null and the loader applies that null back onto the
     * variable, wiping what the constructor seeded — and edges are restored (firing the consumers'
     * {@code onInputEdgeAdded}) before anything re-seeds it, so the eager capture would otherwise
     * read null and the node would never subscribe. A {@link DiscordBotNode} always has its handle,
     * from construction, whatever its output variable currently holds.
     *
     * @param edge a data edge whose target is some node's {@code Bot} input
     * @return the bot on the other end, or null if the source carries none
     */
    static DiscordBot botFrom(Edge edge) {
        Object value = edge.getSourceVariable().getValue();
        if (value instanceof DiscordBot wired) {
            return wired;
        }
        if (edge.getSourceNode() instanceof DiscordBotNode source) {
            return source.bot;
        }
        return null;
    }

    private void connectBot() {
        if (bot.isConnected()) {
            // A redundant Connect must be a no-op, not a reconnect. This node's Bot output is
            // wired as a plain data edge into other nodes (Send Message, Command, ...), and the
            // engine resolves that edge — re-running THIS node's own process() — every time any
            // of them run, not just on a genuine user-initiated Connect. Without this guard,
            // ordinary Discord activity (e.g. every incoming message a Discord Command node
            // reacts to) was silently forcing a full reconnect and slash-command re-sync each
            // time, which is what was hammering Discord's command-sync endpoint into a 429 and
            // leaving the actual connection never stable long enough to send anything.
            return;
        }
        String token = resolveToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Pick a token secret first");
        }
        try {
            // Joins this process's session for the token — opening it if this is the first node
            // to ask, sharing it if another node already has (see DiscordBot/DiscordGateway).
            bot.connect(token);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Discord connect failed: {}", e);
            throw new RuntimeException("Connect failed — check token & MESSAGE_CONTENT intent", e);
        } catch (RuntimeException e) {
            // The exception text won't contain the token, but keep the UI message generic
            // and log only the type/message, never the token itself.
            log.error("Discord connect failed: {}", e);
            throw new RuntimeException("Connect failed — check token & MESSAGE_CONTENT intent", e);
        }
        bot.setGuildId(guildIdInput.getValue());
        // Registers the union across every node sharing this session, so this doesn't wipe the
        // commands another Discord Bot node on the same token registered.
        bot.syncCommands(SlashCommandRegistry.shared().commandsFor(bot));
    }

    /**
     * Undoes {@link #connectBot()}: drops this node's claim on the connection. Whether that
     * actually closes the gateway session is {@link DiscordBot}'s business — it stays open for any
     * other node on the same token that is still using it, and closes when the last one lets go.
     */
    private void disconnectBot() {
        bot.disconnect();
    }

    @Override
    public void configureInputs() {
        addInput(nameInput);
        addInput(tokenSecretInput);
        addInput(guildIdInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(botOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(connectIn);
        addFlowInput(disconnectIn);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        if (bot.isConnected()) {
            state.put("running", "true");
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        // Pre-input-port saves kept these fields in this custom state map; migrate each onto its
        // new port so an old graph doesn't lose its settings (applyValues() no-ops afterward for
        // such a save, since its "inputs" array has no entry for a port that didn't exist yet).
        String legacyName = emptyToNull(state.get("name"));
        if (legacyName != null) {
            nameInput.setValue(legacyName);
        }
        String legacyToken = emptyToNull(state.get("token"));
        if (legacyToken != null) {
            // Pre-port saves stored the secret's *key*, resolved via Secrets.get() at Connect
            // time; Token Secret now carries the resolved value itself, so resolve the legacy
            // key once here rather than teaching process() a lookup it no longer needs.
            String resolvedToken = Secrets.get(legacyToken);
            tokenSecretInput.setValue(resolvedToken != null ? resolvedToken : legacyToken);
        }
        String legacyGuild = emptyToNull(state.get("guild"));
        if (legacyGuild != null) {
            guildIdInput.setValue(legacyGuild);
        }
        wasConnected = Boolean.parseBoolean(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (wasConnected) {
            connect();
        }
    }

    /** Test seam: whether the loaded graph had this bot connected, i.e. auto-connect is pending. */
    boolean wasConnected() {
        return wasConnected;
    }

    @Override
    protected void onRemoved() {
        // Deleting one node that shares a connection with others on the same token leaves that
        // connection up for them; deleting the last one closes it (see DiscordGateway).
        disconnectBot();
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        connectButton = new Button("Connect");
        connectButton.setMaxWidth(Double.MAX_VALUE);
        connectButton.setOnAction(e -> connect());

        disconnectButton = new Button("Disconnect");
        disconnectButton.setMaxWidth(Double.MAX_VALUE);
        disconnectButton.setDisable(true);
        disconnectButton.setOnAction(e -> disconnect());

        statusLabel = new Label("Disconnected");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        HBox buttons = new HBox(6, connectButton, disconnectButton);
        return new VBox(4, buttons, statusLabel);
    }

    /**
     * Runs {@link #process} via {@link #beginProcessing()} on a background thread — a plain pull, so
     * {@code ctx.triggeredVia()} reads empty and {@link #process} defaults to its Connect behaviour,
     * exactly like a wired Connect arrival. UI feedback is handled here rather than left to
     * {@link #onExecuted()} so a failure can report the specific exception message immediately.
     */
    private void connect() {
        connectButton.setDisable(true);
        statusLabel.setText("Connecting…");

        Thread thread = new Thread(() -> {
            beginProcessing();
            Throwable error = getLastError();
            Platform.runLater(() -> {
                if (error != null) {
                    statusLabel.setText(error.getMessage());
                    connectButton.setDisable(false);
                } else {
                    statusLabel.setText("Connected as \"" + currentName() + "\"");
                    disconnectButton.setDisable(false);
                }
            });
        }, "discord-connect-" + currentName());
        thread.setDaemon(true);
        thread.start();
    }

    private void disconnect() {
        disconnectBot();
        statusLabel.setText("Disconnected");
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
    }

    /**
     * Reflects the outcome of any {@code process()} pass — a Connect or Disconnect arriving along a
     * wired flow edge, or a plain pull like the button handlers' own {@link #beginProcessing()} call
     * racing to update the same label — in the status label/buttons. Idempotent: both paths compute
     * the same text from {@link DiscordBot#isConnected()}/{@link #getLastError()}, so whichever runs
     * last leaves the correct state either way. Reached on the FX thread (the engine's callback
     * executor); a no-op if the node's UI was never built.
     */
    @Override
    protected void onExecuted() {
        if (statusLabel == null) {
            return;
        }
        Throwable error = getLastError();
        if (error != null) {
            statusLabel.setText(error.getMessage());
        } else if (bot.isConnected()) {
            statusLabel.setText("Connected as \"" + currentName() + "\"");
        } else {
            statusLabel.setText("Disconnected");
        }
        connectButton.setDisable(bot.isConnected());
        disconnectButton.setDisable(!bot.isConnected());
    }

    private String currentName() {
        return valueOr(nameInput.getValue(), DEFAULT_NAME);
    }

    /**
     * Test seam: {@code Token Secret}'s current value, read exactly as {@link #connectBot()}
     * reads it — as the token itself, with no lookup of its own (that's what wiring in a
     * secret-resolving node, e.g. Secret Loader, is for).
     */
    String resolveToken() {
        return tokenSecretInput.getValue();
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }

    private static String valueOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
