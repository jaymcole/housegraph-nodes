package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.DiscordButtonSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Posts a message with up to 5 buttons to a Discord channel when triggered. Wire a Discord
 * Bot node's {@code Bot} output into this node's {@code Bot} input; fill in as many of the 5
 * label/id slots as needed (a slot is skipped if either its label or id is blank). Pair with
 * a Discord Button Click node configured with the same ids to react to a click. Control flows
 * through, so you can chain more work after sending.
 */
@Display.Name("Discord Send Buttons")
@Node.Type("discord.DiscordSendButtonsNode")
public class DiscordSendButtonsNode extends BaseNode {

    private static final int BUTTON_SLOTS = 5;

    private final NodeVariable<DiscordBot> botInput = new NodeVariable<>("Bot", DiscordBot.class).transientValue().required();
    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true).required();
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class, true).required();
    private final List<NodeVariable<String>> buttonLabels = new ArrayList<>(BUTTON_SLOTS);
    private final List<NodeVariable<String>> buttonIds = new ArrayList<>(BUTTON_SLOTS);
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    public DiscordSendButtonsNode() {
        for (int slot = 1; slot <= BUTTON_SLOTS; slot++) {
            buttonLabels.add(new NodeVariable<>("Button " + slot + " Label", String.class, true));
            buttonIds.add(new NodeVariable<>("Button " + slot + " Id", String.class, true));
        }
    }

    @Override
    public void process(ProcessContext ctx) {
        DiscordBot bot = botInput.getValue();
        String text = message.getValue();
        String channelId = channel.getValue();
        if (bot == null || channelId == null || channelId.isBlank() || text == null) {
            return;
        }
        List<DiscordButtonSpec> buttons = new ArrayList<>();
        for (int slot = 0; slot < BUTTON_SLOTS; slot++) {
            String label = buttonLabels.get(slot).getValue();
            String id = buttonIds.get(slot).getValue();
            if (label != null && !label.isBlank() && id != null && !id.isBlank()) {
                buttons.add(new DiscordButtonSpec(id, label));
            }
        }
        bot.sendMessage(channelId, text, buttons);
    }

    @Override
    public void configureInputs() {
        addInput(botInput);
        addInput(message);
        addInput(channel);
        for (int slot = 0; slot < BUTTON_SLOTS; slot++) {
            addInput(buttonLabels.get(slot));
            addInput(buttonIds.get(slot));
        }
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
