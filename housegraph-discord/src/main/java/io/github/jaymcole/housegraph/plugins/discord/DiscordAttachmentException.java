package io.github.jaymcole.housegraph.plugins.discord;

/**
 * Raised when something wired into an Attachments input cannot be sent: a path with no file at
 * it, a value of a kind this library cannot turn into a file, or more files than Discord accepts
 * in one message.
 * <p>
 * <b>Attachments fail the node rather than being dropped.</b> A message that arrives without the
 * picture it was about is worse than no message at all — it reads as if nothing went wrong — so
 * the failure is loud and names the value that caused it.
 */
public class DiscordAttachmentException extends RuntimeException {

    /**
     * @param message what went wrong, safe to show on the canvas
     */
    public DiscordAttachmentException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong, safe to show on the canvas
     * @param cause   the underlying failure
     */
    public DiscordAttachmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
