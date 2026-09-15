package io.github.jaymcole.housegraph.plugins.alpaca;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where Alpaca's endpoints are and what this library asks them for.
 *
 * <h2>Three hosts, and which one a call goes to is not obvious</h2>
 * Alpaca splits its API across hosts, and getting this wrong is the first thing that bites somebody
 * writing against it:
 * <ul>
 *   <li><b>{@link #TRADING_PAPER}</b> — the paper-trading account. Fake money, real market
 *       behaviour, its own pair of API keys.</li>
 *   <li><b>{@link #TRADING_LIVE}</b> — the real brokerage account. <b>A different pair of keys</b>,
 *       which is the safety property this library leans on: paper keys sent to the live host are
 *       rejected rather than obeyed, so a graph pointed at the wrong one fails to log in instead of
 *       quietly trading real money.</li>
 *   <li><b>{@link #DATA}</b> — market data. <b>The same host for paper and live</b>, because quotes
 *       and bars are not account state. Paper keys read it perfectly well, which is what makes it
 *       possible to build and test a whole strategy without a funded account.</li>
 * </ul>
 *
 * <h2>This is a published, versioned API</h2>
 * Unlike the neighbouring {@code housegraph-robinhood} library, everything here is documented,
 * supported and meant to be called by programs: Alpaca issues the keys, states the rate limits, and
 * offers paper trading precisely so that automated strategies can be built against it. What is
 * written down in this class is a path that Alpaca has published, not one reverse-engineered from
 * an app — so it is unlikely to move, and if it does there will have been a deprecation notice
 * rather than a silent break.
 * <p>
 * It is still collected in one class, for the same reason {@code RobinhoodApi} is: an endpoint that
 * gains a parameter is then an edit here rather than an archaeology exercise across a dozen node
 * classes.
 */
public final class AlpacaApi {

    /** The live brokerage host. Real money. Live keys only. */
    public static final String TRADING_LIVE = "https://api.alpaca.markets";

    /** The paper-trading host. Fake money, and its own separate pair of keys. */
    public static final String TRADING_PAPER = "https://paper-api.alpaca.markets";

    /** Market data, for paper and live accounts alike. */
    public static final String DATA = "https://data.alpaca.markets";

    /**
     * The market-data feed to read when a node doesn't name one.
     * <p>
     * <b>{@code iex} rather than {@code sip} because it is the one every account has.</b> The
     * consolidated tape ({@code sip}) needs a paid market-data subscription, and asking for it
     * without one fails the call with a subscription error rather than falling back — so a library
     * that defaulted to it would not work at all for most people. IEX is a single exchange, so its
     * prices can differ slightly from the consolidated last trade and its volumes are a fraction of
     * the market's; that is the trade, and a node's Feed input is how somebody with a subscription
     * opts out of it.
     */
    public static final String DEFAULT_FEED = "iex";

    /** Sent as {@code User-Agent}, so Alpaca's logs can tell where a call came from. */
    public static final String USER_AGENT = "HouseGraph-Alpaca/1.0";

    private AlpacaApi() {
    }

    /** The trading host for a paper or live account. */
    public static String tradingHost(boolean paper) {
        return paper ? TRADING_PAPER : TRADING_LIVE;
    }

    // --- Trading -----------------------------------------------------------------------------

    /** {@code GET /v2/account} - the balances, and the call a connect uses to check the keys. */
    static String account(String base) {
        return base + "/v2/account";
    }

    /** {@code GET /v2/positions} - every holding, priced. */
    static String positions(String base) {
        return base + "/v2/positions";
    }

    /** {@code DELETE /v2/positions/<symbol>} - liquidate a holding, in whole or in part. */
    static String closePosition(String base, String symbol, String percentage) {
        String url = base + "/v2/positions/" + pathSegment(symbol);
        return percentage == null ? url : url + "?percentage=" + encode(percentage);
    }

    /** {@code POST /v2/orders} to place one, {@code GET} for the list. */
    static String orders(String base) {
        return base + "/v2/orders";
    }

    /**
     * {@code GET /v2/orders} filtered and capped.
     *
     * @param base   the trading host
     * @param status one of {@code open}, {@code closed} or {@code all}
     * @param limit  how many, newest first
     */
    static String orders(String base, String status, int limit) {
        return base + "/v2/orders?status=" + encode(status) + "&limit=" + limit + "&direction=desc";
    }

    /** {@code GET} or {@code DELETE /v2/orders/<id>}. */
    static String order(String base, String orderId) {
        return base + "/v2/orders/" + pathSegment(orderId);
    }

    /** {@code GET /v2/clock} - whether the US equity market is open, and when it next isn't. */
    static String clock(String base) {
        return base + "/v2/clock";
    }

    // --- Market data -------------------------------------------------------------------------

    /**
     * {@code GET /v2/stocks/snapshots} - latest trade, quote and daily bars for several symbols at
     * once.
     * <p>
     * The multi-symbol form is used even for one symbol, so there is one response shape to read
     * rather than two. One call prices a whole watchlist.
     *
     * @param symbols comma-separated tickers
     * @param feed    the data feed, e.g. {@link #DEFAULT_FEED}
     */
    static String snapshots(String base, String symbols, String feed) {
        return base + "/v2/stocks/snapshots?symbols=" + encode(symbols) + "&feed=" + encode(feed);
    }

    /**
     * {@code GET /v2/stocks/bars} - historical OHLC bars.
     * <p>
     * <b>Sorted newest-first and capped, rather than asked for by date range.</b> "The last 50 daily
     * bars" is what a graph computing an average actually wants, and it is a question with no dates
     * in it; asking by date range would mean this library working out which days the market was
     * open. The caller puts them back into chronological order.
     *
     * @param symbol    one ticker
     * @param timeframe Alpaca's timeframe spelling, e.g. {@code 1Day}, {@code 15Min}
     * @param limit     how many bars, newest first
     * @param feed      the data feed
     */
    static String bars(String base, String symbol, String timeframe, int limit, String feed) {
        return base + "/v2/stocks/bars"
                + "?symbols=" + encode(symbol)
                + "&timeframe=" + encode(timeframe)
                + "&limit=" + limit
                + "&sort=desc"
                // Split- and dividend-adjusted, so a moving average taken across a split is not a
                // cliff. Unadjusted prices are the raw record; nobody computing an indicator wants
                // them.
                + "&adjustment=all"
                + "&feed=" + encode(feed);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * A value going into a URL path rather than a query. {@code URLEncoder} is a form encoder, so a
     * space comes out as {@code +}, which in a path means a literal plus sign rather than a space.
     * Everything else it does is right here, including turning the slash in a crypto pair
     * ("BTC/USD") into {@code %2F} so that it stays one path segment.
     */
    private static String pathSegment(String value) {
        return encode(value).replace("+", "%20");
    }
}
