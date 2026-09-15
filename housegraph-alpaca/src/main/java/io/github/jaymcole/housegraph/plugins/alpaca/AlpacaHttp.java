package io.github.jaymcole.housegraph.plugins.alpaca;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONArray;
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
import java.util.List;
import java.util.Map;

/**
 * One request to Alpaca, and a sentence about it when it doesn't work.
 *
 * <h2>The error handling is the reason this is its own class</h2>
 * Alpaca answers a refusal with {@code {"code": 40310000, "message": "..."}}, and the numeric code
 * is the interesting half: several of them mean something a person can act on but the message alone
 * doesn't say so. {@link #describe} turns the ones that bite into a sentence with the fix in it —
 * a 403 from the data API means "this key has no subscription to that feed", not "forbidden"; a 403
 * from the trading API usually means paper keys pointed at the live host, or the reverse. Reporting
 * "HTTP 403" for both would be telling the reader nothing they could do anything about.
 *
 * <h2>What never reaches a message</h2>
 * The API key and secret travel in headers and are never logged, never quoted into an exception,
 * and never included in the request line this class repeats back. Only the method, the URL and what
 * Alpaca said are ever shown, and the URLs here carry no secrets.
 *
 * <h2>Nothing retries</h2>
 * Deliberately, and this is a trading library, so it is worth saying why: a POST to {@code /v2/orders}
 * that timed out may well have been received, and a client that helpfully sent it again could buy
 * the same stock twice. Every order therefore carries a {@code client_order_id}
 * (see {@link Orders#payload}), which Alpaca requires to be unique — a re-sent order is refused
 * rather than duplicated — and deciding to send again is left to a graph, where a person can see it
 * happening.
 */
final class AlpacaHttp {

    private static final Logger log = Log.get(AlpacaHttp.class);

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

    private AlpacaHttp() {
    }

    /**
     * Headers every request carries.
     * <p>
     * <b>{@code Accept-Encoding} is deliberately absent</b>: {@code java.net.http} advertises no
     * compression and does not decompress a response, so asking for gzip would hand this library a
     * body it cannot read.
     */
    static Map<String, String> baseHeaders() {
        return Map.of("Accept", "application/json", "User-Agent", AlpacaApi.USER_AGENT);
    }

    /** A GET whose body is a JSON object. */
    static JSONObject getObject(String url, Map<String, String> headers) {
        return asObject(send(request(url).GET(), headers, "GET", url));
    }

    /**
     * A GET whose body is a JSON array — which is what {@code /v2/positions} and {@code /v2/orders}
     * answer, where the market-data endpoints answer an object with the array inside it.
     *
     * @return the objects in the array, never null
     */
    static List<JSONObject> getList(String url, Map<String, String> headers) {
        return Json.array(asArray(send(request(url).GET(), headers, "GET", url)));
    }

    /** A POST of {@code body} as JSON. */
    static JSONObject postObject(String url, JSONObject body, Map<String, String> headers) {
        HttpRequest.Builder builder = request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        return asObject(send(builder, headers, "POST", url));
    }

    /**
     * A DELETE. Alpaca answers a successful order cancellation with {@code 204 No Content} and a
     * position closure with the liquidating order, so the body may legitimately be empty — which
     * reads here as an empty object rather than as a failure.
     */
    static JSONObject deleteObject(String url, Map<String, String> headers) {
        return asObject(send(request(url).DELETE(), headers, "DELETE", url));
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(uri(url)).timeout(REQUEST_TIMEOUT);
    }

    /**
     * Sends, and returns the body text of a 2xx.
     *
     * @throws AlpacaException for anything else, with a message from {@link #describe}
     */
    private static String send(HttpRequest.Builder builder, Map<String, String> headers,
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
            throw new AlpacaException("Could not reach Alpaca at " + host(url)
                    + " - this machine looks to be offline.", e);
        } catch (HttpTimeoutException e) {
            throw new AlpacaException("Alpaca did not answer " + method + " " + path(url)
                    + " within " + REQUEST_TIMEOUT.toSeconds() + " seconds.", e);
        } catch (IOException e) {
            throw new AlpacaException("The call to Alpaca (" + method + " " + path(url)
                    + ") failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AlpacaException("The call to Alpaca (" + method + " " + path(url)
                    + ") was interrupted.", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new AlpacaException(
                    describe(response.statusCode(), response.body(), method, url));
        }
        log.debug("{} {} answered {}", method, path(url), response.statusCode());
        return response.body();
    }

    /**
     * The body as an object. A body that is empty (a 204 from a cancel) becomes an empty object
     * rather than a parse failure: the caller reads named fields out of it and gets null for each,
     * which is the same answer.
     */
    private static JSONObject asObject(String body) {
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** The body as an array, or null when it wasn't one - which the caller reads as "nothing". */
    private static JSONArray asArray(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return new JSONArray(body);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * What to tell the person looking at the node.
     * <p>
     * Alpaca's own {@code message} is used wherever there is one — it is usually the clearest
     * statement of the problem, e.g. "insufficient buying power" — with the cases where the message
     * alone is not actionable given a sentence of their own.
     *
     * @param status the HTTP status
     * @param raw    the body as it arrived
     * @param method the HTTP method, for the fallback message
     * @param url    the URL, for the fallback message - never carries a secret
     * @return a sentence safe to put on a status line
     */
    static String describe(int status, String raw, String method, String url) {
        JSONObject parsed = asObject(raw);
        String message = Json.string(parsed, "message");
        boolean data = isDataHost(url);

        if (status == 401 || (status == 403 && !data)) {
            return "Alpaca rejected these API keys" + (message == null ? "" : " (" + message + ")")
                    + ". The most common cause is the wrong pair for the host: paper keys only work "
                    + "with Paper Trading switched on, and live keys only with it switched off.";
        }
        if (status == 403) {
            return "Alpaca would not serve this market data with these keys"
                    + (message == null ? "" : ": " + message)
                    + ". The consolidated feed (sip) needs a paid market-data subscription; leave "
                    + "Feed on iex if this account has none.";
        }
        if (status == 404) {
            return "Alpaca has no record of what " + method + " " + path(url) + " asked for"
                    + (message == null ? "." : ": " + message);
        }
        if (status == 422) {
            // Alpaca's own validation: the message names the field, and it is the only place the
            // reason appears - an order refused here never becomes an order at all.
            return "Alpaca would not accept this request"
                    + (message == null ? " and did not say why." : ": " + message);
        }
        if (status == 429) {
            return "Alpaca is rate-limiting this account (200 requests a minute)"
                    + (message == null ? "." : ": " + message)
                    + " Slow the trigger driving this node down.";
        }
        if (message != null) {
            return "Alpaca answered HTTP " + status + ": " + message;
        }
        String body = raw == null ? "" : raw.trim();
        if (body.length() > 200) {
            body = body.substring(0, 200) + "...";
        }
        return "Alpaca answered HTTP " + status + " to " + method + " " + path(url)
                + (body.isEmpty() ? " with an empty body." : ": " + body);
    }

    /** Whether a URL is a market-data one, which decides what a 403 means. */
    private static boolean isDataHost(String url) {
        return url != null && url.contains("/v2/stocks/");
    }

    private static URI uri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new AlpacaException("Not a usable Alpaca URL: " + url, e);
        }
    }

    /** The URL without its query string, for messages - Alpaca's paths carry no secrets. */
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
