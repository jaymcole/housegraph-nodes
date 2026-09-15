package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An order as Alpaca currently sees it — the answer to placing one, and the answer to asking about
 * one later. Both endpoints return the same shape, so both read as this.
 *
 * <h2>A rejected order is not a failed request</h2>
 * The field to branch on is {@link #state()}. Alpaca refuses an order two quite different ways:
 * a malformed or unaffordable one is refused <em>synchronously</em>, with a 422 whose message says
 * why (that never becomes an Order at all — it fails the node), while one the exchange turns down
 * is accepted, answered 200, and moved to {@code rejected} a moment later. That second kind is why
 * placing and checking are separate nodes.
 *
 * <h2>Alpaca does not say why it rejected one</h2>
 * There is no reject-reason field on the order — the explanation goes out over Alpaca's trade
 * update stream, which this library does not hold open. So a rejected order reports
 * {@code rejected} and nothing more, and the place to look is Alpaca's own dashboard. It is worth
 * knowing before building a graph that expects to be told.
 *
 * @param id             Alpaca's id for the order - what Cancel Order and Order Status take
 * @param clientOrderId  the id this library placed it under, which is visible in Alpaca's dashboard
 * @param symbol         the ticker
 * @param side           buy or sell
 * @param state          where the order has got to
 * @param rawState       the {@code status} exactly as Alpaca spelled it, for display
 * @param quantity       shares asked for, or null for a dollar-denominated order
 * @param notional       dollars asked for, or null for a share-denominated order
 * @param filledQuantity shares actually traded so far
 * @param averagePrice   the average price they traded at, or null while nothing has filled
 * @param type           the order shape, as Alpaca spelled it
 * @param limitPrice     the limit price, or null
 * @param stopPrice      the stop price, or null
 * @param timeInForce    how long the order stays live
 * @param submittedAt    when Alpaca recorded it, as the ISO-8601 text it sent
 * @param filledAt       when it finished filling, or null
 */
public record Order(String id,
                    String clientOrderId,
                    String symbol,
                    OrderSide side,
                    OrderState state,
                    String rawState,
                    Double quantity,
                    Double notional,
                    double filledQuantity,
                    Double averagePrice,
                    String type,
                    Double limitPrice,
                    Double stopPrice,
                    String timeInForce,
                    String submittedAt,
                    String filledAt) {

    /** What the filled part cost or raised, or null while nothing has filled. */
    public Double filledValue() {
        return averagePrice == null ? null : averagePrice * filledQuantity;
    }

    /** The state as Alpaca spelled it, falling back to this library's own spelling. */
    public String displayState() {
        return rawState == null ? state.wireValue() : rawState;
    }

    /**
     * One line describing the order, for a node's status label: what it is doing, and how much of
     * it has happened.
     *
     * @return a sentence with no trailing full stop
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(side == null ? "?" : side.wireValue()).append(' ');
        if (quantity != null) {
            text.append(trim(quantity)).append(' ').append(symbol == null ? "?" : symbol);
        } else if (notional != null) {
            text.append('$').append(trim(notional)).append(" of ").append(symbol == null ? "?" : symbol);
        } else {
            text.append(symbol == null ? "?" : symbol);
        }
        text.append(" - ").append(displayState());
        if (filledQuantity > 0 && averagePrice != null) {
            text.append(" (").append(trim(filledQuantity)).append(" @ ").append(averagePrice).append(')');
        }
        return text.toString();
    }

    /**
     * The order as a map, for the collections library to work on - the same bargain
     * {@link Position#asMap()} makes, and with Alpaca's own field names for the same reason.
     * Absent fields are left out rather than mapped to null.
     *
     * @return the order as a map, in a stable order
     */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        if (symbol != null) {
            map.put("symbol", symbol);
        }
        map.put("side", side == null ? "" : side.wireValue());
        map.put("status", displayState());
        putIfPresent(map, "qty", quantity);
        putIfPresent(map, "notional", notional);
        map.put("filled_qty", filledQuantity);
        putIfPresent(map, "filled_avg_price", averagePrice);
        if (type != null) {
            map.put("type", type);
        }
        putIfPresent(map, "limit_price", limitPrice);
        putIfPresent(map, "stop_price", stopPrice);
        if (timeInForce != null) {
            map.put("time_in_force", timeInForce);
        }
        if (submittedAt != null) {
            map.put("submitted_at", submittedAt);
        }
        if (filledAt != null) {
            map.put("filled_at", filledAt);
        }
        return map;
    }

    /**
     * Reads an order body.
     *
     * @param body the order, from a placement or a lookup
     * @return the order
     */
    static Order from(JSONObject body) {
        String rawState = Json.string(body, "status");
        return new Order(
                Json.string(body, "id"),
                Json.string(body, "client_order_id"),
                Json.string(body, "symbol"),
                OrderSide.read(Json.string(body, "side")),
                OrderState.parse(rawState),
                rawState,
                Json.number(body, "qty"),
                Json.number(body, "notional"),
                Json.numberOr(body, "filled_qty", 0),
                Json.number(body, "filled_avg_price"),
                // "type" is the current field; "order_type" is the one Alpaca deprecated in its
                // favour and still sends. Prefer the former, accept the latter.
                Json.stringOr(body, "type", Json.string(body, "order_type")),
                Json.number(body, "limit_price"),
                Json.number(body, "stop_price"),
                Json.string(body, "time_in_force"),
                Json.stringOr(body, "submitted_at", Json.string(body, "created_at")),
                Json.string(body, "filled_at"));
    }

    private static void putIfPresent(Map<String, Object> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** "3" rather than "3.0", but "0.5" kept, for a display string. */
    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
