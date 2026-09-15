package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

/**
 * What one symbol is trading at right now, read from Alpaca's snapshot endpoint.
 *
 * <h2>Why a snapshot rather than a quote</h2>
 * Alpaca's {@code /quotes/latest} returns the bid and ask and nothing else — no last trade, no
 * previous close — so a node built on it could not answer "what is it worth?" or "is it up today?".
 * The snapshot returns the latest trade, the latest quote and the daily bars in one call for the
 * same price, so that is what this library asks for.
 *
 * <h2>{@link #price()} is the number to use, and it is not always the last trade</h2>
 * A symbol that has not traded on the chosen feed today has no last trade, and outside market hours
 * the last trade can be hours stale while the quote keeps moving. So {@code price()} answers the
 * last trade when there is one, the midpoint of the bid and ask when there isn't, and today's
 * closing bar as a last resort — and null when the feed reported none of the three, which is a real
 * answer for a thinly traded symbol on IEX rather than an error.
 *
 * @param symbol         the symbol, as Alpaca spells it
 * @param lastTradePrice the last trade on the chosen feed, or null
 * @param bidPrice       the best bid, or null
 * @param askPrice       the best ask, or null
 * @param bidSize        how many shares are bid at that price, or null
 * @param askSize        how many are offered, or null
 * @param dayOpen        today's opening price, or null before the open
 * @param dayHigh        today's high, or null
 * @param dayLow         today's low, or null
 * @param dayClose       the latest close of today's daily bar, which is {@link #price()}'s last
 *                       resort rather than a number to report on its own
 * @param dayVolume      shares traded today on this feed, or null
 * @param previousClose  the previous trading day's close, or null
 * @param tradeTimestamp when the last trade happened, as the ISO-8601 text Alpaca sent
 */
public record Quote(String symbol,
                    Double lastTradePrice,
                    Double bidPrice,
                    Double askPrice,
                    Double bidSize,
                    Double askSize,
                    Double dayOpen,
                    Double dayHigh,
                    Double dayLow,
                    Double dayClose,
                    Double dayVolume,
                    Double previousClose,
                    String tradeTimestamp) {

    /**
     * The price to treat as current. See the class note for the order it tries things in.
     *
     * @return the current price, or null if the feed reported nothing usable
     */
    public Double price() {
        if (lastTradePrice != null) {
            return lastTradePrice;
        }
        Double middle = midpoint();
        return middle != null ? middle : dayClose;
    }

    /** The middle of the bid and the ask, or null unless both are there and positive. */
    public Double midpoint() {
        if (bidPrice == null || askPrice == null || bidPrice <= 0 || askPrice <= 0) {
            return null;
        }
        return (bidPrice + askPrice) / 2;
    }

    /** The gap between bid and ask, or null if either is missing. Wide means illiquid. */
    public Double spread() {
        return (bidPrice == null || askPrice == null) ? null : askPrice - bidPrice;
    }

    /** The change since the previous close, or null if either end of that subtraction is missing. */
    public Double changeFromPreviousClose() {
        Double current = price();
        return (current == null || previousClose == null) ? null : current - previousClose;
    }

    /**
     * The same change as a fraction of the previous close — 0.012 is up 1.2%. Null when the
     * previous close was missing or zero.
     *
     * @return the day's change as a fraction, or null
     */
    public Double changePercent() {
        Double change = changeFromPreviousClose();
        if (change == null || previousClose == null || previousClose == 0) {
            return null;
        }
        return change / previousClose;
    }

    /**
     * Whether the snapshot carried no prices at all.
     * <p>
     * <b>This is how an unknown ticker shows up.</b> Alpaca does not 404 a snapshot for a symbol it
     * has never heard of — it answers 200 with an empty object for it — so "no such symbol" and
     * "this symbol has not traded on this feed" arrive looking identical, and the caller has to turn
     * that into a message rather than into a price of null.
     *
     * @return true when there is nothing to price the symbol from
     */
    public boolean isEmpty() {
        return lastTradePrice == null && bidPrice == null && askPrice == null && dayClose == null;
    }

    /**
     * Reads Alpaca's snapshot body for one symbol.
     *
     * @param symbol the symbol asked for, used when the body doesn't name one
     * @param body   the snapshot: {@code latestTrade}, {@code latestQuote}, {@code dailyBar},
     *               {@code prevDailyBar}
     * @return the quote
     */
    static Quote from(String symbol, JSONObject body) {
        JSONObject trade = Json.object(body, "latestTrade");
        JSONObject quote = Json.object(body, "latestQuote");
        JSONObject daily = Json.object(body, "dailyBar");
        JSONObject previous = Json.object(body, "prevDailyBar");

        return new Quote(
                Json.stringOr(body, "symbol", symbol),
                Json.number(trade, "p"),
                Json.number(quote, "bp"),
                Json.number(quote, "ap"),
                Json.number(quote, "bs"),
                Json.number(quote, "as"),
                Json.number(daily, "o"),
                Json.number(daily, "h"),
                Json.number(daily, "l"),
                Json.number(daily, "c"),
                Json.number(daily, "v"),
                Json.number(previous, "c"),
                Json.string(trade, "t"));
    }
}
