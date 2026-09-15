package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One candle: what a symbol did over a fixed slice of time.
 * <p>
 * <b>Alpaca's market-data wire format is single-letter keys</b> — {@code {"t","o","h","l","c","v",
 * "n","vw"}} — which is a sensible choice for a feed that sends millions of these and an unhelpful
 * one to hand a graph. So this is where they get names, once, and {@link #asMap()} is what a node
 * passes downstream.
 *
 * @param symbol     the ticker these bars are for
 * @param timestamp  the bar's opening time, as the ISO-8601 text Alpaca sent
 * @param open       the first trade in the interval
 * @param high       the highest
 * @param low        the lowest
 * @param close      the last
 * @param volume     shares traded in the interval
 * @param tradeCount how many trades made it up, or null
 * @param vwap       the volume-weighted average price over the interval, or null
 */
public record Bar(String symbol,
                  String timestamp,
                  Double open,
                  Double high,
                  Double low,
                  Double close,
                  Double volume,
                  Double tradeCount,
                  Double vwap) {

    /** The change across the bar, or null if either end is missing. */
    public Double change() {
        return (open == null || close == null) ? null : close - open;
    }

    /**
     * The bar as a map, with words for keys, for the collections library to work on — the shape a
     * For Each node walks and a Map Get reads a field out of.
     *
     * @return the bar as a map, in a stable order
     */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbol);
        if (timestamp != null) {
            map.put("timestamp", timestamp);
        }
        putIfPresent(map, "open", open);
        putIfPresent(map, "high", high);
        putIfPresent(map, "low", low);
        putIfPresent(map, "close", close);
        putIfPresent(map, "volume", volume);
        putIfPresent(map, "trade_count", tradeCount);
        putIfPresent(map, "vwap", vwap);
        return map;
    }

    /**
     * Reads one element of a {@code /v2/stocks/bars} response.
     *
     * @param symbol the ticker the bars were asked for
     * @param body   the bar
     * @return the bar
     */
    static Bar from(String symbol, JSONObject body) {
        return new Bar(symbol,
                Json.string(body, "t"),
                Json.number(body, "o"),
                Json.number(body, "h"),
                Json.number(body, "l"),
                Json.number(body, "c"),
                Json.number(body, "v"),
                Json.number(body, "n"),
                Json.number(body, "vw"));
    }

    private static void putIfPresent(Map<String, Object> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
