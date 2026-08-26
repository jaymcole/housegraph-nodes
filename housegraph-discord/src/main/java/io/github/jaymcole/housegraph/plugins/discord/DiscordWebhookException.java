package io.github.jaymcole.housegraph.plugins.discord;

/**
 * Raised for anything that stops a Discord webhook post from going through: the URL can't be
 * reached, Discord rejects it (bad/deleted webhook, malformed payload, rate limit), or the
 * request times out.
 * <p>
 * The message is written to be shown as-is on the node. It never contains the webhook URL
 * itself — that URL is a bearer credential, anyone holding it can post as the webhook — so
 * failures name what went wrong without echoing it back.
 */
public class DiscordWebhookException extends RuntimeException {

    /**
     * @param message what went wrong, safe to show on the canvas
     */
    public DiscordWebhookException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong, safe to show on the canvas
     * @param cause   the underlying failure
     */
    public DiscordWebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}
