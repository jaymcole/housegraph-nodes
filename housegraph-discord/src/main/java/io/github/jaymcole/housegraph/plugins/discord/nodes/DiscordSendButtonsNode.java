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
import io.github.jaymcole.housegraph.plugins.discord.DiscordAttachment;
import io.github.jaymcole.housegraph.plugins.discord.DiscordAttachments;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.DiscordImages;
import io.github.jaymcole.housegraph.plugins.discord.DiscordButtonClick;
import io.github.jaymcole.housegraph.plugins.discord.DiscordButtonSpec;
import io.github.jaymcole.housegraph.plugins.discord.DiscordReply;
import io.github.jaymcole.housegraph.resource.Subscription;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * {@code Bot} is captured via {@link #onInputEdgeAdded}/{@link #onInputEdgeRemoved} into a plain
 * field, needed regardless of process() to (re)subscribe the click listener the moment the wire
 * changes rather than only when this node happens to run. That capture goes through
 * {@link DiscordBotNode#botFrom(Edge)} rather than reading the wired output's value directly,
 * because on a graph load that value is null (see there). {@code process()} also resolves
 * {@code Bot} the normal pull way (safe now that {@code DiscordBotNode#connectBot} is idempotent)
 * and, if it disagrees with the captured field, (re)subscribes right there before doing anything
 * else. Belt and suspenders: the eager path is the one a click needs, the pull is the one that
 * can't be stale.
 * <p>
 * A click's message has its buttons disabled so they can't be pressed again — handled by the
 * gateway session behind {@link DiscordBot}, and switchable off per button id via
 * {@link DiscordBot#setButtonDisableOnClick} by clearing this node's "Disable after first
 * click". Cleared, the message stays pressable — by the same person again, and by everyone
 * else — which is what makes "Max clicks per person" possible: Discord has no per-viewer
 * component state (the buttons are part of the one shared message, so disabling them disables
 * them for everybody), so a per-person budget can only be enforced here, by counting. This
 * node keeps a click count per Discord user id and, once someone is over their budget, fires
 * its {@code Exceeded} flow-out instead of the clicked button's own — with that click's
 * sender/reply seeded the same way, so the branch can tell them they're done. The port only
 * exists while a limit is configured. Two consequences worth knowing: the count is per person
 * across <em>all</em> of this node's buttons (a 1-click budget on a {@code Yes, No} pair means
 * one answer, not one of each), and it's in-memory and reset on every send — a fresh message
 * starts a fresh round, and a host restart forgets who clicked.
 * <p>
 * Leaving "Disable after first click" on while a per-person limit is set is contradictory —
 * the first click by anyone disables the buttons for everyone, so nobody reaches their second.
 * The node logs a warning rather than silently picking one.
 * <p>
 * That same listener also decides, per button id,
 * whether to defer the click ephemerally (visible only to the clicker) via
 * {@link DiscordBot#setButtonEphemeral}: this node declares that preference — ephemeral unless
 * {@code Reply} has an outgoing edge — every time it matters (bot wired, labels changed, or
 * {@code Reply}'s wiring changed), tracked via {@link #onOutputEdgeAdded}/
 * {@link #onOutputEdgeRemoved}. A prior declaration is withdrawn first so a removed/renamed
 * button doesn't leave a stale entry behind for some other node's button of the same label to
 * pick up.
 */
@Display.Name("Discord Send Buttons")
@Node.Type("discord.DiscordSendButtonsNode")
public class DiscordSendButtonsNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(DiscordSendButtonsNode.class);

    /** Name of the flow-out fired instead of a button's own when the clicker is over their budget. */
    private static final String EXCEEDED_PORT = "Exceeded";

    private final NodeVariable<DiscordBot> botInput = new NodeVariable<>("Bot", DiscordBot.class).transientValue().required();
    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true).required();
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class, true).required();
    private final NodeVariable<Object> attachments = new NodeVariable<>("Attachments", Object.class);
    private final NodeVariable<String> senderId = new NodeVariable<>("Sender ID", String.class);
    private final NodeVariable<String> senderName = new NodeVariable<>("Sender Name", String.class);
    private final NodeVariable<DiscordReply> reply = new NodeVariable<>("Reply", DiscordReply.class).transientValue();
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort sent = new FlowPort("", FlowPort.Direction.OUT);
    private final Map<String, FlowPort> buttonOutputs = new LinkedHashMap<>();
    private final List<String> buttonLabels = new ArrayList<>();
    /** Discord user id -> clicks taken on this node's buttons since the last send; see the class doc. */
    private final Map<String, Integer> clicksByUser = new ConcurrentHashMap<>();

    private DiscordBot bot;
    private Subscription subscription;
    private boolean replyWired;
    private DiscordBot declaredBot;
    private List<String> declaredLabels = new ArrayList<>();
    private boolean disableAfterFirstClick = true;
    private int maxClicksPerPerson;
    /** The {@code Exceeded} port while one is declared (a limit is set), else null. Rebuilt in {@link #configureFlowOutputs}. */
    private FlowPort exceededOutput;

    @Override
    public void process(ProcessContext ctx) {
        // Self-heal against onInputEdgeAdded's callback not having caught up yet (see the class
        // doc): resolve the wired Bot the normal pull way too, and if it's not what we've got
        // subscribed against, (re)subscribe before doing anything else. Harmless when they
        // already agree — subscribeTo() is idempotent.
        DiscordBot wiredBot = botInput.getValue();
        if (wiredBot != bot) {
            subscribeTo(wiredBot);
        }
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
        // Read before anything is sent, so a bad path fails the node instead of posting the
        // buttons and losing the file they were about.
        List<DiscordAttachment> files = DiscordAttachments.read(attachments.getValue(), DiscordImages.ENCODER);
        bot.sendMessage(channelId, text, buttons, files);
        // A new message is a new round: whoever used up their clicks on the last one gets
        // their budget back. Only on an actual send, so a bail-out above leaves the counts alone.
        clicksByUser.clear();
        activate(sent); // explicit: with button branches also declared, the "activate nothing -> fire everything" default would fire those too
    }

    @Override
    public void configureInputs() {
        addInput(botInput);
        addInput(message);
        addInput(channel);
        addInput(attachments);
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
        // Last, after the buttons, so adding or removing a limit doesn't shuffle their order.
        exceededOutput = null;
        if (maxClicksPerPerson > 0) {
            if (buttonOutputs.containsKey(EXCEEDED_PORT)) {
                // A button labelled "Exceeded" already owns that port name. Two flow-outs with
                // one name would make saved edges reconnect to whichever came first, so skip
                // the limit's port rather than build an ambiguous node.
                log.warn("Discord Send Buttons has no \"{}\" flow-out for its per-person limit: "
                        + "a button of that name already claims it — rename that button", EXCEEDED_PORT);
            } else {
                exceededOutput = new FlowPort(EXCEEDED_PORT, FlowPort.Direction.OUT);
                addFlowOutput(exceededOutput);
            }
        }
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("buttonLabels", String.join(",", buttonLabels));
        state.put("disableAfterFirstClick", Boolean.toString(disableAfterFirstClick));
        state.put("maxClicksPerPerson", Integer.toString(maxClicksPerPerson));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        buttonLabels.clear();
        buttonLabels.addAll(parseLabels(state.get("buttonLabels")));
        // Absent means a graph saved before the option existed, when disabling was
        // unconditional — so absent has to mean true, not parseBoolean(null)'s false.
        String savedDisable = state.get("disableAfterFirstClick");
        disableAfterFirstClick = savedDisable == null || Boolean.parseBoolean(savedDisable);
        maxClicksPerPerson = parseMaxClicks(state.get("maxClicksPerPerson"), 0);
    }

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        if (edge.getTargetVariable() == botInput) {
            subscribeTo(DiscordBotNode.botFrom(edge));
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

    @Override
    protected void onOutputEdgeAdded(Edge edge) {
        if (edge.getSourceVariable() == reply) {
            replyWired = true;
            redeclareButtonPreferences();
        }
    }

    @Override
    protected void onOutputEdgeRemoved(Edge edge) {
        if (edge.getSourceVariable() == reply) {
            replyWired = hasOutgoingReplyEdge();
            redeclareButtonPreferences();
        }
    }

    private boolean hasOutgoingReplyEdge() {
        for (Edge edge : getOutgoingDataEdges()) {
            if (edge.getSourceVariable() == reply) {
                return true;
            }
        }
        return false;
    }

    private void subscribeTo(DiscordBot newBot) {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        bot = newBot;
        botInput.setValue(newBot);
        log.debug("Discord Send Buttons {} its Bot", newBot != null ? "captured" : "cleared");
        if (bot != null) {
            subscription = bot.addButtonListener(this::onClick);
        }
        redeclareButtonPreferences();
    }

    /**
     * (Re)declares this node's per-button preferences — ephemeral-vs-public replies, and
     * whether a click disables the message — for its currently configured button labels
     * against the currently wired bot, withdrawing whatever was previously declared first (bot
     * and/or labels may have changed since).
     */
    private void redeclareButtonPreferences() {
        if (declaredBot != null) {
            for (String label : declaredLabels) {
                declaredBot.clearButtonEphemeral(label);
                declaredBot.clearButtonDisableOnClick(label);
            }
        }
        declaredBot = bot;
        declaredLabels = new ArrayList<>(buttonLabels);
        if (bot != null) {
            boolean ephemeral = !replyWired;
            for (String label : buttonLabels) {
                bot.setButtonEphemeral(label, ephemeral);
                bot.setButtonDisableOnClick(label, disableAfterFirstClick);
            }
        }
        if (disableAfterFirstClick && maxClicksPerPerson > 0) {
            log.warn("Discord Send Buttons has both \"Disable after first click\" and a per-person "
                    + "limit of {} set: the first click by anyone disables the buttons for everyone, "
                    + "so nobody gets a second click. Clear one of the two.", maxClicksPerPerson);
        }
    }

    /**
     * Decides which flow-out a click should fire: the clicked button's own, the
     * {@code Exceeded} port if the clicker has used up their per-person budget, or null to
     * ignore the click (a button this node doesn't have) — counting the click on the way,
     * which is why this is called exactly once per click. Separate from {@link #onClick} so the
     * routing can be tested without a live gateway to click on.
     */
    FlowPort routeClick(String buttonId, String userId) {
        FlowPort port = buttonOutputs.get(buttonId);
        if (port == null) {
            log.debug("Discord Send Buttons ignored a click on \"{}\": not one of this node's configured buttons {}",
                    buttonId, buttonLabels);
            return null;
        }
        // merge() is atomic, so two near-simultaneous clicks from one person can't both read
        // the same count and both slip past the last slot. The count keeps climbing past the
        // limit for a persistent clicker; only whether it's over the line matters.
        int used = clicksByUser.merge(userId, 1, Integer::sum);
        int limit = maxClicksPerPerson;
        if (limit > 0 && used > limit) {
            if (exceededOutput == null) {
                log.debug("Discord Send Buttons dropped a click on \"{}\": over the per-person limit of {} "
                        + "with no \"{}\" flow-out to fire", buttonId, limit, EXCEEDED_PORT);
            } else {
                log.info("Discord Send Buttons firing \"{}\" branch: click {} on \"{}\" is over the per-person limit of {}",
                        EXCEEDED_PORT, used, buttonId, limit);
            }
            return exceededOutput;
        }
        return port;
    }

    private void onClick(DiscordButtonClick click) {
        FlowPort port = routeClick(click.buttonId(), click.authorId());
        if (port == null) {
            return; // routeClick logged why
        }
        if (port != exceededOutput) {
            log.info("Discord Send Buttons firing \"{}\" branch for a click from \"{}\"", click.buttonId(), click.authorName());
        }
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

        Label labelsLabel = new Label("Buttons");
        labelsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        // The checkbox changes nothing about this node's shape, so it applies as you click it.
        // The limit does (it grows the Exceeded port), so it waits for Apply along with the
        // labels — one rebuild instead of one per keystroke.
        CheckBox disableBox = new CheckBox("Disable after first click");
        disableBox.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");
        disableBox.setSelected(disableAfterFirstClick);
        disableBox.selectedProperty().addListener((obs, was, now) -> {
            disableAfterFirstClick = now;
            redeclareButtonPreferences();
        });

        Label maxLabel = new Label("Max clicks per person (0 = unlimited)");
        maxLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        TextField maxField = new TextField(Integer.toString(maxClicksPerPerson));
        maxField.setPromptText("0");

        Button applyButton = new Button("Apply");
        applyButton.setOnAction(e -> applyEdits(labelsField.getText(), maxField.getText()));

        // wrapText handles the line breaks, so no embedded newlines to keep in sync.
        Label hint = new Label("A limit needs the buttons left pressable, so clear "
                + "\"Disable after first click\" to use it. Counts are per person across all of "
                + "this node's buttons, and reset on every send.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #888888; -fx-font-size: 9px;");

        return new VBox(4, labelsLabel, labelsField, disableBox, maxLabel, maxField, applyButton, hint);
    }

    private void applyEdits(String labelsText, String maxClicksText) {
        List<String> editedLabels = parseLabels(labelsText);
        int editedMax = parseMaxClicks(maxClicksText, maxClicksPerPerson);
        if (editedLabels.equals(buttonLabels) && editedMax == maxClicksPerPerson) {
            return; // no change - avoid a needless rebuild
        }
        buttonLabels.clear();
        buttonLabels.addAll(editedLabels);
        maxClicksPerPerson = editedMax;
        rebuildPorts();
        redeclareButtonPreferences();
    }

    /** Parses a click limit, falling back to {@code fallback} for anything that isn't a number. Negatives mean unlimited. */
    private static int parseMaxClicks(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            log.warn("Discord Send Buttons ignored \"{}\" as a max-clicks-per-person value: not a number", text);
            return fallback;
        }
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
