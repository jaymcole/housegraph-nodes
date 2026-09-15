package io.github.jaymcole.housegraph.plugins.alpaca;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One connected Alpaca account, for as long as the graph wants it: the keys, which account they
 * open, and every call this library makes with them.
 *
 * <h2>Why the session is an object and not a node</h2>
 * A set of credentials is acquired once and used by many nodes, so it lives here and is owned by
 * one resource node ({@code AlpacaAccountNode}), exactly as a database connection or a Discord
 * gateway does in the neighbouring libraries. Every other node in this library takes a session as
 * an input and does one thing with it. Nothing in this class schedules anything: <em>when</em> to
 * quote, place or check is a trigger's business.
 *
 * <h2>"Connecting" to a key-authenticated API</h2>
 * There is no login here and no token to keep alive — Alpaca authenticates every request with the
 * key pair — so {@link #connect} does the only thing that "connected" can honestly mean: it reads
 * {@code /v2/account} once, which proves the keys work, proves they are the right pair for the host
 * (paper keys are rejected by the live host and the reverse), and reads the account number.
 * <b>That check is the whole value of the Connect step</b>: without it, a typo in a key would be
 * discovered by the first node that tried to trade, at whatever hour the trigger driving it fired.
 * <p>
 * The happy consequence of there being no token is that nothing here expires, nothing refreshes in
 * the background, and a session that worked an hour ago works now. The Robinhood library's whole
 * token-refresh machinery has no counterpart in this one.
 *
 * <h2>Thread safety</h2>
 * The credentials are read by every node on whatever thread the engine runs them on and replaced
 * only by a connect or a disconnect, which hold {@link #lock}. Reads are of {@code volatile}
 * fields, so a node mid-call never sees half a connect.
 */
public final class AlpacaSession {

    private static final Logger log = Log.get(AlpacaSession.class);

    /** Alpaca's own cap on {@code GET /v2/orders}. */
    private static final int MAX_ORDERS = 500;

    private final String tradingBaseOverride;
    private final String dataBaseUrl;
    private final Object lock = new Object();

    private volatile AlpacaCredentials credentials;
    private volatile String tradingBaseUrl;
    private volatile String accountNumber;
    private volatile String accountStatus;

    /** A session pointed at the real Alpaca, paper or live according to the credentials. */
    public AlpacaSession() {
        this(null, AlpacaApi.DATA);
    }

    /**
     * A session pointed somewhere else — the seam the tests use to put a stub in Alpaca's place.
     * Not reachable from a node: there is no input for it, deliberately, because "which server is my
     * broker" is not a question a graph should be able to get wrong.
     *
     * @param tradingBaseUrl the trading API root with no trailing slash, or null to let the
     *                       credentials' paper flag choose between Alpaca's two real hosts
     * @param dataBaseUrl    the market-data API root, with no trailing slash
     */
    AlpacaSession(String tradingBaseUrl, String dataBaseUrl) {
        this.tradingBaseOverride = tradingBaseUrl;
        this.dataBaseUrl = dataBaseUrl;
    }

    // --- Lifecycle --------------------------------------------------------------------------

    /**
     * Takes the credentials and checks them against Alpaca.
     * <p>
     * Idempotent in the way that matters: connecting an already-connected session replaces the
     * credentials, which is what a Connect pressed after rotating a key has to do. A connect that
     * fails leaves the session disconnected rather than half-connected, so a node cannot be left
     * reporting a session that would fail every call.
     *
     * @param newCredentials the API keys, and whether they are the paper pair
     * @throws AlpacaException if the keys are incomplete, or Alpaca would not accept them
     */
    public void connect(AlpacaCredentials newCredentials) {
        AlpacaCredentials checked = newCredentials.validate();
        String base = tradingBaseOverride != null ? tradingBaseOverride : checked.tradingHost();
        synchronized (lock) {
            AlpacaCredentials previous = credentials;
            String previousBase = tradingBaseUrl;
            credentials = checked;
            tradingBaseUrl = base;
            try {
                JSONObject account = AlpacaHttp.getObject(AlpacaApi.account(base), authHeaders());
                accountNumber = Json.string(account, "account_number");
                accountStatus = Json.string(account, "status");
            } catch (RuntimeException failure) {
                // Put back what was there. A failed connect that had cleared a working session would
                // take every other node in the graph down with it.
                credentials = previous;
                tradingBaseUrl = previousBase;
                throw failure;
            }
        }
        log.info("Connected to Alpaca {} account {}", checked.accountKind(), accountNumber);
    }

    /**
     * Forgets the credentials.
     * <p>
     * There is nothing to hand back — no token was issued, so nothing is revoked — which means this
     * cannot fail and makes no network call. What it does is stop every node wired to this session
     * from being able to trade with it.
     */
    public void disconnect() {
        synchronized (lock) {
            credentials = null;
            tradingBaseUrl = null;
            accountNumber = null;
            accountStatus = null;
        }
    }

    /** Whether this session holds credentials Alpaca accepted. */
    public boolean isConnected() {
        return credentials != null;
    }

    /** Whether this session is on the paper-trading account. False once it is trading real money. */
    public boolean isPaper() {
        AlpacaCredentials current = credentials;
        return current == null || current.paper();
    }

    /** The brokerage account number this session trades, or null before a connect. */
    public String accountNumber() {
        return accountNumber;
    }

    /**
     * One line for a node's status label.
     * <p>
     * A live account says so in capitals. That is not decoration: the single most consequential fact
     * about a graph on this canvas is whether it is spending real money, and it should be readable
     * without opening a node's inputs.
     */
    public String statusText() {
        AlpacaCredentials current = credentials;
        if (current == null) {
            return "Not connected";
        }
        String number = accountNumber == null ? "(unknown)" : accountNumber;
        String kind = current.paper() ? "paper" : "LIVE";
        String suffix = accountStatus == null || "ACTIVE".equalsIgnoreCase(accountStatus)
                ? "" : " [" + accountStatus + "]";
        return "Connected - " + kind + " account " + number + suffix;
    }

    /** The trading API root this session talks to, or null when it isn't connected. */
    String tradingBaseUrl() {
        return tradingBaseUrl;
    }

    /** The market-data API root this session talks to. */
    String dataBaseUrl() {
        return dataBaseUrl;
    }

    // --- Reading the account ----------------------------------------------------------------

    /**
     * What the account is worth and what it can spend.
     *
     * @return the summary
     * @throws AlpacaException if the call failed
     */
    public AccountSummary accountSummary() {
        return AccountSummary.from(getJson(AlpacaApi.account(trading())));
    }

    /**
     * Everything the account holds, priced by Alpaca.
     *
     * @return the holdings, in the order Alpaca listed them, never null
     * @throws AlpacaException if the positions could not be read
     */
    public List<Position> positions() {
        List<Position> positions = new ArrayList<>();
        for (JSONObject result : AlpacaHttp.getList(AlpacaApi.positions(trading()), authHeaders())) {
            positions.add(Position.from(result));
        }
        return positions;
    }

    /**
     * Sells (or buys back) a holding, in whole or in part.
     * <p>
     * <b>This spends money, and it is a market order.</b> Alpaca works out the exact quantity itself
     * — including the fractional tail that makes "sell all of it" awkward to express as an ordinary
     * order — and submits a market order to liquidate it, which is what the returned order
     * describes.
     *
     * @param symbol     the holding to close
     * @param percentage how much of it, 0 exclusive to 100 inclusive, or null for all of it
     * @return the liquidating order Alpaca placed
     * @throws AlpacaException if there is no such holding, or Alpaca refused
     */
    public Order closePosition(String symbol, BigDecimal percentage) {
        String ticker = normalise(symbol);
        String share = percentage == null ? null : percentage.stripTrailingZeros().toPlainString();
        JSONObject body = AlpacaHttp.deleteObject(
                AlpacaApi.closePosition(trading(), ticker, share), authHeaders());
        Order order = Order.from(body);
        log.info("Closing {}{} - order {}", share == null ? "" : share + "% of ", ticker, order.id());
        return order;
    }

    /**
     * Whether the US equity market is open right now, from Alpaca's own calendar.
     *
     * @return the clock
     * @throws AlpacaException if the call failed
     */
    public MarketClock clock() {
        return MarketClock.from(getJson(AlpacaApi.clock(trading())));
    }

    // --- Market data ------------------------------------------------------------------------

    /**
     * One symbol's current prices.
     *
     * @param symbol the ticker
     * @param feed   the data feed, e.g. {@link AlpacaApi#DEFAULT_FEED}
     * @return the quote
     * @throws AlpacaException if the symbol is unknown to the feed, or the call failed
     */
    public Quote quote(String symbol, String feed) {
        String ticker = normalise(symbol);
        Quote quote = quotes(List.of(ticker), feed).get(ticker);
        if (quote == null || quote.isEmpty()) {
            throw new AlpacaException("Alpaca's " + feed + " feed has no prices for '" + ticker
                    + "'. Check the ticker - and note that the free iex feed only sees trades on "
                    + "one exchange, so a thinly traded symbol can be quiet there all day.");
        }
        return quote;
    }

    /**
     * Several symbols' prices in one call.
     * <p>
     * A symbol the feed does not know is simply absent from the result rather than failing the call,
     * because the callers are list-pricing ones: one bad ticker in a watchlist should not cost the
     * prices of the other nine.
     *
     * @param symbols the tickers; an empty list makes no call at all
     * @param feed    the data feed
     * @return quotes by ticker
     * @throws AlpacaException if the call failed
     */
    public Map<String, Quote> quotes(List<String> symbols, String feed) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) {
            return quotes;
        }
        Set<String> tickers = new LinkedHashSet<>();
        for (String symbol : symbols) {
            tickers.add(normalise(symbol));
        }
        JSONObject body = AlpacaHttp.getObject(
                AlpacaApi.snapshots(dataBaseUrl, String.join(",", tickers), feedOr(feed)),
                authHeaders());
        // Alpaca has answered this endpoint both ways: a bare map of symbol to snapshot, and the
        // same map wrapped in a "snapshots" envelope. Reading either costs one line.
        JSONObject map = Json.object(body, "snapshots");
        if (map == null) {
            map = body;
        }
        for (String ticker : tickers) {
            JSONObject snapshot = Json.object(map, ticker);
            if (snapshot != null) {
                quotes.put(ticker, Quote.from(ticker, snapshot));
            }
        }
        return quotes;
    }

    /**
     * Historical bars for one symbol, oldest first.
     * <p>
     * Asked for newest-first and reversed here, so that "the last 50 daily bars" needs no dates and
     * no knowledge of which days the market was open — see {@link AlpacaApi#bars}. Oldest-first is
     * the order anything computing an average or a trend wants them in.
     *
     * @param symbol    the ticker
     * @param timeframe Alpaca's timeframe spelling, e.g. {@code 1Day}, {@code 1Hour}, {@code 15Min}
     * @param limit     how many bars
     * @param feed      the data feed
     * @return the bars, oldest first, never null
     * @throws AlpacaException if the call failed
     */
    public List<Bar> bars(String symbol, String timeframe, int limit, String feed) {
        String ticker = normalise(symbol);
        JSONObject body = AlpacaHttp.getObject(
                AlpacaApi.bars(dataBaseUrl, ticker, timeframe, limit, feedOr(feed)), authHeaders());
        JSONObject bySymbol = Json.object(body, "bars");
        List<JSONObject> raw = bySymbol == null
                ? List.of()
                : Json.objects(bySymbol, ticker);

        List<Bar> bars = new ArrayList<>();
        for (JSONObject element : raw) {
            bars.add(Bar.from(ticker, element));
        }
        Collections.reverse(bars);
        return bars;
    }

    // --- Orders -----------------------------------------------------------------------------

    /**
     * Works an authored order out in full — the body that would be sent, and what it is expected to
     * cost — <b>without placing it</b>.
     * <p>
     * This is the half that can be looked at before anything is spent: a dry run stops here, and so
     * does an order that a spending cap turns down. It costs at most one market-data read and
     * changes nothing.
     *
     * @param request the authored order
     * @return the prepared order, ready for {@link #submit}
     * @throws AlpacaException if the order is not placeable as authored
     */
    public PreparedOrder prepareOrder(OrderRequest request) {
        OrderRequest checked = request.validate();
        requireConnected();

        // Priced only when the estimate actually needs it. A dollar-denominated order is worth the
        // dollars asked for, and a limit or stop order is valued at its own price, so neither costs
        // a market-data call - which also means neither fails because the feed was quiet.
        Double price = null;
        if (needsReferencePrice(checked)) {
            try {
                price = quote(checked.symbol(), AlpacaApi.DEFAULT_FEED).price();
            } catch (AlpacaException e) {
                // Not fatal here. Whether an unpriceable order may still go out is a decision for
                // the node - Place Order fails only when a spending cap it cannot check is set.
                log.debug("No price for {} while preparing an order: {}",
                        checked.symbol(), e.getMessage());
            }
        }
        return new PreparedOrder(checked, price, Orders.payload(checked, Orders.newClientOrderId()));
    }

    /** Whether the estimate for this order needs a current price to exist at all. */
    private static boolean needsReferencePrice(OrderRequest request) {
        return !request.isAmountBased()
                && !request.type().needsLimitPrice()
                && request.type() != OrderType.STOP;
    }

    /**
     * Sends a prepared order. <b>This is the call that spends money</b> — unless the session is on
     * the paper account, where it spends nothing and behaves the same, which is the point of paper.
     * <p>
     * It does not retry — see {@link AlpacaHttp} — and the order carries the unique
     * {@code client_order_id} that {@link #prepareOrder} generated, so sending the <em>same</em>
     * {@link PreparedOrder} twice is refused by Alpaca rather than traded twice.
     *
     * @param prepared the order from {@link #prepareOrder}
     * @return the order as Alpaca recorded it
     * @throws AlpacaException if Alpaca refused the request outright
     */
    public Order submit(PreparedOrder prepared) {
        Order order = Order.from(postJson(AlpacaApi.orders(trading()), prepared.payload()));
        log.info("Placed {} order {} - {}", isPaper() ? "paper" : "LIVE", order.id(), order.describe());
        return order;
    }

    /**
     * One order as it stands now.
     *
     * @param orderId the id a placement answered with
     * @return the order
     * @throws AlpacaException if there is no such order, or the call failed
     */
    public Order order(String orderId) {
        return Order.from(getJson(AlpacaApi.order(trading(), requireOrderId(orderId))));
    }

    /**
     * The most recent orders on the account, newest first.
     *
     * @param limit     how many to return, capped at {@value #MAX_ORDERS}
     * @param openOnly  true for orders that are still working, false for every recent order
     * @return the orders, never null
     * @throws AlpacaException if the call failed
     */
    public List<Order> recentOrders(int limit, boolean openOnly) {
        int wanted = Math.min(Math.max(limit, 1), MAX_ORDERS);
        String status = openOnly ? "open" : "all";
        List<Order> orders = new ArrayList<>();
        for (JSONObject result
                : AlpacaHttp.getList(AlpacaApi.orders(trading(), status, wanted), authHeaders())) {
            orders.add(Order.from(result));
        }
        return orders;
    }

    /**
     * Asks Alpaca to cancel an order, and reports where it ended up.
     * <p>
     * <b>An order that has already finished is not an error here.</b> It answers with that order
     * untouched, and the caller reads {@link Order#state()} to see that there was nothing to cancel
     * — which is a different outcome from a cancel that failed, and one {@code CancelOrderNode}
     * gives its own flow port.
     * <p>
     * The same is true of the race: Alpaca answers 422 for an order that finished between the read
     * and the cancel, and this reads the order back rather than reporting that as a failure.
     *
     * @param orderId the order to cancel
     * @return the order after the attempt
     * @throws AlpacaException if the order could not be read, or the cancel failed for some other
     *                         reason
     */
    public Order cancel(String orderId) {
        String id = requireOrderId(orderId);
        Order current = order(id);
        if (!current.state().isCancellable()) {
            return current;
        }
        try {
            AlpacaHttp.deleteObject(AlpacaApi.order(trading(), id), authHeaders());
        } catch (AlpacaException failure) {
            // A cancel racing a fill is the normal case, not the exceptional one. If the order is
            // finished now, the refusal was Alpaca saying so, and the caller wants the state it
            // finished in rather than an exception.
            Order after = readQuietly(id);
            if (after != null && after.state().isTerminal()) {
                return after;
            }
            throw failure;
        }
        // Cancelling is a request, not an act: Alpaca answers 204 and the order reaches "canceled"
        // once the exchange agrees. Read it back so the caller reports the state that actually
        // resulted rather than the one that was asked for.
        return order(id);
    }

    private Order readQuietly(String orderId) {
        try {
            return order(orderId);
        } catch (AlpacaException e) {
            return null;
        }
    }

    // --- Authenticated calls ------------------------------------------------------------------

    /**
     * A GET with this session's keys.
     *
     * @param url an absolute Alpaca URL
     * @return the response body
     */
    JSONObject getJson(String url) {
        return AlpacaHttp.getObject(url, authHeaders());
    }

    /**
     * A POST with this session's keys.
     *
     * @param url  an absolute Alpaca URL
     * @param body the request body
     * @return the response body
     */
    JSONObject postJson(String url, JSONObject body) {
        return AlpacaHttp.postObject(url, body, authHeaders());
    }

    /**
     * The two headers that authenticate every Alpaca call.
     *
     * @throws AlpacaException if this session was never connected, or has been disconnected
     */
    private Map<String, String> authHeaders() {
        AlpacaCredentials current = requireConnected();
        return Map.of(
                "APCA-API-KEY-ID", current.apiKeyId().trim(),
                "APCA-API-SECRET-KEY", current.secretKey().trim());
    }

    private AlpacaCredentials requireConnected() {
        AlpacaCredentials current = credentials;
        if (current == null) {
            throw new AlpacaException("This Alpaca Account is not connected. Press Connect on the "
                    + "node, or wire something into its Connect port.");
        }
        return current;
    }

    /** The trading host, after checking there is one. */
    private String trading() {
        requireConnected();
        String base = tradingBaseUrl;
        if (base == null) {
            throw new AlpacaException("This Alpaca Account is not connected.");
        }
        return base;
    }

    private static String feedOr(String feed) {
        return (feed == null || feed.isBlank()) ? AlpacaApi.DEFAULT_FEED : feed.trim().toLowerCase();
    }

    private static String requireOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new AlpacaException("Order ID is empty - wire in the Order ID a Place Order node "
                    + "handed back, or one from Get Recent Orders.");
        }
        return orderId.trim();
    }

    /** A ticker as Alpaca spells them: upper case, no surrounding space. */
    static String normalise(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new AlpacaException("Symbol is empty - name the stock or ETF, e.g. AAPL.");
        }
        return symbol.trim().toUpperCase();
    }
}
