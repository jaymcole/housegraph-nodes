package io.github.jaymcole.housegraph.plugins.alpaca;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for Alpaca, answering per method and path.
 * <p>
 * Testing this library against the real thing is not an option in a build — even the paper API needs
 * somebody's keys — so what is tested is what goes out and what is made of what comes back. Both are
 * answerable by a handler in a few lines, and both are where this library's bugs would actually
 * live: the order payload, the reading of replies that are full of nulls and numbers-as-strings, and
 * the one thing a trading library must never get wrong, which is placing an order it was told not
 * to.
 * <p>
 * <b>One stub serves both of Alpaca's hosts.</b> The trading API and the market-data API differ only
 * by path here ({@code /v2/account} against {@code /v2/stocks/...}), so a session pointed at this
 * stub for both reaches the same handler and the same list of calls — which is what lets a test
 * assert that placing an order took exactly one quote and one POST.
 * <p>
 * <b>Responses are queued per route.</b> A cancel reads the order, deletes it and reads it back, so
 * the same GET has to be able to answer "open" and then "canceled". A route holds a queue and hands
 * out the next answer each time it is asked, repeating the last one forever after.
 * <p>
 * Public because the node tests live in the sibling {@code nodes} package. It is in the test source
 * set, so it is never part of the library or its jar.
 */
public final class StubAlpaca implements AutoCloseable {

    /** One request the stub was asked for, as it arrived. */
    public record Call(String method, String path, String query, String body,
                       String apiKey, String secretKey) {

        /** The request body parsed as JSON, or an empty object for a body that wasn't. */
        public JSONObject json() {
            try {
                return body == null || body.isBlank() ? new JSONObject() : new JSONObject(body);
            } catch (RuntimeException e) {
                return new JSONObject();
            }
        }
    }

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Deque<Response>> routes = new LinkedHashMap<>();
    private final List<Call> calls = new ArrayList<>();

    private StubAlpaca(HttpServer server) {
        this.server = server;
    }

