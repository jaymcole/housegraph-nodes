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
import io.github.jaymcole.housegraph.plugins.discord.DiscordButtonSpec;
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
 * Posts a message with configurable buttons to a Discord channel when triggered, and fires a
 * button's own flow-out branch when someone clicks it — one named port per label in
 * {@code Buttons} (e.g. {@code Yes, No} grows a {@code Yes} and a {@code No} flow-out), plus the
 * plain {@code ""} flow-out that fires immediately once the message is sent. A button's label
 * doubles as its id, so no separate id field is needed.
 * <p>
 * Wire a Discord Bot node's {@code Bot} output into this node's {@code Bot} input; the click
 * subscription follows that wire, same as the Discord Command node. Editing {@code Buttons}
 * rebuilds this node's flow-out ports (edges to surviving labels reconnect by name). Because
 * matching is by label, not by which specific message was sent, a click on any message using
 * the same wired bot and a matching button label fires this node — the same trade-off the
 * Discord Command node makes for {@code !command} text.
 * <p>
 * {@link #process} distinguishes the two ways it runs: reached via its flow-in, it sends the
 * message and activates only the plain {@code ""} port; reached via {@link #execute(Runnable)}
 * from a click (no flow-in involved), outputs and the matching button's activation were already
 * set just before, so it does nothing further.
 */
@Display.Name("Discord Send Buttons")
@Node.Type("discord.DiscordSendButtonsNode")
public class DiscordSendButtonsNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<DiscordBot> botInput = new NodeVariable<>("Bot", DiscordBot.class).transientValue().required();
    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true).required();
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class, true).required();
    private final NodeVariable<String> senderId = new NodeVariable<>("Sender ID", String.class);
    private final NodeVariable<String> senderName = new NodeVariable<>("Sender Name", String.class);
    private final NodeVariable<DiscordReply> reply = new NodeVariable<>("Reply", DiscordReply.class).transientValue();
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort sent = new FlowPort("", FlowPort.Direction.OUT);
    private final Map<String, FlowPort> buttonOutputs = new LinkedHashMap<>();
    private final List<String> buttonLabels = new ArrayList<>();

    private DiscordBot bot;
    private Subscription subscription;

    @Override
    public void process(ProcessContext ctx) {
        if (!ctx.wasTriggeredVia(in)) {
            // Reached via a click, not the flow-in: execute() already set the sender/reply
            // outputs and activated the matching button branch just before this ran.
            return;
        }
        DiscordBot currentBot = botInput.getValue();
        String text = message.getValue();
        String channelId = channel.getValue();
        if (currentBot == null || channelId == null || channelId.isBlank() || text == null) {
            activateNone(); // nothing was sent, so no branch — including a button's — should fire
            return;
        }
        List<DiscordButtonSpec> buttons = new ArrayList<>();
        for (String label : buttonLabels) {
            buttons.add(new DiscordButtonSpec(label, label));
        }
        currentBot.sendMessage(channelId, text, buttons);
        activate(sent); // explicit: with button branches also declared, the "activate nothing -> fire everything" default would fire those too
    }

    @Override
    public void configureInputs() {
        addInput(botInput);
        addInput(message);
        addInput(channel);
    }

    @Override
    public void configureOutputs() {
        addOutput(senderId);
        addOutput(senderName);
        addOutput(reply);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(sent);
        buttonOutputs.clear();
        for (String label : buttonLabels) {
            FlowPort port = new FlowPort(label, FlowPort.Direction.OUT);
            buttonOutputs.put(label, port);
            addFlowOutput(port);
        }
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("buttonLabels", String.join(",", buttonLabels));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        buttonLabels.clear();
        buttonLabels.addAll(parseLabels(state.get("buttonLabels")));
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
        TextField labelsField = new TextField(String.join(", ", buttonLabels));
        labelsField.setPromptText("Yes, No");

        Button applyButton = new Button("Apply");
        applyButton.setOnAction(e -> applyLabels(labelsField.getText()));

        Label labelsLabel = new Label("Buttons");
        labelsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        return new VBox(4, labelsLabel, labelsField, applyButton);
    }

    private void applyLabels(String text) {
        List<String> edited = parseLabels(text);
        if (edited.equals(buttonLabels)) {
            return; // no change - avoid a needless rebuild
        }
        buttonLabels.clear();
        buttonLabels.addAll(edited);
        rebuildPorts();
    }

    private static List<String> parseLabels(String text) {
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
