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
import io.github.jaymcole.housegraph.plugins.discord.DiscordWebhookClient;
import io.github.jaymcole.housegraph.plugins.discord.DiscordWebhookException;

import java.util.List;

/**
 * Posts a message to a Discord <a href="https://discord.com/developers/docs/resources/webhook">webhook</a>
 * when triggered. Unlike Discord Send Message, this needs no Discord Bot node wired in at all —
 * a webhook posts over a plain HTTP call carrying its own URL as the credential, with no bot
 * login or gateway connection involved. That makes this the lighter-weight option when the
 * destination is a single fixed channel and nothing else in the graph needs a live bot
 * connection (commands, buttons, replies).
 * <p>
 * {@code Webhook URL} is the full URL Discord issued when the webhook was created (channel
 * settings → Integrations → Webhooks) — treat it like a password, since anyone holding it can
 * post as the webhook with no further authentication. It is marked secret so it is never written
 * into a save file; wire a Secret Loader into it rather than typing it in directly.
 * <p>
 * {@code Username} and {@code Avatar URL} are optional per-message overrides of the webhook's
 * own configured name/avatar; leave either blank to use what the webhook is already set to.
 * <p>
 * A failure — an invalid or deleted webhook, an unreachable URL, a request Discord rejects —
 * fails the node rather than silently doing nothing, so it shows up on the canvas instead of
 * disappearing (see {@link DiscordWebhookClient}).
 * <p>
 * <b>Attachments</b> takes an image, a file path, or a list of either — it is typed to accept
 * anything because a port typed for images could not take the list Graph Images emits, and one
 * typed for lists could not take the single image a Camera Snapshot emits. A JavaFX image is
 * uploaded as a PNG; a string or {@code Path} is read as a file on disk. Leave it unwired to send
 * text alone. Discord caps one message at ten files, and anything that cannot be sent fails the
 * node rather than posting a message that looks like it worked
 * (see {@link io.github.jaymcole.housegraph.plugins.discord.DiscordAttachments}).
 * <p>
 * Attaching a file also changes how the post is made: with none it is one JSON request, and with
 * any it becomes a {@code multipart/form-data} upload, which is the only shape Discord's webhook
 * API accepts files in.
 */
@Display.Name("Discord Send Webhook Message")
@Node.Type("discord.DiscordSendWebhookMessageNode")
public class DiscordSendWebhookMessageNode extends BaseNode {

    private final NodeVariable<String> webhookUrl = new NodeVariable<>("Webhook URL", String.class, true).required().markSecret();
    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true).required();
    private final NodeVariable<String> username = new NodeVariable<>("Username", String.class, true);
    private final NodeVariable<String> avatarUrl = new NodeVariable<>("Avatar URL", String.class, true);
    private final NodeVariable<Object> attachments = new NodeVariable<>("Attachments", Object.class);
    private final NodeVariable<Integer> timeout = new NodeVariable<>("Timeout (s)", Integer.class, true);
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        String url = webhookUrl.getValue();
        String text = message.getValue();
        if (url == null || url.isBlank()) {
            throw new DiscordWebhookException("Webhook URL is empty.");
        }
        if (text == null || text.isBlank()) {
            throw new DiscordWebhookException("Message is empty.");
        }
        // Read before the request is built: a path with no file at it should fail the node rather
        // than post the message and lose the attachment it was about.
        List<DiscordAttachment> files = DiscordAttachments.read(attachments.getValue(), DiscordImages.ENCODER);
        ctx.checkCancelled();
        DiscordWebhookClient.send(url, text, username.getValue(), avatarUrl.getValue(), timeoutSeconds(), files);
    }

    /** The authored timeout, or the client's default when the field is empty. */
    private int timeoutSeconds() {
        Integer seconds = timeout.getValue();
        return seconds == null ? DiscordWebhookClient.DEFAULT_TIMEOUT_SECONDS : seconds;
    }

    @Override
    public void configureInputs() {
        addInput(webhookUrl);
        addInput(message);
        addInput(username);
        addInput(avatarUrl);
        addInput(attachments);
        addInput(timeout);
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
