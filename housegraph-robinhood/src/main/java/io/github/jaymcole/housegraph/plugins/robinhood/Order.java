package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An order as Robinhood currently sees it — the answer to placing one, and the answer to asking
 * about one later. Both endpoints return the same shape, so both read as this.
 * <p>
 * <b>The field to branch on is {@link #state()}, and the field to show a person is
 * {@link #rejectReason()}.</b> A rejected order is not an HTTP failure: Robinhood accepts the
 * request, answers 201, and puts the refusal in the order's own state a moment later. That is why
 * placing and checking are separate nodes — see {@code PlaceOrderNode} and {@code OrderStatusNode}.
 *
 * @param id             Robinhood's id for the order - what Cancel Order and Order Status take
 * @param symbol         the ticker, resolved from the order's instrument URL, or null if unresolved
 * @param side           buy or sell
 * @param state          where the order has got to
 * @param rawState       the {@code state} exactly as Robinhood spelled it, for display
 * @param quantity       shares asked for
 * @param filledQuantity shares actually traded so far
 * @param averagePrice   the average price they traded at, or null while nothing has filled
 * @param limitPrice     the limit or collar price the order carries, or null
 * @param stopPrice      the stop price, or null for an order with no stop
 * @param timeInForce    how long the order stays live
 * @param createdAt      when Robinhood recorded it, as the ISO-8601 text it sent
 * @param rejectReason   why Robinhood refused it, or null if it didn't
 */
public record Order(String id,
                    String symbol,
                    OrderSide side,
                    OrderState state,
                    String rawState,
                    double quantity,
                    double filledQuantity,
                    Double averagePrice,
                    Double limitPrice,
                    Double stopPrice,
                    String timeInForce,
                    String createdAt,
                    String rejectReason) {

    /** What the filled part cost or raised, or null while nothing has filled. */
    public Double filledValue() {
        return averagePrice == null ? null : averagePrice * filledQuantity;
    }

    /**
     * One line describing the order, for a node's status label: what it is doing, and — when it
     * went wrong — why.
     *
     * @return a sentence with no trailing full stop
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(side == null ? "?" : side.wireValue())
                .append(' ').append(trim(quantity))
                .append(' ').append(symbol == null ? "?" : symbol)
                .append(" - ").append(rawState == null ? state.wireValue() : rawState);
        if (filledQuantity > 0 && averagePrice != null) {
            text.append(" (").append(trim(filledQuantity)).append(" @ ").append(averagePrice).append(')');
        }
        if (rejectReason != null) {
            text.append(": ").append(rejectReason);
        }
        return text.toString();
    }

    /**
     * The order as a map, for the collections library to work on - the same bargain
     * {@link Position#asMap()} makes. Absent fields are left out rather than mapped to null.
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
        map.put("state", rawState == null ? state.wireValue() : rawState);
        map.put("quantity", quantity);
        map.put("filled_quantity", filledQuantity);
        if (averagePrice != null) {
            map.put("average_price", averagePrice);
        }
        if (limitPrice != null) {
            map.put("price", limitPrice);
        }
        if (stopPrice != null) {
            map.put("stop_price", stopPrice);
        }
        if (timeInForce != null) {
            map.put("time_in_force", timeInForce);
        }
        if (createdAt != null) {
            map.put("created_at", createdAt);
        }
        if (rejectReason != null) {
            map.put("reject_reason", rejectReason);
        }
        return map;
    }

    /**
     * Reads an order body.
     *
     * @param body   the order, from a placement or a lookup
     * @param symbol the ticker, resolved separately, or null if it could not be
     * @return the order
     */
    static Order from(JSONObject body, String symbol) {
        String rawState = Json.string(body, "state");
        String sideText = Json.string(body, "side");
        OrderSide side = null;
        if (sideText != null) {
            // Never throws here: an order that came back with a side this library doesn't know is
            // still an order worth reporting, and parse() exists to stop a *typo* placing the wrong
            // trade, which is not what is happening on the way back.
            side = "sell".equalsIgnoreCase(sideText.trim()) ? OrderSide.SELL
                    : "buy".equalsIgnoreCase(sideText.trim()) ? OrderSide.BUY : null;
        }
        return new Order(
                Json.string(body, "id"),
                symbol,
                side,
                OrderState.parse(rawState),
                rawState,
                Json.numberOr(body, "quantity", 0),
                Json.numberOr(body, "cumulative_quantity", 0),
                Json.number(body, "average_price"),
                Json.number(body, "price"),
                Json.number(body, "stop_price"),
                Json.string(body, "time_in_force"),
                Json.string(body, "created_at"),
                Json.string(body, "reject_reason"));
    }

    /** The instrument URL of an order body, which is how its symbol is found. */
    static String instrumentUrlOf(JSONObject body) {
        return Json.string(body, "instrument");
    }

    /** "3" rather than "3.0", but "0.5" kept, for a display string. */
    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
