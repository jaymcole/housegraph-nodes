package io.github.jaymcole.housegraph.plugins.robinhood;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One request to Robinhood, and a sentence about it when it doesn't work.
 * <p>
 * <b>The error handling is the reason this is its own class.</b> Robinhood reports a problem four
 * different ways depending on which endpoint refused — {@code detail} for a throttle or a rejected
 * order, {@code error_description} for OAuth, {@code non_field_errors} for a malformed order, and
 * a per-field array ({@code {"quantity": ["Order quantity must be positive"]}}) for a validation
 * failure — and a node that reported "HTTP 400" for all four would be telling its reader nothing
 * they could act on. {@link #describe} finds whichever one is present, so
 * "Robinhood rejected the order: You can only purchase 1.00 shares of this security" reaches the
 * node's status line intact.
 *
 * <h2>What never reaches a message</h2>
 * The request body is never logged or quoted into an exception: it carries passwords, MFA codes and
 * refresh tokens. Only the method, the URL and what Robinhood said back are ever repeated, and the
 * URLs here carry no secrets.
 *
 * <h2>Nothing retries</h2>
 * Deliberately, and this is a trading library, so it is worth saying why: a POST to
 * {@code /orders/} that timed out may well have been received, and a client that helpfully sent it
 * again could buy the same stock twice. Every order therefore carries a {@code ref_id}
 * (see {@link Orders#payload}), which is Robinhood's own idempotency key, and re-sending is left to
 * a graph that has decided it wants to - where a person can see it happening.
 */
final class RobinhoodHttp {

    private static final Logger log = Log.get(RobinhoodHttp.class);

    /**
     * Shared across every session and node in this library: it pools connections, and a graph
     * polling an order's state every few seconds should not be opening a new TLS session each time.
     * The connect timeout is short because a machine that is offline should say so at once rather
     * than waiting out the read timeout.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Long enough for a slow market-data call, short enough that a wedged call frees the node. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private RobinhoodHttp() {
    }

    /**
     * Headers every request carries. Robinhood answers unadorned requests inconsistently - some
     * endpoints 403 without an {@code Accept} it recognises - so these are sent on every call.
     * <p>
     * <b>Two headers the Python clients send are deliberately absent.</b> {@code Accept-Encoding}
     * because {@code java.net.http} advertises no compression and does not decompress a response,
     * so asking for gzip would hand this library a body it cannot read; and {@code Connection}
     * because {@code java.net.http} owns connection reuse itself and throws
     * {@code IllegalArgumentException} for any attempt to set it.
     */
    static Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-Robinhood-API-Version", RobinhoodApi.API_VERSION);
        headers.put("User-Agent", "HouseGraph-Robinhood/1.0");
        return headers;
    }

    /** A GET, with {@code headers} on top of {@link #baseHeaders()}. */
    static JSONObject get(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        return send(builder, headers, "GET", url);
    }

    /** A POST of {@code body} as JSON, with {@code headers} on top of {@link #baseHeaders()}. */
    static JSONObject post(String url, JSONObject body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        return send(builder, headers, "POST", url);
    }

    private static JSONObject send(HttpRequest.Builder builder, Map<String, String> headers,
                                   String method, String url) {
        baseHeaders().forEach(builder::header);
        if (headers != null) {
            headers.forEach(builder::header);
        }

        HttpResponse<String> response;
        try {
            response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new RobinhoodException("Could not reach Robinhood at " + host(url)
                    + " - this machine looks to be offline.", e);
        } catch (HttpTimeoutException e) {
            throw new RobinhoodException("Robinhood did not answer " + method + " " + path(url)
                    + " within " + REQUEST_TIMEOUT.toSeconds() + " seconds.", e);
        } catch (IOException e) {
            throw new RobinhoodException("The call to Robinhood (" + method + " " + path(url)
                    + ") failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobinhoodException("The call to Robinhood (" + method + " " + path(url)
                    + ") was interrupted.", e);
        }

        JSONObject parsed = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new RobinhoodException(
                    describe(response.statusCode(), parsed, response.body(), method, url));
        }
        log.debug("{} {} answered {}", method, path(url), response.statusCode());
        return parsed;
    }

    /**
     * The response body as an object. A body that is empty (a cancel answers {@code {}}, sometimes
     * nothing at all) or is a bare array becomes an empty object rather than a parse failure: the
     * caller reads named fields out of it and gets null for each, which is the same answer.
     */
    private static JSONObject parse(String body) {
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * What to tell the person looking at the node. Tries each of the four shapes Robinhood uses for
     * "no", in the order of how specific they are, and falls back to naming the status code when
     * the body is something new.
     *
     * @param status the HTTP status
     * @param parsed the body as an object, possibly empty
     * @param raw    the body as it arrived, used only to say something when {@code parsed} is empty
     * @param method the HTTP method, for the fallback message
     * @param url    the URL, for the fallback message - never carries a secret
     * @return a sentence safe to put on a status line
     */
    static String describe(int status, JSONObject parsed, String raw, String method, String url) {
        String detail = firstMessage(parsed);
        if (status == 401 && detail == null) {
            return "Robinhood rejected the request as unauthenticated - the session's token is no "
                    + "longer valid. Connect the Robinhood Account node again.";
        }
        if (status == 429) {
            return "Robinhood is rate-limiting this account" + (detail == null ? "." : ": " + detail);
        }
        if (detail != null) {
            return "Robinhood answered HTTP " + status + ": " + detail;
        }
        String body = raw == null ? "" : raw.trim();
        if (body.length() > 200) {
            body = body.substring(0, 200) + "...";
        }
        return "Robinhood answered HTTP " + status + " to " + method + " " + path(url)
                + (body.isEmpty() ? " with an empty body." : ": " + body);
    }

    /** The first human-readable complaint in a Robinhood error body, or null if there is none. */
    private static String firstMessage(JSONObject body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        for (String key : new String[]{"detail", "error_description", "message"}) {
            String value = Json.string(body, key);
            if (value != null) {
                return value;
            }
        }
        // Validation failures: {"quantity": ["..."]} or {"non_field_errors": ["..."]}. Read
        // non_field_errors first when present, then any other array of strings, so the message
        // names the field it came from.
        String nonField = firstOfArray(body, "non_field_errors");
        if (nonField != null) {
            return nonField;
        }
        for (String key : body.keySet()) {
            String value = firstOfArray(body, key);
            if (value != null) {
                return "non_field_errors".equals(key) ? value : key + ": " + value;
            }
        }
        // {"error": "invalid_grant"} on its own is a machine code, not a sentence - last resort.
        return Json.string(body, "error");
    }

    private static String firstOfArray(JSONObject body, String key) {
        if (!body.has(key) || body.isNull(key) || body.optJSONArray(key) == null
                || body.optJSONArray(key).isEmpty()) {
            return null;
        }
        Object first = body.optJSONArray(key).opt(0);
        return first == null ? null : first.toString();
    }

    private static URI uri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new RobinhoodException("Not a usable Robinhood URL: " + url, e);
        }
    }

    /** The URL without its query string, for messages - Robinhood's paths carry no secrets. */
    private static String path(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }
}
