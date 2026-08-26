package io.github.jaymcole.housegraph.plugins.discord;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One POST to a Discord <a href="https://discord.com/developers/docs/resources/webhook">webhook</a>
 * URL. This is the plain-HTTP side of talking to Discord — no bot login, no gateway, no
 * {@code housegraph-discord}-wide connection to share — so unlike {@link DiscordBot} it needs
 * nothing wired in beyond the webhook URL itself and posts synchronously, the same shape as this
 * repository's other direct-HTTP clients ({@code LocalLlmClient}, {@code ReolinkClient}).
 * <p>
 * The webhook URL is itself the credential: anyone holding it can post as the webhook, with no
 * further authentication. It is never logged and never appears in a thrown message.
 */
public final class DiscordWebhookClient {

    /** Five seconds: a webhook URL that isn't answering at all should fail fast, not hang the run. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
            .build();

    private DiscordWebhookClient() {
    }

    /**
     * Posts {@code content} to a Discord webhook, optionally overriding the display name and/or
     * avatar for this one message.
     *
     * @param webhookUrl     the full webhook URL Discord issued (e.g.
     *                       {@code https://discord.com/api/webhooks/<id>/<token>})
     * @param content        the message text
     * @param username       overrides the webhook's configured name for this message, or null/blank
     *                       to use the webhook's own
     * @param avatarUrl      overrides the webhook's configured avatar for this message, or
     *                       null/blank to use the webhook's own
     * @param timeoutSeconds how long to wait for Discord to answer; a value below 1 is clamped to 1
     * @throws DiscordWebhookException if the URL can't be reached, Discord rejects the request, or
     *                                 the request times out
     */
    public static void send(String webhookUrl, String content, String username, String avatarUrl, int timeoutSeconds) {
        JSONObject body = new JSONObject();
        body.put("content", content);
        if (username != null && !username.isBlank()) {
            body.put("username", username);
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            body.put("avatar_url", avatarUrl);
        }

        URI uri;
        try {
            uri = URI.create(webhookUrl);
        } catch (IllegalArgumentException e) {
            throw new DiscordWebhookException("The Webhook URL is not a valid URL.", e);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) {
            throw new DiscordWebhookException("Discord rejected the webhook message with HTTP "
                    + response.statusCode() + ": " + errorFrom(response.body()));
        }
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new DiscordWebhookException("Discord did not answer the webhook post in time.", e);
        } catch (IOException e) {
            throw new DiscordWebhookException("Could not reach Discord: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            // The engine interrupts process() to cancel a run or to enforce a node timeout. Restore
            // the flag so anything above this still sees a cancelled thread, then fail this pass.
            Thread.currentThread().interrupt();
            throw new DiscordWebhookException("Interrupted while posting to the webhook.", e);
        }
    }

    /** What Discord said went wrong, dug out of a JSON error body ({@code {"message": "..."}}). */
    private static String errorFrom(String body) {
        try {
            JSONObject json = new JSONObject(body == null ? "" : body);
            String message = json.optString("message", "");
            return message.isBlank() ? excerpt(body) : message;
        } catch (JSONException e) {
            return excerpt(body);
        }
    }

    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "(no response body)";
        }
        String trimmed = body.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }
}
