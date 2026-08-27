package io.github.jaymcole.housegraph.plugins.discord;

import org.json.JSONArray;
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
import java.util.List;

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
        send(webhookUrl, content, username, avatarUrl, timeoutSeconds, List.of());
    }

    /**
     * Posts {@code content} to a Discord webhook, with {@code attachments} uploaded alongside it.
     * <p>
     * <b>Files change the wire format, not just the body.</b> With none, this is the one JSON POST
     * above. With any, Discord requires {@code multipart/form-data} — the message becomes a
     * {@code payload_json} part and each file a {@code files[n]} part — so the two shapes are
     * built by {@link DiscordMultipart} and chosen between here.
     *
     * @param webhookUrl     the full webhook URL Discord issued
     * @param content        the message text
     * @param username       a per-message name override, or null/blank for the webhook's own
     * @param avatarUrl      a per-message avatar override, or null/blank for the webhook's own
     * @param timeoutSeconds how long to wait for Discord to answer; below 1 is clamped to 1
     * @param attachments    files to upload; empty sends the plain JSON shape
     * @throws DiscordWebhookException    if the URL can't be reached, Discord rejects the request,
     *                                    or the request times out
     * @throws DiscordAttachmentException if a file to upload cannot be read
     */
    public static void send(String webhookUrl, String content, String username, String avatarUrl,
                            int timeoutSeconds, List<DiscordAttachment> attachments) {
        JSONObject body = new JSONObject();
        body.put("content", content);
        if (username != null && !username.isBlank()) {
            body.put("username", username);
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            body.put("avatar_url", avatarUrl);
        }
        if (!attachments.isEmpty()) {
            // Discord matches these ids to the files[n] parts, and uses the filename it finds here
            // rather than the one on the part. Declaring them is what makes an upload show up named
            // as intended instead of as "unknown".
            JSONArray declared = new JSONArray();
            for (int i = 0; i < attachments.size(); i++) {
                declared.put(new JSONObject().put("id", i).put("filename", attachments.get(i).name()));
            }
            body.put("attachments", declared);
        }

        URI uri;
        try {
            uri = URI.create(webhookUrl);
        } catch (IllegalArgumentException e) {
            throw new DiscordWebhookException("The Webhook URL is not a valid URL.", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        if (attachments.isEmpty()) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else {
            DiscordMultipart multipart = DiscordMultipart.of(body.toString(), attachments);
            builder.header("Content-Type", multipart.contentType())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()));
        }

        HttpResponse<String> response = send(builder.build());
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
