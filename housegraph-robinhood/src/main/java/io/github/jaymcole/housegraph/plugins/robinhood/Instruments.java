package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Symbols to Robinhood's instrument URLs, and back again — with the answers kept, because they
 * never change.
 * <p>
 * <b>Robinhood identifies a security by a URL, not a ticker.</b> An order names an instrument URL;
 * a position and an order both come back naming one and never naming the symbol. So almost every
 * node in this library needs a translation in one direction or the other, and doing it uncached
 * would mean a call per position per refresh — which is how an account ends up rate-limited by a
 * graph that is only drawing a table.
 * <p>
 * The cache is unbounded and never expires, deliberately: an instrument's symbol is a fact, not a
 * reading, and the population is the handful of things one account touches. The one thing that
 * <em>can</em> change under it is a ticker being reassigned after a delisting, which is rare enough
 * to be worth a restart rather than an expiry policy nobody would ever see work.
 */
final class Instruments {

    private final RobinhoodSession session;
    private final Map<String, String> urlBySymbol = new ConcurrentHashMap<>();
    private final Map<String, String> symbolByUrl = new ConcurrentHashMap<>();

    Instruments(RobinhoodSession session) {
        this.session = session;
    }

    /**
     * The instrument URL for a ticker.
     *
     * @param symbol the ticker, any case
     * @return the instrument URL
     * @throws RobinhoodException if Robinhood knows no such symbol
     */
    String urlFor(String symbol) {
        String key = normalise(symbol);
        String cached = urlBySymbol.get(key);
        if (cached != null) {
            return cached;
        }
        JSONObject body = session.getJson(RobinhoodApi.instrumentBySymbol(session.baseUrl(), key));
        JSONObject instrument = Json.firstResult(body);
        String url = Json.string(instrument, "url");
        if (url == null) {
            throw new RobinhoodException("Robinhood has no tradable instrument for the symbol '"
                    + key + "'. Check the ticker.");
        }
        remember(key, url);
        return url;
    }

    /**
     * The ticker for an instrument URL, or null if it could not be read. Answers null rather than
     * throwing because the callers are list-building ones: a portfolio with one unresolvable
     * holding in it is still worth showing.
     *
     * @param instrumentUrl the instrument URL, possibly null
     * @return the ticker, or null
     */
    String symbolFor(String instrumentUrl) {
        if (instrumentUrl == null || instrumentUrl.isBlank()) {
            return null;
        }
        String cached = symbolByUrl.get(instrumentUrl);
        if (cached != null) {
            return cached;
        }
        String symbol;
        try {
            symbol = Json.string(session.getJson(instrumentUrl), "symbol");
        } catch (RobinhoodException e) {
            return null;
        }
        if (symbol == null) {
            return null;
        }
        remember(normalise(symbol), instrumentUrl);
        return symbol;
    }

    /** Seeds both directions at once - every lookup learns the other way round for free. */
    private void remember(String symbol, String url) {
        urlBySymbol.put(symbol, url);
        symbolByUrl.put(url, symbol);
    }

    /** Tickers are upper-case and Robinhood's symbol filter is case-sensitive. */
    static String normalise(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new RobinhoodException("Symbol is empty - name the stock or ETF, e.g. AAPL.");
        }
        return symbol.trim().toUpperCase();
    }
}
