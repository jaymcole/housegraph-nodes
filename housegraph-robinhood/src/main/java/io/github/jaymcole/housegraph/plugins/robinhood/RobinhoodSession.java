package io.github.jaymcole.housegraph.plugins.robinhood;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One logged-in Robinhood account, for as long as the graph wants it: the login, the token behind
 * every later call, and the calls themselves.
 *
 * <h2>Why the session is an object and not a node</h2>
 * A login is a <em>connection</em>, not an action — it is acquired once, refreshed in the
 * background, and used by many nodes — so it lives here and is owned by one resource node
 * ({@code RobinhoodAccountNode}), exactly as a database connection or a Discord gateway does in the
 * neighbouring libraries. Every other node in this library takes a session as an input and does one
 * thing with it. Nothing in this class schedules anything: <em>when</em> to quote, place or check is
 * a trigger's business.
 *
 * <h2>Logging in</h2>
 * Robinhood's password grant can answer four ways, and all four are handled in
 * {@link #acquireTokens}:
 * <ul>
 *   <li><b>Tokens.</b> Done.</li>
 *   <li><b>{@code mfa_required}.</b> Answered with a code generated from the {@code MFA Secret}
 *       seed ({@link Totp}). Without a seed there is nobody to ask, so this fails with a message
 *       saying so.</li>
 *   <li><b>{@code verification_workflow}.</b> The "is this you?" prompt in the phone app. This
 *       library starts that workflow and waits for the tap — see {@link #approveDevice} — which is
 *       the one interactive path that can be automated, because the person approves on their phone
 *       rather than typing something back into the graph.</li>
 *   <li><b>A {@code challenge} to answer by SMS or email.</b> There is no way for a node to read a
 *       texted code, so this one fails with the fix: turn on app-based two-factor authentication
 *       and give the node its seed.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * The token is read by every node on whatever thread the engine runs them on, and replaced by
 * whichever one first notices it is stale. Reads are of {@code volatile} fields; anything that
 * <em>changes</em> the login — connecting, refreshing, disconnecting — holds {@link #lock}, so two
 * nodes noticing an expiry at the same moment produce one refresh rather than two.
 */
public final class RobinhoodSession {

    private static final Logger log = Log.get(RobinhoodSession.class);

    /** Refresh this long before the token actually expires, so no call races the boundary. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    /** How often to ask whether the approval prompt has been tapped yet. */
    private static final Duration APPROVAL_POLL = Duration.ofSeconds(3);

    /** Robinhood's orders endpoint pages; nothing here wants more than one page of them. */
    private static final int MAX_ORDERS = 50;

    private final String baseUrl;
    private final Instruments instruments = new Instruments(this);
    private final Object lock = new Object();

    private volatile RobinhoodCredentials credentials;
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile Instant expiresAt;
    private volatile String accountNumber;
    private volatile String accountUrl;
    private volatile int approvalTimeoutSeconds = 120;

    /** A session pointed at the real Robinhood. */
    public RobinhoodSession() {
        this(RobinhoodApi.BASE_URL);
    }

    /**
     * A session pointed somewhere else — the seam the tests use to put a stub in Robinhood's place.
     * Not reachable from a node: there is no input for it, deliberately, because "which server is
     * my broker" is not a question a graph should be able to get wrong.
     *
     * @param baseUrl the API root, with no trailing slash
     */
    RobinhoodSession(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // --- Lifecycle --------------------------------------------------------------------------

    /**
     * Logs in, and reads which account the login can trade.
     * <p>
     * Idempotent in the way that matters: connecting an already-connected session logs in again and
     * replaces the tokens, which is what a Connect pressed after a password change has to do.
     *
     * @param newCredentials         the login
     * @param approvalTimeoutSeconds how long to wait for an app approval tap before giving up
     * @throws RobinhoodException if the login could not be completed, with a message naming why
     */
    public void connect(RobinhoodCredentials newCredentials, int approvalTimeoutSeconds) {
        RobinhoodCredentials checked = newCredentials.validate();
        synchronized (lock) {
            this.credentials = checked;
            this.approvalTimeoutSeconds = Math.max(0, approvalTimeoutSeconds);
            acquireTokens(checked);
            loadAccount();
        }
        log.info("Connected to Robinhood account {}", accountNumber);
    }

    /**
     * Hands the refresh token back so it stops working, and forgets everything about the login.
     * <p>
     * A revoke that fails is logged and otherwise ignored: the local half — forgetting the tokens —
     * is the half that must happen, and a Disconnect that threw because Robinhood was unreachable
     * would leave a node showing "connected" with no way to get out of it.
     */
    public void disconnect() {
        synchronized (lock) {
            String token = refreshToken;
            if (token != null) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("client_id", RobinhoodApi.CLIENT_ID);
                    body.put("token", token);
                    RobinhoodHttp.post(RobinhoodApi.revoke(baseUrl), body, RobinhoodHttp.baseHeaders());
                } catch (RobinhoodException e) {
                    log.debug("Revoking the Robinhood token failed, forgetting it anyway: {}",
                            e.getMessage());
                }
            }
            accessToken = null;
            refreshToken = null;
            expiresAt = null;
            accountNumber = null;
            accountUrl = null;
            credentials = null;
        }
    }

    /** Whether this session holds a token it believes is usable. */
    public boolean isConnected() {
        return accessToken != null;
    }

    /** The brokerage account number this session trades, or null before a connect. */
    public String accountNumber() {
        return accountNumber;
    }

    /** One line for a node's status label. */
    public String statusText() {
        if (!isConnected()) {
            return "Not connected";
        }
        return "Connected - account " + (accountNumber == null ? "(unknown)" : accountNumber);
    }

    /** The API root this session talks to. */
    String baseUrl() {
        return baseUrl;
    }

    // --- Reading the account ----------------------------------------------------------------

    /**
     * What the account is worth and what it can spend, from {@code /accounts/} and
     * {@code /portfolios/} together.
     *
     * @return the summary
     * @throws RobinhoodException if either call failed
     */
    public AccountSummary accountSummary() {
        JSONObject account = Json.firstResult(getJson(RobinhoodApi.accounts(baseUrl)));
        if (account == null) {
            throw new RobinhoodException("Robinhood returned no brokerage account for this login.");
        }
        String number = Json.stringOr(account, "account_number", accountNumber);
        JSONObject portfolio = null;
        if (number != null) {
            portfolio = getJson(RobinhoodApi.portfolio(baseUrl, number));
        }
        return AccountSummary.from(number, account, portfolio);
    }

    /**
     * Everything the account holds.
     *
     * @param withPrices whether to price the holdings, which costs one extra call for the whole list
     *                   (not one per holding - see {@link RobinhoodApi#quotes})
     * @return the holdings, in the order Robinhood listed them, never null
     * @throws RobinhoodException if the positions could not be read
     */
    public List<Position> positions(boolean withPrices) {
        List<JSONObject> results = Json.objects(getJson(RobinhoodApi.positions(baseUrl)), "results");

        // Resolve every symbol first, so the prices can be asked for in one call rather than one
        // per holding. An instrument whose symbol cannot be read is dropped: it cannot be priced,
        // named or acted on, so a row for it would be a row of blanks.
        Map<JSONObject, String> symbols = new LinkedHashMap<>();
        for (JSONObject result : results) {
            String symbol = instruments.symbolFor(Position.instrumentUrlOf(result));
            if (symbol != null) {
                symbols.put(result, symbol);
            }
        }

        Map<String, Quote> prices = withPrices
                ? quotes(new ArrayList<>(new LinkedHashSet<>(symbols.values())))
                : Map.of();

        List<Position> positions = new ArrayList<>();
        for (Map.Entry<JSONObject, String> entry : symbols.entrySet()) {
            Quote quote = prices.get(entry.getValue());
            positions.add(Position.from(entry.getValue(), entry.getKey(),
                    quote == null ? null : quote.price()));
        }
        return positions;
    }

    /**
     * One symbol's current prices.
     *
     * @param symbol the ticker
     * @return the quote
     * @throws RobinhoodException if the symbol is unknown or the call failed
     */
    public Quote quote(String symbol) {
        String ticker = Instruments.normalise(symbol);
        JSONObject body = getJson(RobinhoodApi.quote(baseUrl, ticker));
        if (Json.string(body, "symbol") == null && Json.number(body, "last_trade_price") == null) {
            throw new RobinhoodException("Robinhood returned no quote for '" + ticker
                    + "'. Check the ticker.");
        }
        return Quote.from(ticker, body);
    }

    /**
     * Several symbols' prices in one call.
     * <p>
     * A symbol Robinhood does not know is simply absent from the result rather than failing the
     * call, because the callers are list-pricing ones: one bad ticker in a portfolio should not
     * cost the prices of the other nine.
     *
     * @param symbols the tickers; an empty list makes no call at all
     * @return quotes by ticker, in the order asked for
     */
    public Map<String, Quote> quotes(List<String> symbols) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) {
            return quotes;
        }
        Set<String> tickers = new LinkedHashSet<>();
        for (String symbol : symbols) {
            tickers.add(Instruments.normalise(symbol));
        }
        JSONObject body = getJson(RobinhoodApi.quotes(baseUrl, String.join(",", tickers)));
        for (JSONObject result : Json.objects(body, "results")) {
            String symbol = Json.string(result, "symbol");
            if (symbol != null) {
                quotes.put(symbol, Quote.from(symbol, result));
            }
        }
        return quotes;
    }

    // --- Orders -----------------------------------------------------------------------------

    /**
     * Works an authored order out in full — the instrument, the share count, the price it would go
     * in at — <b>without placing it</b>.
     * <p>
     * This is the half that can be looked at before anything is spent: a dry run stops here, and so
     * does an order that a spending cap turns down. It costs two reads (an instrument lookup, which
     * is usually cached, and a quote) and changes nothing.
     *
     * @param request the authored order
     * @return the prepared order, ready for {@link #submit}
     * @throws RobinhoodException if the order is not placeable as authored, or a lookup failed
     */
    public PreparedOrder prepareOrder(OrderRequest request) {
        OrderRequest checked = request.validate();
        requireAccount();
        String instrumentUrl = instruments.urlFor(checked.symbol());

        // Priced even for a limit order: the estimate a cap is checked against and the number a dry
        // run reports both want to know what the thing currently costs, not only what was asked for.
        Double price = null;
        try {
            price = quote(checked.symbol()).price();
        } catch (RobinhoodException e) {
            if (checked.type() == OrderType.MARKET || checked.isAmountBased()) {
                throw e;
            }
            log.debug("No quote for {} while preparing a {} order: {}",
                    checked.symbol(), checked.type().label(), e.getMessage());
        }

        BigDecimal quantity = Orders.quantityFor(checked, price);
        JSONObject payload = Orders.payload(checked, accountUrl, instrumentUrl, quantity, price);
        return new PreparedOrder(checked, instrumentUrl, quantity, price, payload);
    }

    /**
     * Sends a prepared order. <b>This is the call that spends money.</b>
     * <p>
     * It does not retry — see {@link RobinhoodHttp} — and the order it sends carries the
     * {@code ref_id} that {@link #prepareOrder} generated, so sending the <em>same</em>
     * {@link PreparedOrder} twice is one order to Robinhood rather than two.
     *
     * @param prepared the order from {@link #prepareOrder}
     * @return the order as Robinhood recorded it, which may already be rejected
     * @throws RobinhoodException if Robinhood refused the request outright
     */
    public Order submit(PreparedOrder prepared) {
        JSONObject body = postJson(RobinhoodApi.orders(baseUrl), prepared.payload());
        Order order = Order.from(body, prepared.request().symbol());
        log.info("Placed order {} - {}", order.id(), order.describe());
        return order;
    }

    /**
     * One order as it stands now.
     *
     * @param orderId the id a placement answered with
     * @return the order
     * @throws RobinhoodException if there is no such order, or the call failed
     */
    public Order order(String orderId) {
        String id = requireOrderId(orderId);
        JSONObject body = getJson(RobinhoodApi.order(baseUrl, id));
        return Order.from(body, instruments.symbolFor(Order.instrumentUrlOf(body)));
    }

    /**
     * The most recent orders on the account, newest first.
     *
     * @param limit how many to return, capped at {@value #MAX_ORDERS}
     * @return the orders, never null
     * @throws RobinhoodException if the call failed
     */
    public List<Order> recentOrders(int limit) {
        int wanted = Math.min(Math.max(limit, 1), MAX_ORDERS);
        List<JSONObject> results = Json.objects(getJson(RobinhoodApi.orders(baseUrl)), "results");
        List<Order> orders = new ArrayList<>();
        for (JSONObject result : results) {
            if (orders.size() == wanted) {
                break;
            }
            orders.add(Order.from(result, instruments.symbolFor(Order.instrumentUrlOf(result))));
        }
        return orders;
    }

    /**
     * Asks Robinhood to cancel an order, and reports where it ended up.
     * <p>
     * <b>An order that has already finished is not an error here.</b> It answers with that order
     * untouched, and the caller reads {@link Order#state()} to see that there was nothing to
     * cancel — which is a different outcome from a cancel that failed, and one
     * {@code CancelOrderNode} gives its own flow port.
     *
     * @param orderId the order to cancel
     * @return the order after the attempt
     * @throws RobinhoodException if the order could not be read, or Robinhood refused the cancel
     */
    public Order cancel(String orderId) {
        Order current = order(orderId);
        if (current.state().isTerminal()) {
            return current;
        }
        postJson(RobinhoodApi.cancelOrder(baseUrl, requireOrderId(orderId)), new JSONObject());
        // Cancelling is a request, not an act: Robinhood answers {} and moves the order to
        // "canceled" once the exchange agrees. Read it back so the caller reports the state that
        // actually resulted rather than the one that was asked for.
        return order(orderId);
    }

    // --- Authenticated calls ------------------------------------------------------------------

    /**
     * A GET with this session's token, refreshing it first if it is close to expiring.
     *
     * @param url an absolute Robinhood URL
     * @return the response body
     */
    JSONObject getJson(String url) {
        return RobinhoodHttp.get(url, authHeaders());
    }

    /**
     * A POST with this session's token, refreshing it first if it is close to expiring.
     *
     * @param url  an absolute Robinhood URL
     * @param body the request body
     * @return the response body
     */
    JSONObject postJson(String url, JSONObject body) {
        return RobinhoodHttp.post(url, body, authHeaders());
    }

    private Map<String, String> authHeaders() {
        ensureFresh();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        return headers;
    }

    /**
     * Makes sure the access token will still be valid for the call about to be made, renewing it if
     * not. Called before every request; almost always a volatile read and a comparison.
     */
    private void ensureFresh() {
        if (accessToken == null) {
            throw new RobinhoodException("This Robinhood Account is not connected. Press Connect on "
                    + "the node, or wire something into its Connect port.");
        }
        if (expiresAt == null || Instant.now().isBefore(expiresAt.minus(REFRESH_MARGIN))) {
            return;
        }
        synchronized (lock) {
            // Re-checked inside the lock: several nodes can notice one expiry at the same moment,
            // and only the first of them should do anything about it.
            if (expiresAt == null || Instant.now().isBefore(expiresAt.minus(REFRESH_MARGIN))) {
                return;
            }
            refreshTokens();
        }
    }

    /** Swaps the refresh token for a new access token, falling back to a full login if it won't. */
    private void refreshTokens() {
        String token = refreshToken;
        if (token != null) {
            try {
                JSONObject body = new JSONObject();
                body.put("client_id", RobinhoodApi.CLIENT_ID);
                body.put("grant_type", "refresh_token");
                body.put("refresh_token", token);
                body.put("scope", "internal");
                body.put("expires_in", RobinhoodApi.TOKEN_LIFETIME_SECONDS);
                if (credentials != null) {
                    body.put("device_token", credentials.deviceToken());
                }
                if (adoptTokens(RobinhoodHttp.post(RobinhoodApi.token(baseUrl), body,
                        RobinhoodHttp.baseHeaders()))) {
                    log.debug("Refreshed the Robinhood access token");
                    return;
                }
            } catch (RobinhoodException e) {
                log.debug("Refreshing the Robinhood token failed, logging in again: {}", e.getMessage());
            }
        }
        RobinhoodCredentials current = credentials;
        if (current == null) {
            accessToken = null;
            throw new RobinhoodException("The Robinhood session expired and there are no credentials "
                    + "left to log in with. Press Connect on the Robinhood Account node.");
        }
        acquireTokens(current);
    }

    // --- Logging in ---------------------------------------------------------------------------

    /**
     * Drives the password grant to tokens, answering whichever of Robinhood's four replies comes
     * back. Bounded at three passes: each branch either finishes, throws, or clears one obstacle,
     * so a fourth would mean Robinhood is asking for something in a loop and the honest answer is
     * to stop rather than to keep re-sending a password.
     */
    private void acquireTokens(RobinhoodCredentials creds) {
        String mfaCode = null;
        boolean approvalAttempted = false;

        for (int attempt = 0; attempt < 3; attempt++) {
            JSONObject response = RobinhoodHttp.post(RobinhoodApi.token(baseUrl),
                    passwordPayload(creds, mfaCode), RobinhoodHttp.baseHeaders());

            if (adoptTokens(response)) {
                return;
            }
            if (Json.bool(response, "mfa_required", false)) {
                if (mfaCode != null) {
                    throw new RobinhoodException("Robinhood rejected the two-factor code. Check that "
                            + "MFA Secret is the base32 seed from Robinhood's own authenticator "
                            + "setup, and that this machine's clock is right.");
                }
                if (!creds.hasMfaSeed()) {
                    throw new RobinhoodException("Robinhood wants a two-factor code ("
                            + Json.stringOr(response, "mfa_type", "app") + ") and this node has no "
                            + "way to produce one. Put the base32 seed from Robinhood's "
                            + "authenticator-app setup into MFA Secret.");
                }
                mfaCode = creds.mfaCode();
                continue;
            }

            JSONObject workflow = Json.object(response, "verification_workflow");
            if (workflow != null) {
                if (approvalAttempted) {
                    throw new RobinhoodException("Robinhood asked for device approval again straight "
                            + "after it was given. Open the Robinhood app, approve the login there, "
                            + "and press Connect again.");
                }
                approveDevice(Json.string(workflow, "id"), creds);
                approvalAttempted = true;
                continue;
            }

            JSONObject challenge = Json.object(response, "challenge");
            if (challenge != null) {
                throw new RobinhoodException("Robinhood wants a code sent by "
                        + Json.stringOr(challenge, "type", "SMS or email")
                        + ", which a graph has no way to read. Switch this account to an "
                        + "authenticator app in Robinhood's security settings and put the seed it "
                        + "shows into MFA Secret.");
            }

            throw new RobinhoodException("Robinhood did not log this session in, and did not say why "
                    + "(no access token, no MFA request, no approval workflow). This usually means "
                    + "the login endpoint has changed - see the library's design notes.");
        }
        throw new RobinhoodException("Could not log in to Robinhood: it kept asking for something "
                + "more after three attempts.");
    }

    /** The password grant body, with a two-factor code when one has been generated. */
    private JSONObject passwordPayload(RobinhoodCredentials creds, String mfaCode) {
        JSONObject body = new JSONObject();
        body.put("client_id", RobinhoodApi.CLIENT_ID);
        body.put("grant_type", "password");
        body.put("scope", "internal");
        body.put("expires_in", RobinhoodApi.TOKEN_LIFETIME_SECONDS);
        body.put("username", creds.username().trim());
        body.put("password", creds.password());
        body.put("device_token", creds.deviceToken());
        body.put("try_passkeys", false);
        body.put("token_request_path", "/login");
        body.put("create_read_only_secondary_token", true);
        if (mfaCode != null) {
            body.put("mfa_code", mfaCode);
        }
        return body;
    }

    /**
     * Takes the tokens out of a token-endpoint reply.
     *
     * @return true if there was an access token in it, false if Robinhood wants something else first
     */
    private boolean adoptTokens(JSONObject response) {
        String access = Json.string(response, "access_token");
        if (access == null) {
            return false;
        }
        accessToken = access;
        String refresh = Json.string(response, "refresh_token");
        if (refresh != null) {
            refreshToken = refresh;
        }
        // Trust what Robinhood says it gave rather than what was asked for; it is free to issue a
        // shorter one, and a session that assumed otherwise would start failing calls a day in.
        double lifetime = Json.numberOr(response, "expires_in", RobinhoodApi.TOKEN_LIFETIME_SECONDS);
        expiresAt = Instant.now().plusSeconds((long) lifetime);
        return true;
    }

    /**
     * Runs the "approve this login on your phone" workflow and waits for the tap.
     * <p>
     * <b>This is the one interactive login step a graph can survive</b>, which is why it is
     * automated and the SMS one is not: the person acts on their phone, not in the graph, so an
     * unattended machine reconnecting at 4am fails cleanly at the timeout instead of hanging on a
     * prompt nobody will ever answer.
     *
     * @param workflowId the id the token endpoint handed back
     * @param creds      the login, for the device id the approval is recorded against
     * @throws RobinhoodException if the workflow could not be started, needs a typed-in code, or
     *                            was not approved before the timeout
     */
    private void approveDevice(String workflowId, RobinhoodCredentials creds) {
        if (workflowId == null) {
            throw new RobinhoodException("Robinhood asked for device approval but did not say which "
                    + "workflow to approve. Open the Robinhood app, approve the login there, and "
                    + "press Connect again.");
        }
        JSONObject start = new JSONObject();
        start.put("device_id", creds.deviceToken());
        start.put("flow", "suv");
        start.put("input", new JSONObject().put("workflow_id", workflowId));
        String machineId = Json.string(
                RobinhoodHttp.post(RobinhoodApi.userMachine(baseUrl), start, RobinhoodHttp.baseHeaders()),
                "id");
        if (machineId == null) {
            throw new RobinhoodException("Robinhood would not start the device-approval workflow. "
                    + "Open the Robinhood app, approve the login there, and press Connect again.");
        }

        String inquiryUrl = RobinhoodApi.inquiry(baseUrl, machineId);
        Instant deadline = Instant.now().plusSeconds(approvalTimeoutSeconds);
        log.info("Waiting up to {}s for the Robinhood login to be approved in the app",
                approvalTimeoutSeconds);

        while (true) {
            JSONObject view = RobinhoodHttp.get(inquiryUrl, RobinhoodHttp.baseHeaders());
            JSONObject challenge = Json.object(
                    Json.object(Json.object(view, "type_context"), "context"), "sheriff_challenge");
            if (challenge == null) {
                // No challenge left to satisfy: the workflow is done and the token request can be
                // made again.
                return;
            }
            String type = Json.stringOr(challenge, "type", "prompt");
            if (!"prompt".equalsIgnoreCase(type)) {
                throw new RobinhoodException("Robinhood wants this login approved with a code sent by "
                        + type + ", which a graph has no way to read. Approve the login once in the "
                        + "Robinhood app, or switch the account to an authenticator app and fill in "
                        + "MFA Secret.");
            }
            String challengeId = Json.string(challenge, "id");
            if (challengeId != null && isApproved(challengeId)) {
                JSONObject cont = new JSONObject();
                cont.put("sequence", 0);
                cont.put("user_input", new JSONObject().put("status", "continue"));
                RobinhoodHttp.post(inquiryUrl, cont, RobinhoodHttp.baseHeaders());
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new RobinhoodException("Robinhood is waiting for this login to be approved in "
                        + "the app, and it was not approved within " + approvalTimeoutSeconds
                        + " seconds. Approve it on your phone and press Connect again, or raise "
                        + "Approval Timeout (s).");
            }
            sleep(APPROVAL_POLL);
        }
    }

    /** Whether the approval prompt has been tapped yet. */
    private boolean isApproved(String challengeId) {
        JSONObject status = RobinhoodHttp.get(RobinhoodApi.promptStatus(baseUrl, challengeId),
                RobinhoodHttp.baseHeaders());
        return "validated".equalsIgnoreCase(Json.stringOr(status, "challenge_status", ""));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobinhoodException("Waiting for the Robinhood login approval was interrupted.", e);
        }
    }

    /** Reads which account this login trades. Robinhood returns a list; this library uses the first. */
    private void loadAccount() {
        JSONObject account = Json.firstResult(getJson(RobinhoodApi.accounts(baseUrl)));
        if (account == null) {
            throw new RobinhoodException("This Robinhood login has no brokerage account to trade.");
        }
        accountNumber = Json.string(account, "account_number");
        accountUrl = Json.string(account, "url");
        if (accountUrl == null && accountNumber != null) {
            accountUrl = RobinhoodApi.accounts(baseUrl) + accountNumber + "/";
        }
    }

    private void requireAccount() {
        ensureFresh();
        if (accountUrl == null) {
            loadAccount();
        }
        if (accountUrl == null) {
            throw new RobinhoodException("This Robinhood session has no account to place orders "
                    + "against. Connect the Robinhood Account node again.");
        }
    }

    private static String requireOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new RobinhoodException("Order ID is empty - wire it from the Place Order node's "
                    + "Order ID output.");
        }
        return orderId.trim();
    }
}
