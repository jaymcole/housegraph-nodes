package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.DiscordButtonClick;
import io.github.jaymcole.housegraph.plugins.discord.DiscordReply;
import io.github.jaymcole.housegraph.resource.Subscription;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to a wired bot and, when someone clicks one of its configured buttons, fires that
 * button's own flow-out branch — one named port per id in {@code Button IDs} (e.g.
 * {@code yes, no} grows a {@code yes} and a {@code no} flow-out). {@code Channel}, sender, and
 * a {@code Reply} handle (wire into a Discord Reply node to answer the click) are set the same
 * way regardless of which button fired. Pair with a Discord Send Buttons node using the same ids.
 * <p>
 * Wire a Discord Bot node's {@code Bot} output into this node's {@code Bot} input; the
 * subscription follows the wire, same as the Discord Command node. Editing {@code Button IDs}
 * rebuilds this node's flow-out ports (edges to surviving ids reconnect by name).
 */
@Display.Name("Discord Button Click")
@Node.Type("discord.DiscordButtonClickNode")
public class DiscordButtonClickNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<DiscordBot> botInput = new NodeVariable<>("Bot", DiscordBot.class).transientValue().required();
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class);
    private final NodeVariable<String> senderId = new NodeVariable<>("Sender ID", String.class);
    private final NodeVariable<String> senderName = new NodeVariable<>("Sender Name", String.class);
    private final NodeVariable<DiscordReply> reply = new NodeVariable<>("Reply", DiscordReply.class).transientValue();
    private final Map<String, FlowPort> buttonOutputs = new LinkedHashMap<>();
    private final List<String> buttonIds = new ArrayList<>();

    private DiscordBot bot;
    private Subscription subscription;

    @Override
    public void process(ProcessContext ctx) {
        // Outputs are set and the matching branch activated from the incoming click just
        // before execute(); nothing to compute.
    }

    @Override
    public void configureInputs() {
        addInput(botInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(channel);
        addOutput(senderId);
        addOutput(senderName);
        addOutput(reply);
    }

    @Override
    public void configureFlowOutputs() {
        buttonOutputs.clear();
        for (String id : buttonIds) {
            FlowPort port = new FlowPort(id, FlowPort.Direction.OUT);
            buttonOutputs.put(id, port);
            addFlowOutput(port);
        }
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("buttonIds", String.join(",", buttonIds));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        buttonIds.clear();
        buttonIds.addAll(parseIds(state.get("buttonIds")));
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

    private void subscribeTo(DiscordBot newBot) {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        bot = newBot;
        botInput.setValue(newBot);
        if (bot != null) {
            subscription = bot.addButtonListener(this::onClick);
        }
    }

    private void onClick(DiscordButtonClick click) {
        FlowPort port = buttonOutputs.get(click.buttonId());
        if (port == null) {
            return; // not one of this node's configured buttons
        }
        // Capture everything for this specific click and apply it together inside the pass,
        // so a burst of clicks can't mix one click's sender/reply with another's.
        try {
            execute(() -> {
                channel.setValue(click.channelId());
                senderId.setValue(click.authorId());
                senderName.setValue(click.authorName());
                reply.setValue(click.reply());
                activate(port);
            });
        } catch (IllegalStateException e) {
            // The node was removed just as the click arrived (event on a Discord thread,
            // removal on the UI thread); ignore rather than error.
        }
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        TextField idsField = new TextField(String.join(", ", buttonIds));
        idsField.setPromptText("yes, no");

        Button applyButton = new Button("Apply");
        applyButton.setOnAction(e -> applyIds(idsField.getText()));

        Label idsLabel = new Label("Button IDs");
        idsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        return new VBox(4, idsLabel, idsField, applyButton);
    }

    private void applyIds(String text) {
        List<String> edited = parseIds(text);
        if (edited.equals(buttonIds)) {
            return; // no change - avoid a needless rebuild
        }
        buttonIds.clear();
        buttonIds.addAll(edited);
        rebuildPorts();
    }

    private static List<String> parseIds(String text) {
        List<String> parsed = new ArrayList<>();
        if (text == null) {
            return parsed;
        }
        for (String entry : text.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed;
    }
}