    /** Starts a stub on a free loopback port that answers 404 until something is registered. */
    public static StubAlpaca open() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        StubAlpaca stub = new StubAlpaca(http);
        http.createContext("/", stub::handle);
        http.start();
        return stub;
    }

    /**
     * A stub that will accept a connect and report one paper account - the starting point for every
     * test that is about something other than connecting.
     */
    public static StubAlpaca openConnected() throws IOException {
        StubAlpaca stub = open();
        stub.on("GET", "/v2/account", 200, account("PA123456789"));
        return stub;
    }

    /** A session pointed at this stub for both the trading API and the market-data API. */
    public AlpacaSession session() {
        return new AlpacaSession(address(), address());
    }

    /** A session pointed at this stub and already connected, for tests about something else. */
    public AlpacaSession connectedSession() {
        AlpacaSession session = session();
        session.connect(credentials());
        return session;
    }

    /** Paper credentials, which is what every test that isn't about live trading uses. */
    public static AlpacaCredentials credentials() {
        return new AlpacaCredentials("PKTESTKEYID", "test-secret-key", true);
    }

    /** Queues one answer for a route; several calls queue several answers, used in order. */
    public StubAlpaca on(String method, String path, int status, String body) {
        routes.computeIfAbsent(key(method, path), ignored -> new ArrayDeque<>())
                .add(new Response(status, body));
        return this;
    }

    /** Replaces everything queued for a route with one answer. */
    public StubAlpaca only(String method, String path, int status, String body) {
        routes.remove(key(method, path));
        return on(method, path, status, body);
    }

    /** Every request the stub has been asked for, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The calls to one path, in order - usually to assert that exactly one order was placed. */
    public List<Call> callsTo(String method, String path) {
        return calls.stream()
                .filter(call -> call.method().equals(method) && call.path().equals(path))
                .toList();
    }

    /** The last call to one path, or null if there wasn't one. */
    public Call lastCallTo(String method, String path) {
        List<Call> matching = callsTo(method, path);
        return matching.isEmpty() ? null : matching.get(matching.size() - 1);
    }

    /** The address to point a session at. */
    public String address() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        synchronized (calls) {
            calls.add(new Call(exchange.getRequestMethod(), path, exchange.getRequestURI().getQuery(),
                    body,
                    exchange.getRequestHeaders().getFirst("APCA-API-KEY-ID"),
                    exchange.getRequestHeaders().getFirst("APCA-API-SECRET-KEY")));
        }

        Deque<Response> queued = routes.get(key(exchange.getRequestMethod(), path));
        Response response = queued == null || queued.isEmpty()
                ? new Response(404, "{\"message\": \"no stub for " + exchange.getRequestMethod() + " "
                        + path + "\"}")
                // The last queued answer is kept rather than consumed, so a route registered once
                // answers every call - a node polling an order mid-test shouldn't 404.
                : queued.size() == 1 ? queued.peek() : queued.poll();

        byte[] bytes = response.body() == null
                ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // 204 must not carry a body, and the JDK's server enforces it.
        exchange.sendResponseHeaders(response.status(), response.status() == 204 ? -1 : bytes.length);
        if (response.status() != 204) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }

    // --- Bodies -----------------------------------------------------------------------------

    /** One brokerage account. Alpaca sends money as strings, and this stub does too. */
    public static String account(String accountNumber) {
        return new JSONObject()
                .put("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                .put("account_number", accountNumber)
                .put("status", "ACTIVE")
                .put("currency", "USD")
                .put("cash", "980.10")
                .put("equity", "5250.75")
                .put("last_equity", "5100.25")
                .put("buying_power", "1960.20")
                .put("long_market_value", "4270.65")
                .put("short_market_value", "0")
                .put("trading_blocked", false)
                .put("account_blocked", false)
                .put("shorting_enabled", true)
                .toString();
    }

    /** One holding. */
    public static JSONObject position(String symbol, String qty, String currentPrice) {
        return new JSONObject()
                .put("asset_id", "11111111-2222-3333-4444-555555555555")
                .put("symbol", symbol)
                .put("exchange", "NASDAQ")
                .put("asset_class", "us_equity")
                .put("qty", qty)
                .put("side", "long")
                .put("avg_entry_price", "90.00")
                .put("current_price", currentPrice)
                .put("market_value", "200.00")
                .put("cost_basis", "180.00")
                .put("unrealized_pl", "20.00")
                .put("unrealized_plpc", "0.1111")
                .put("lastday_price", "99.00")
                .put("change_today", "0.0101");
    }

    /** An order in whatever state the test needs it in. */
    public static JSONObject order(String id, String symbol, String side, String status) {
        boolean filled = "filled".equals(status);
        return new JSONObject()
                .put("id", id)
                .put("client_order_id", "housegraph-" + id)
                .put("symbol", symbol)
                .put("side", side)
                .put("status", status)
                .put("qty", "2")
                .put("notional", JSONObject.NULL)
                .put("filled_qty", filled ? "2" : "0")
                .put("filled_avg_price", filled ? "101.50" : JSONObject.NULL)
                .put("type", "market")
                .put("order_type", "market")
                .put("time_in_force", "day")
                .put("limit_price", JSONObject.NULL)
                .put("stop_price", JSONObject.NULL)
                .put("extended_hours", false)
                .put("submitted_at", "2026-09-14T13:30:00Z")
                .put("created_at", "2026-09-14T13:30:00Z")
                .put("filled_at", filled ? "2026-09-14T13:30:02Z" : JSONObject.NULL);
    }

    /**
     * One symbol's snapshot, in the envelope the multi-symbol endpoint uses. Market data comes back
     * as JSON numbers rather than strings, unlike everything on the trading API - which is exactly
     * the sort of thing {@code Json} exists to stop mattering.
     */
    public static String snapshots(String symbol, double lastTrade, double previousClose) {
        JSONObject snapshot = new JSONObject()
                .put("latestTrade", new JSONObject()
                        .put("t", "2026-09-14T19:59:59Z").put("p", lastTrade).put("s", 100))
                .put("latestQuote", new JSONObject()
                        .put("t", "2026-09-14T19:59:59Z")
                        .put("bp", lastTrade - 0.05).put("bs", 3)
                        .put("ap", lastTrade + 0.05).put("as", 4))
                .put("dailyBar", bar("2026-09-14T04:00:00Z", lastTrade, lastTrade))
                .put("prevDailyBar", bar("2026-09-13T04:00:00Z", previousClose, previousClose));
        return new JSONObject().put("snapshots", new JSONObject().put(symbol, snapshot)).toString();
    }

    /** A snapshot response with nothing in it, which is how an unknown ticker comes back. */
    public static String emptySnapshots(String symbol) {
        return new JSONObject()
                .put("snapshots", new JSONObject().put(symbol, new JSONObject()))
                .toString();
    }

    /** One bar, newest-first order being the caller's business. */
    public static JSONObject bar(String timestamp, double open, double close) {
        return new JSONObject()
                .put("t", timestamp)
                .put("o", open)
                .put("h", Math.max(open, close) + 1)
                .put("l", Math.min(open, close) - 1)
                .put("c", close)
                .put("v", 1_000_000)
                .put("n", 5000)
                .put("vw", (open + close) / 2);
    }

    /** A bars response, in the envelope the endpoint uses. */
    public static String bars(String symbol, JSONObject... bars) {
        return new JSONObject()
                .put("bars", new JSONObject().put(symbol, new JSONArray(List.of(bars))))
                .toString();
    }

    /** The market clock. */
    public static String clock(boolean open) {
        return new JSONObject()
                .put("timestamp", "2026-09-14T14:30:00-04:00")
                .put("is_open", open)
                .put("next_open", "2026-09-15T09:30:00-04:00")
                .put("next_close", "2026-09-14T16:00:00-04:00")
                .toString();
    }

    /** An Alpaca error body. */
    public static String error(int code, String message) {
        return new JSONObject().put("code", code).put("message", message).toString();
    }

    /** Wraps bodies in the bare JSON array that Alpaca's trading list endpoints answer with. */
    public static String list(JSONObject... objects) {
        return new JSONArray(List.of(objects)).toString();
    }
}
