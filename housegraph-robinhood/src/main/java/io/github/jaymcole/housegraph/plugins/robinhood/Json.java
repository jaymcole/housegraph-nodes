package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading Robinhood's JSON without believing any of it.
 * <p>
 * <b>Every field on every Robinhood response is optional in practice.</b> Money and quantities come
 * back as <em>strings</em> ({@code "179.4300"}), a field that has no value yet comes back as JSON
 * {@code null} rather than being absent (an unfilled order's {@code average_price}), and fields
 * appear and disappear between API versions without notice, because nothing here is a published
 * contract — see {@code docs/design/robinhood-unofficial-api.md}. Code that reads such a body with
 * {@code getString}/{@code getDouble} throws {@code JSONException} the first time Robinhood changes
 * its mind, and a {@code JSONException} on a node's status line tells its reader nothing.
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
     * The number at {@code key}, or null. Accepts both shapes Robinhood uses — a JSON number and a
     * decimal string — and answers null rather than throwing for anything else, because a field
     * that has stopped being a number is the same problem to a node as a field that is missing.
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

    /** The nested object at {@code key}, or null. */
    static JSONObject object(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optJSONObject(key);
    }

    /**
     * The objects in the array at {@code key}, skipping anything in it that isn't one. Robinhood's
     * list endpoints all answer {@code {"results": [...], "next": ...}}, so this is how every list
     * in this library is read.
     */
    static List<JSONObject> objects(JSONObject object, String key) {
        List<JSONObject> results = new ArrayList<>();
        if (object == null || !object.has(key) || object.isNull(key)) {
            return results;
        }
        JSONArray array = object.optJSONArray(key);
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

    /** The first object of {@code key}'s array, or null when the array is missing or empty. */
    static JSONObject firstResult(JSONObject object) {
        List<JSONObject> results = objects(object, "results");
        return results.isEmpty() ? null : results.get(0);
    }
}
