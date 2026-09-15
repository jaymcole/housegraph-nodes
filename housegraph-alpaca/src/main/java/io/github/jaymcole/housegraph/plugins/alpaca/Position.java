package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One holding: how much of a symbol the account owns, what it cost, and what it is worth now.
 * <p>
 * <b>Alpaca prices the holdings itself.</b> {@code GET /v2/positions} comes back with the current
 * price, market value and unrealised profit already worked out, so this library does no quote calls
 * and no arithmetic to fill in a portfolio — where the Robinhood one has to resolve every
 * instrument URL to a ticker and then price the lot. That is the difference a published API makes,
 * and it is why this record has no "include prices" bargain attached to it.
 *
 * @param symbol         the ticker
 * @param quantity       shares held; negative for a short position
 * @param side           {@code long} or {@code short}, as Alpaca spelled it
 * @param averageEntryPrice the average price paid (or received, for a short) per share
 * @param currentPrice   the current price per share, or null if Alpaca didn't report one
 * @param marketValue    what the holding is worth now, or null
 * @param costBasis      what it cost, or null
 * @param unrealisedProfit profit or loss at the current price, or null
 * @param unrealisedProfitPercent the same as a fraction of cost - 0.05 is up 5%, not 5
 * @param changeToday    today's price change as a fraction, or null
 */
public record Position(String symbol,
                       double quantity,
                       String side,
                       Double averageEntryPrice,
                       Double currentPrice,
                       Double marketValue,
                       Double costBasis,
                       Double unrealisedProfit,
                       Double unrealisedProfitPercent,
                       Double changeToday) {

    /** Whether this is a short position, which sells first and buys back later. */
    public boolean isShort() {
        return "short".equalsIgnoreCase(side) || quantity < 0;
    }

    /**
     * The same holding as a map, which is what a node hands downstream so the collections library's
     * Map Get, Filter and Sort nodes can work on it without this library inventing ports for every
     * field. Values are Strings and Doubles only; a field Alpaca didn't report is left out rather
     * than mapped to null, because a Map Get on an absent key and one on a null value read the same
     * downstream, and leaving it out keeps {@code Map Contains} honest.
     * <p>
     * <b>The keys are Alpaca's own field names</b>, not prettier ones. Somebody reading Alpaca's
     * documentation next to their graph should not have to translate, and {@code unrealized_plpc}
     * being American and abbreviated is a smaller cost than that.
     *
     * @return the holding as a map, in a stable order
     */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbol);
        map.put("qty", quantity);
        if (side != null) {
            map.put("side", side);
        }
        putIfPresent(map, "avg_entry_price", averageEntryPrice);
        putIfPresent(map, "current_price", currentPrice);
        putIfPresent(map, "market_value", marketValue);
        putIfPresent(map, "cost_basis", costBasis);
        putIfPresent(map, "unrealized_pl", unrealisedProfit);
        putIfPresent(map, "unrealized_plpc", unrealisedProfitPercent);
        putIfPresent(map, "change_today", changeToday);
        return map;
    }

    /**
     * Reads one element of {@code /v2/positions}.
     *
     * @param body the position
     * @return the position
     */
    static Position from(JSONObject body) {
        return new Position(
                Json.string(body, "symbol"),
                Json.numberOr(body, "qty", 0),
                Json.string(body, "side"),
                Json.number(body, "avg_entry_price"),
                Json.number(body, "current_price"),
                Json.number(body, "market_value"),
                Json.number(body, "cost_basis"),
                Json.number(body, "unrealized_pl"),
                Json.number(body, "unrealized_plpc"),
                Json.number(body, "change_today"));
    }

    private static void putIfPresent(Map<String, Object> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
