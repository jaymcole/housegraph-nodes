package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.CommandMatcher;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.DiscordMessage;
import io.github.jaymcole.housegraph.resource.Subscription;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * A modular Discord command: listens to a wired bot and, when a message invokes its
 * trigger (e.g. {@code !deploy}), fires its flow-out with the invocation details on its
 * outputs — the {@code Args} after the command, plus the {@code Channel} it came from
 * (wire this into a Send Message node to reply there) and the sender's id and name.
 * Each command node is one self-contained trigger point on the graph.
 * <p>
 * Wire a Discord Bot node's {@code Bot} output into this node's {@code Bot} input; the
 * subscription follows the wire — {@link #onInputEdgeAdded}/{@link #onInputEdgeRemoved}
 * (re)subscribe against whatever {@link DiscordBot} is currently on the other end, so
 * rewiring to a different bot takes effect immediately, with no flow-in needed to pick it
 * up. Matching is handled by {@link CommandMatcher}. Events arrive on a Discord thread;
 * firing goes through the normal background-threaded trigger path, with all outputs set
 * together for that one invocation so a burst of commands can't mix their values.
 */
@Display.Name("Discord Command")
@Node.Type("discord.DiscordCommandNode")
public class DiscordCommandNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<DiscordBot> botInput = new NodeVariable<>("Bot", DiscordBot.class).transientValue().required();
    private final NodeVariable<String> args = new NodeVariable<>("Args", String.class);
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class);
    private final NodeVariable<String> senderId = new NodeVariable<>("Sender ID", String.class);
    private final NodeVariable<String> senderName = new NodeVariable<>("Sender Name", String.class);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private String command = "!command";
    private Subscription subscription;

    @Override
    public void process(ProcessContext ctx) {
        // Outputs are set from the incoming message just before execute(); nothing to compute.
    }

    @Override
    public void configureInputs() {
        addInput(botInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(args);
        addOutput(channel);
        addOutput(senderId);
        addOutput(senderName);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("command", command);
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String saved = state.get("command");
        if (saved != null && !saved.isBlank()) {
            command = saved;
        }
    }

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        if (edge.getTargetVariable() == botInput) {
            subscribeTo((DiscordBot) edge.getSourceVariable().getValue());
        }
    }

    @Override
    protected void onInputEdgeRemoved(Edge edge) {
        if (edge.getTargetVariable() == botInput) {
            subscribeTo(null);
        }
    }

    @Override
    protected void onRemoved() {
        subscribeTo(null);
    }

    private void subscribeTo(DiscordBot bot) {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        botInput.setValue(bot);
        if (bot != null) {
            subscription = bot.addMessageListener(this::onMessage);
        }
    }

    private void onMessage(DiscordMessage message) {
        String content = message.content();
        if (!CommandMatcher.matches(content, command)) {
            return;
        }
        // Capture everything for this specific invocation and apply it together inside
        // the pass, so a burst of the same command can't mix one call's args with
        // another's channel/sender.
        String invocationArgs = CommandMatcher.args(content, command);
        try {
            execute(() -> {
                args.setValue(invocationArgs);
                channel.setValue(message.channelId());
                senderId.setValue(message.authorId());
                senderName.setValue(message.authorName());
            });
        } catch (IllegalStateException e) {
            // The node was removed just as the message arrived (event on a Discord
            // thread, removal on the UI thread); ignore rather than error.
        }
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        TextField commandField = new TextField(command);
        commandField.setPromptText("!command");
        commandField.textProperty().addListener((obs, old, value) -> command = value);

        return new VBox(4, commandField);
    }
}
