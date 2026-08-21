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
 * A click never re-enters {@link #process}: {@code process()} only ever means "send" (there is
 * no reliable way to tell "reached via a real flow-in edge" apart from "reached via a click" —
 * both a plain host-UI test-run of this node and this node's own click re-entry produce an
 * empty {@link ProcessContext#triggeredVia()}, since neither traverses an actual flow edge). A
 * click instead calls {@link #runFlowBranchToCompletion(FlowPort, Runnable)} directly on the
 * matching button port, off a background thread since it blocks until that branch finishes.
 * <p>
 * {@code Bot} is captured eagerly via {@link #onInputEdgeAdded}/{@link #onInputEdgeRemoved} into
 * a plain field (needed anyway for the click subscription); {@code process()} reads that field,
 * not {@code botInput.getValue()} — the same reasoning as {@code DiscordSendMessageNode}: a
 * {@code DiscordBot} is a live resource wired in from a node outside this node's own
 * flow-triggered pass, so pulling it via the normal per-pass data-edge resolution is unreliable.
 */
@Display.Name("Discord Send Buttons")
@Node.Type("discord.DiscordSendButtonsNode")
public class DiscordSendButtonsNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(DiscordSendButtonsNode.class);

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
        String text = message.getValue();
        String channelId = channel.getValue();
        if (bot == null) {
            log.warn("Discord Send Buttons did nothing: no Bot wired in");
            activateNone(); // nothing was sent, so no branch — including a button's — should fire
            return;
        }
        if (channelId == null || channelId.isBlank()) {
            log.warn("Discord Send Buttons did nothing: Channel is empty");
            activateNone();
            return;
        }
        if (text == null) {
            log.warn("Discord Send Buttons did nothing: Message is empty");
            activateNone();
            return;
        }
        List<DiscordButtonSpec> buttons = new ArrayList<>();
        for (String label : buttonLabels) {
            buttons.add(new DiscordButtonSpec(label, label));
        }
        bot.sendMessage(channelId, text, buttons);
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
            log.debug("Discord Send Buttons ignored a click on \"{}\": not one of this node's configured buttons {}",
                    click.buttonId(), buttonLabels);
            return;
        }
        log.info("Discord Send Buttons firing \"{}\" branch for a click from \"{}\"", click.buttonId(), click.authorName());
        // runFlowBranchToCompletion blocks until the whole downstream branch finishes, so run
        // it off its own thread rather than tying up the JDA gateway thread the click arrived
        // on. The seed sets this specific click's sender/reply directly on the output
        // variables — same as every other event-source node in this library — before the
        // matching button branch fires.
        Thread thread = new Thread(() -> {
            try {
                runFlowBranchToCompletion(port, () -> {
                    senderId.setValue(click.authorId());
                    senderName.setValue(click.authorName());
                    reply.setValue(click.reply());
                });
            } catch (IllegalStateException e) {
                // The node was removed just as the click arrived, or isn't on a graph; ignore.
            }
        }, "discord-button-click-" + click.buttonId());
        thread.setDaemon(true);
        thread.start();
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
