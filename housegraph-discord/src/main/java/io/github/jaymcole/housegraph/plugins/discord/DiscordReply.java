package io.github.jaymcole.housegraph.plugins.discord;

import java.util.List;

/**
 * A one-shot handle for replying to a specific slash-command invocation. Backed by
 * JDA's deferred interaction hook, so it's valid for about 15 minutes after the command
 * was run — long enough for a slow graph to finish and answer. Flows through the graph
 * as a value from the command node to a reply node.
 * <p>
 * The attachment-carrying method is the abstract one and the text-only one delegates to it, so
 * this stays a lambda target while every implementation is obliged to handle files: a reply that
 * quietly dropped the picture it was sent with would look like a reply that worked.
 */
@FunctionalInterface
public interface DiscordReply {

    /**
     * Answers the interaction with {@code text}, carrying {@code attachments}.
     *
     * @param text        the reply body
     * @param attachments files to upload with it; empty for a plain text reply
     */
    void reply(String text, List<DiscordAttachment> attachments);

    /** Answers with text alone. */
    default void reply(String text) {
        reply(text, List.of());
    }
}
