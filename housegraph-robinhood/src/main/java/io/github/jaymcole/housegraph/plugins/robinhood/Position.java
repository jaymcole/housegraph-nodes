package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One holding: how much of a symbol the account owns, what it cost, and — when the caller asked for
 * prices — what it is worth now.
 * <p>
 * <b>Robinhood's {@code /positions/} does not name the symbol.</b> It identifies the holding by an
 * instrument URL, so every position has to be turned back into a ticker through
 * {@link Instruments}, which caches: an account's holdings barely change, and an instrument's
 * symbol never does.
 *
 * @param symbol          the ticker
 * @param quantity        shares held, fractional included
 * @param averageBuyPrice the average price paid per share, or null if Robinhood didn't say
 * @param price           the current price per share, or null when prices weren't asked for
 */
public record Position(String symbol, double quantity, Double averageBuyPrice, Double price) {

    /** What the holding is worth now, or null when no price was fetched. */
    public Double marketValue() {
        return price == null ? null : price * quantity;
    }

    /** What the holding cost, or null when Robinhood reported no average price. */
    public Double costBasis() {
        return averageBuyPrice == null ? null : averageBuyPrice * quantity;
    }

    /** Profit or loss on the holding at the current price, or null if either number is missing. */
    public Double unrealisedGain() {
        Double value = marketValue();
        Double cost = costBasis();
        return (value == null || cost == null) ? null : value - cost;
    }

    /**
     * The same holding as a map, which is what a node hands downstream so the collections library's
     * Map Get, Filter and Sort nodes can work on it without this library inventing ports for every
     * field. Keys are lower-case words, values are Strings and Doubles only; a field Robinhood
     * didn't report is left out rather than mapped to null, because a Map Get on an absent key and
     * one on a null value read the same downstream, and leaving it out keeps
     * {@code Map Contains} honest.
     *
     * @return the holding as a map, in a stable order
     */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbol);
        map.put("quantity", quantity);
        putIfPresent(map, "average_buy_price", averageBuyPrice);
        putIfPresent(map, "price", price);
        putIfPresent(map, "market_value", marketValue());
        putIfPresent(map, "cost_basis", costBasis());
        putIfPresent(map, "unrealised_gain", unrealisedGain());
        return map;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * Reads one result of {@code /positions/}.
     *
     * @param symbol the ticker, resolved from the position's instrument URL
     * @param body   the position
     * @param price  the current price, or null if prices weren't fetched
     * @return the position
     */
    static Position from(String symbol, JSONObject body, Double price) {
        return new Position(symbol,
                Json.numberOr(body, "quantity", 0),
                Json.number(body, "average_buy_price"),
                price);
    }

    /** The instrument URL of one {@code /positions/} result, which is how its symbol is found. */
    static String instrumentUrlOf(JSONObject body) {
        return Json.string(body, "instrument");
    }
}
