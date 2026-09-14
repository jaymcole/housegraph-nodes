package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;

/**
 * What one symbol is trading at right now.
 * <p>
 * <b>{@link #price()} is the number to use, and it is not always the last trade.</b> Outside market
 * hours Robinhood stops updating {@code last_trade_price} and reports the extended-hours price in a
 * field of its own; a graph that read only the former would price a 7pm order off the 4pm close.
 * So {@code price()} answers the extended-hours price when there is one and the last trade
 * otherwise, which is the same rule the phone app's headline number follows.
 *
 * @param symbol            the symbol, as Robinhood spells it
 * @param lastTradePrice    the last regular-hours trade, or null if Robinhood didn't say
 * @param extendedHoursPrice the last pre/post-market trade, or null outside those sessions
 * @param bidPrice          the best bid, or null
 * @param askPrice          the best ask, or null
 * @param previousClose     yesterday's close, or null
 * @param tradingHalted     whether trading in this symbol is halted
 */
public record Quote(String symbol,
                    Double lastTradePrice,
                    Double extendedHoursPrice,
                    Double bidPrice,
                    Double askPrice,
                    Double previousClose,
                    boolean tradingHalted) {

    /**
     * The price to treat as current: the extended-hours price when the market is closed and one is
     * being reported, otherwise the last regular-hours trade. Null only when Robinhood reported
     * neither, which happens for a symbol that has never traded today.
     *
     * @return the current price, or null if Robinhood reported none
     */
    public Double price() {
        return extendedHoursPrice != null ? extendedHoursPrice : lastTradePrice;
    }

    /** The change since yesterday's close, or null if either end of that subtraction is missing. */
    public Double changeFromPreviousClose() {
        Double current = price();
        return (current == null || previousClose == null) ? null : current - previousClose;
    }

    /**
     * Reads Robinhood's {@code /marketdata/quotes/} body.
     *
     * @param symbol the symbol asked for, used when the body doesn't name one
     * @param body   the response
     * @return the quote
     */
    static Quote from(String symbol, JSONObject body) {
        return new Quote(
                Json.stringOr(body, "symbol", symbol),
                Json.number(body, "last_trade_price"),
                Json.number(body, "last_extended_hours_trade_price"),
                Json.number(body, "bid_price"),
                Json.number(body, "ask_price"),
                Json.number(body, "previous_close"),
                Json.bool(body, "trading_halted", false));
    }
}
