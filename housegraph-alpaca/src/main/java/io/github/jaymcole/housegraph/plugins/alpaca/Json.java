package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading Alpaca's JSON without believing any of it.
 * <p>
 * Alpaca documents its responses, which is the whole reason to prefer it — but <b>documented is not
 * the same as always present</b>. Money and quantities come back as <em>strings</em>
 * ({@code "179.43"}), a field with no value yet comes back as JSON {@code null} rather than being
 * absent (an unfilled order's {@code filled_avg_price}), and fields do come and go: Alpaca removed
 * {@code pattern_day_trader}, {@code daytrade_count} and {@code daytrading_buying_power} from its
 * account response in July 2026. Code that reads such a body with {@code getString}/{@code
 * getDouble} throws {@code JSONException} the first time that happens, and a {@code JSONException}
 * on a node's status line tells its reader nothing.
 * <p>
 * So every read goes through here and answers {@code null} for "not there", leaving the node to
 * decide whether that is a failure worth a message of its own.
 */
final class Json {

    private Json() {
    }

    /** The string at {@code key}, or null if it is absent, JSON null, or empty. */
    static String string(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return null;
        }
        String value = object.get(key).toString().trim();
        return value.isEmpty() ? null : value;
    }

    /** {@link #string} with a fallback for the absent case, so a caller that wants a blank gets one. */
    static String stringOr(JSONObject object, String key, String fallback) {
        String value = string(object, key);
        return value == null ? fallback : value;
    }

    /**
     * The number at {@code key}, or null. Accepts both shapes Alpaca uses — a JSON number (market
     * data) and a decimal string (everything on the trading API) — and answers null rather than
     * throwing for anything else, because a field that has stopped being a number is the same
     * problem to a node as a field that is missing.
     */
    static Double number(JSONObject object, String key) {
        String value = string(object, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@link #number} with a fallback, for a field whose absence has an obvious zero-ish meaning. */
    static double numberOr(JSONObject object, String key, double fallback) {
        Double value = number(object, key);
        return value == null ? fallback : value;
    }

    /** The boolean at {@code key}, or {@code fallback} if it is absent or not a boolean. */
    static boolean bool(JSONObject object, String key, boolean fallback) {
        String value = string(object, key);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase()) {
            case "true" -> true;
            case "false" -> false;
            default -> fallback;
        };
    }

    /** The boolean at {@code key} as an object, or null when Alpaca didn't report it at all. */
    static Boolean boolOrNull(JSONObject object, String key) {
        String value = string(object, key);
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** The nested object at {@code key}, or null. */
    static JSONObject object(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optJSONObject(key);
    }

    /**
     * The objects in the array at {@code key}, skipping anything in it that isn't one. Alpaca's
     * market-data endpoints answer {@code {"bars": {"AAPL": [...]}}} and its trading endpoints
     * answer bare arrays, so both {@link #array} and this exist.
     */
    static List<JSONObject> objects(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return List.of();
        }
        return array(object.optJSONArray(key));
    }

    /** The objects in {@code array}, skipping anything in it that isn't one. Never null. */
    static List<JSONObject> array(JSONArray array) {
        List<JSONObject> results = new ArrayList<>();
        if (array == null) {
            return results;
        }
        for (int index = 0; index < array.length(); index++) {
            JSONObject element = array.optJSONObject(index);
            if (element != null) {
                results.add(element);
            }
        }
        return results;
    }
}
