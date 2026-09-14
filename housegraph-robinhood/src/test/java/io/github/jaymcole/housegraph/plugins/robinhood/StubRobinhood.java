package io.github.jaymcole.housegraph.plugins.robinhood;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
 * A stand-in for Robinhood, answering per method and path.
 * <p>
 * Testing this library against the real thing is not an option — it would need somebody's
 * credentials, a second factor, and a willingness to have a test suite place orders — so what is
 * tested is what goes out and what is made of what comes back. Both are answerable by a handler in
 * a few lines, and both are where this library's bugs would actually live: the login state machine,
 * the order payload, and the reading of replies that are full of nulls and numbers-as-strings.
 * <p>
 * <b>Responses are queued per route.</b> The login flow is a sequence — the token endpoint answers
 * "MFA required" and then, to the same URL, answers with tokens — so a route holds a queue and
 * hands out the next answer each time it is asked, repeating the last one forever after. That is
 * what makes a two-step login testable at all.
 * <p>
 * Public because the node tests live in the sibling {@code nodes} package. It is in the test source
 * set, so it is never part of the library or its jar.
 */
public final class StubRobinhood implements AutoCloseable {

    /** One request the stub was asked for, as it arrived. */
    public record Call(String method, String path, String query, String body, String authorization) {

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

    private StubRobinhood(HttpServer server) {
        this.server = server;
    }

    /** Starts a stub on a free loopback port that answers 404 until something is registered. */
    public static StubRobinhood open() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        StubRobinhood stub = new StubRobinhood(http);
        http.createContext("/", stub::handle);
        http.start();
        return stub;
    }

    /**
     * A stub that will log a session in on the first try and report one brokerage account - the
     * starting point for every test that is about something other than logging in.
     */
    public static StubRobinhood openLoggedIn() throws IOException {
        StubRobinhood stub = open();
        stub.on("POST", "/oauth2/token/", 200, tokens("access-1", "refresh-1", 86400));
        stub.on("GET", "/accounts/", 200, accounts("123456789"));
        return stub;
    }

    /** A session pointed at this stub. */
    public RobinhoodSession session() {
        return new RobinhoodSession(address());
    }

    /** A session pointed at this stub and already connected, for tests about something else. */
    public RobinhoodSession connectedSession() {
        RobinhoodSession session = session();
        session.connect(credentials(), 5);
        return session;
    }

    /** Credentials with an authenticator seed, which is the setup the stub's login flows assume. */
    public static RobinhoodCredentials credentials() {
        return new RobinhoodCredentials("trader@example.com", "hunter2", "GEZDGNBVGY3TQOJQ");
    }

    /** Queues one answer for a route; several calls queue several answers, used in order. */
    public StubRobinhood on(String method, String path, int status, String body) {
        routes.computeIfAbsent(key(method, path), ignored -> new ArrayDeque<>())
                .add(new Response(status, body));
        return this;
    }

    /** Replaces everything queued for a route with one answer. */
    public StubRobinhood only(String method, String path, int status, String body) {
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
                    body, exchange.getRequestHeaders().getFirst("Authorization")));
        }

        Deque<Response> queued = routes.get(key(exchange.getRequestMethod(), path));
        Response response = queued == null || queued.isEmpty()
                ? new Response(404, "{\"detail\": \"no stub for " + exchange.getRequestMethod() + " "
                        + path + "\"}")
                // The last queued answer is kept rather than consumed, so a route registered once
                // answers every call - a session refreshing its token mid-test shouldn't 404.
                : queued.size() == 1 ? queued.peek() : queued.poll();

        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }

    // --- Bodies -----------------------------------------------------------------------------

    /** A successful token response. */
    public static String tokens(String access, String refresh, int expiresIn) {
        return new JSONObject()
                .put("access_token", access)
                .put("refresh_token", refresh)
                .put("expires_in", expiresIn)
                .put("token_type", "Bearer")
                .toString();
    }

    /** The token endpoint asking for a two-factor code. */
    public static String mfaRequired() {
        return new JSONObject().put("mfa_required", true).put("mfa_type", "app").toString();
    }

    /** The token endpoint asking for the login to be approved in the phone app. */
    public static String verificationWorkflow(String workflowId) {
        return new JSONObject()
                .put("verification_workflow", new JSONObject().put("id", workflowId))
                .toString();
    }

    /** One brokerage account. */
    public static String accounts(String accountNumber) {
        return results(new JSONObject()
                .put("account_number", accountNumber)
                .put("url", "https://stub/accounts/" + accountNumber + "/")
                .put("buying_power", "1250.75")
                .put("cash", "980.10"));
    }

    /** A portfolio valuation. */
    public static String portfolio(String equity, String previousClose) {
        return new JSONObject()
                .put("equity", equity)
                .put("market_value", "4000.00")
                .put("adjusted_equity_previous_close", previousClose)
                .toString();
    }

    /** One symbol's quote. Robinhood sends money as strings, and this stub does too. */
    public static JSONObject quote(String symbol, String lastTrade, String previousClose) {
        return new JSONObject()
                .put("symbol", symbol)
                .put("last_trade_price", lastTrade)
                .put("last_extended_hours_trade_price", JSONObject.NULL)
                .put("bid_price", lastTrade)
                .put("ask_price", lastTrade)
                .put("previous_close", previousClose)
                .put("trading_halted", false);
    }

    /** One tradable instrument. */
    public static String instrument(String symbol, String url) {
        return results(new JSONObject().put("symbol", symbol).put("url", url).put("tradeable", true));
    }

    /** An order in whatever state the test needs it in. */
    public static JSONObject order(String id, String symbol, String side, String state) {
        return new JSONObject()
                .put("id", id)
                .put("instrument", "https://stub/instruments/" + symbol + "/")
                .put("side", side)
                .put("state", state)
                .put("quantity", "2.00000000")
                .put("cumulative_quantity", "filled".equals(state) ? "2.00000000" : "0.00000000")
                .put("average_price", "filled".equals(state) ? "101.50" : JSONObject.NULL)
                .put("price", "105.00")
                .put("time_in_force", "gtc")
                .put("created_at", "2026-09-14T13:30:00.000000Z")
                .put("reject_reason", "rejected".equals(state) ? "Not enough buying power" : JSONObject.NULL);
    }

    /** Wraps bodies in the {@code {"results": [...]}} envelope every Robinhood list endpoint uses. */
    public static String results(JSONObject... objects) {
        JSONObject body = new JSONObject();
        body.put("results", List.of(objects));
        return body.toString();
    }
}
