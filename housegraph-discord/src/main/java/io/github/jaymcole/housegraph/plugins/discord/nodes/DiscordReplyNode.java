package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.discord.DiscordAttachment;
import io.github.jaymcole.housegraph.plugins.discord.DiscordAttachments;
import io.github.jaymcole.housegraph.plugins.discord.DiscordImages;
import io.github.jaymcole.housegraph.plugins.discord.DiscordReply;

import java.util.List;

/**
 * Answers a slash-command invocation. Wire a Discord Slash Command node's {@code Reply}
 * output into this node's {@code Reply} input and give it a {@code Message}; when
 * triggered, it sends that text back to the interaction (editing the deferred
 * "thinking…" response). This is the slash counterpart to Send Message — the reply goes
 * to the specific invocation, so no channel is needed. Control flows through.
 * <p>
 * <b>Attachments</b> takes an image, a file path, or a list of either — it is typed to accept
 * anything because a port typed for images could not take the list Graph Images emits, and one
 * typed for lists could not take the single image a Camera Snapshot emits. A JavaFX image is
 * uploaded as a PNG; a string or {@code Path} is read as a file on disk. Leave it unwired to send
 * text alone. Discord caps one message at ten files, and anything that cannot be sent fails the
 * node rather than posting a message that looks like it worked
 * (see {@link io.github.jaymcole.housegraph.plugins.discord.DiscordAttachments}).
 */
@Display.Name("Discord Reply")
@Node.Type("discord.DiscordReplyNode")
public class DiscordReplyNode extends BaseNode {

    private final NodeVariable<DiscordReply> reply = new NodeVariable<>("Reply", DiscordReply.class).transientValue().required();
    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true).required();
    private final NodeVariable<Object> attachments = new NodeVariable<>("Attachments", Object.class);
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        DiscordReply handle = reply.getValue();
        String text = message.getValue();
        if (handle != null && text != null) {
            handle.reply(text, DiscordAttachments.read(attachments.getValue(), DiscordImages.ENCODER));
        }
    }

    @Override
    public void configureInputs() {
        addInput(reply);
        addInput(message);
        addInput(attachments);
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
