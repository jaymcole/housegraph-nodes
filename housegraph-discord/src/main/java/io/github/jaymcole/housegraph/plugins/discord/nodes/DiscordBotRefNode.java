package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.application.Platform;
import javafx.scene.control.Label;

/**
 * A second (third, tenth) place to plug into the bot you already have — without a second
 * connection. It looks up a {@code Discord Bot} node's handle by name and hands it out on its own
 * {@code Bot} output, so a cluster of Discord nodes on the far side of the canvas can wire to
 * something next to it instead of dragging a wire back across the graph.
 * <p>
 * <b>This is the node to reach for when one Discord Bot node's wiring gets messy.</b> A second
 * Discord Bot node on the same token looks like the answer and isn't: a token gets one gateway
 * session, so extra bot nodes are extra claims on one connection at best, and in separate graphs
 * (which the daemon runs as separate processes) they fight over it — see
 * {@code docs/design/discord-one-token-one-session.md}. This node adds no connection at all. It
 * owns nothing, starts nothing, and holds nothing open; it is a label pointing at the one bot.
 * <p>
 * {@code Bot Name} matches the {@code Bot Name} input of the Discord Bot node to point at, which
 * publishes itself under that name when it joins the graph. Resolution is by name and repeated on
 * every read, so load order doesn't matter and renaming either end takes effect immediately
 * (see {@link DiscordBotNode#botFrom}).
 * <p>
 * Everything wired to this node behaves exactly as if wired to the Discord Bot node directly —
 * it is the same {@link DiscordBot} instance, not a copy or a proxy — including Command, Slash
 * Command and Send Buttons nodes, which capture it the moment the wire appears.
 */
@Display.Name("Discord Bot Ref")
@Node.Type("discord.DiscordBotRefNode")
public class DiscordBotRefNode extends BaseNode implements NodeContentProvider {

    private static final String DEFAULT_NAME = "discord";

    private final NodeVariable<String> nameInput =
            withDefault(new NodeVariable<>("Bot Name", String.class, true), DEFAULT_NAME);
    /**
     * Transient, like the Discord Bot node's own {@code Bot} output: a live connection handle is
     * not something a save file can carry. It is re-resolved rather than restored.
     */
    private final NodeVariable<DiscordBot> botOutput =
            new NodeVariable<>("Bot", DiscordBot.class).transientValue();

    private Label statusLabel;

    /** Publishes whatever {@code Bot Name} currently points at; null if nothing answers to it. */
    @Override
    public void process(ProcessContext ctx) {
        botOutput.setValue(resolve());
    }

    /**
     * The bot published under this node's {@code Bot Name} right now, or null if no Discord Bot
     * node in this graph carries that name. Resolved fresh on every call rather than cached: the
     * name is an ordinary input (typed in, or wired from upstream), and the bot node it points at
     * may be added, renamed or removed at any point in the graph's life.
     */
    DiscordBot resolve() {
        return ResourceRegistry.shared().find(currentName(), DiscordBot.class).orElse(null);
    }

    @Override
    protected void onActivated() {
        // A convenience only. Whether this finds anything depends on where the Discord Bot node
        // sits in the load's node order, which is why nothing is allowed to depend on it: the
        // consumers that capture eagerly go through DiscordBotNode.botFrom, which re-resolves, and
        // every later read runs process() again.
        botOutput.setValue(resolve());
    }

    @Override
    public void configureInputs() {
        addInput(nameInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(botOutput);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        refreshStatus();
        return statusLabel;
    }

    /**
     * Says what the name currently resolves to. Worth showing: a name with no bot behind it is
     * this node's one failure mode, it is silent everywhere else, and a typo is easy to make.
     */
    @Override
    protected void onExecuted() {
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusLabel == null) {
            return;
        }
        DiscordBot bot = resolve();
        String text = bot == null
                ? "No bot named \"" + currentName() + "\""
                : bot.isConnected() ? "Connected" : "Found, not connected";
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(text);
        } else {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }

    private String currentName() {
        String name = nameInput.getValue();
        return (name == null || name.isBlank()) ? DEFAULT_NAME : name;
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
