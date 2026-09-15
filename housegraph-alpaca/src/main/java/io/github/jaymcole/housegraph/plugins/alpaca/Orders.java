package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Turning an authored order into the body Alpaca expects.
 * <p>
 * This and {@link AlpacaApi} are where the wire format is written down, so a payload field that
 * Alpaca renames is a change here and nowhere else.
 *
 * <h2>The two things worth knowing</h2>
 * <b>Every order carries a {@code client_order_id}.</b> Alpaca requires it to be unique per account
 * and refuses a second order that reuses one — which is what makes a double-send safe. This library
 * generates a fresh id per prepared order and never reuses it, so re-sending the <em>same</em>
 * {@link PreparedOrder} is refused rather than traded twice, while a genuinely new order gets a new
 * id. (Note the difference from an idempotency key that replays the original answer: Alpaca's
 * refusal surfaces as a failed node, not as a success. The trade not happening twice is the part
 * that matters.)
 * <p>
 * <b>Prices are rounded to the tick Alpaca accepts</b> — two decimal places at a dollar and above,
 * four below it — because a limit price of 100.005 is rejected outright rather than rounded by
 * Alpaca. A rounded price is visible before anything is sent: it is what a dry run reports and what
 * the estimate is worked out from.
 */
final class Orders {

    /** Alpaca's tick for anything trading at a dollar or more. */
    private static final int PRICE_SCALE = 2;

    /** Sub-dollar stocks quote in hundredths of a cent. */
    private static final int SUB_DOLLAR_PRICE_SCALE = 4;

    /** Fractional shares go to nine decimal places; more is rejected. */
    private static final int QUANTITY_SCALE = 9;

    private Orders() {
    }

    /**
     * The body to POST to {@code /v2/orders}.
     * <p>
     * Note what is <em>not</em> here: no account identifier (the API key says which account), no
     * instrument lookup (Alpaca takes the ticker), and no price on a market order (Alpaca does not
     * collar them the way Robinhood's private API does). Each of those is a call this library does
     * not have to make before it can place an order.
     *
     * @param request       the authored, validated order
     * @param clientOrderId the unique id this order is placed under
     * @return the request body
     */
    static JSONObject payload(OrderRequest request, String clientOrderId) {
        JSONObject body = new JSONObject();
        body.put("symbol", request.symbol());
        body.put("side", request.side().wireValue());
        body.put("type", request.type().wireValue());
        body.put("time_in_force", request.timeInForce().wireValue());
        body.put("client_order_id", clientOrderId);
        body.put("extended_hours", request.extendedHours());

        if (request.isAmountBased()) {
            // Dollars. Alpaca works the share count out itself at execution, which is the whole
            // reason a notional order has to be a market order good for the day.
            body.put("notional", money(request.notional()).toPlainString());
        } else {
            body.put("qty", quantity(request.quantity()).toPlainString());
        }

        if (request.limitPrice() != null) {
            body.put("limit_price", price(request.limitPrice()).toPlainString());
        }
        if (request.stopPrice() != null) {
            body.put("stop_price", price(request.stopPrice()).toPlainString());
        }
        if (request.trailPrice() != null) {
            body.put("trail_price", price(request.trailPrice()).toPlainString());
        }
        if (request.trailPercent() != null) {
            body.put("trail_percent", request.trailPercent().stripTrailingZeros().toPlainString());
        }
        return body;
    }

    /** A fresh unique id for one order. See the class note for what Alpaca does with it. */
    static String newClientOrderId() {
        // Prefixed so that an order placed by a graph is identifiable in Alpaca's own dashboard,
        // next to ones placed by hand or by something else the account holder runs.
        return "housegraph-" + UUID.randomUUID();
    }

    /**
     * What the order is expected to be worth, for a spending cap and a dry run to report.
     * <p>
     * Each shape is valued at the price it is actually likely to trade at: a limit order at its
     * limit, a stop at its stop, and a market or trailing-stop order at the current price. A
     * dollar-denominated order is worth exactly the dollars asked for, with no price needed at all.
     *
     * @param request        the authored order
     * @param referencePrice the current price, or null if none could be read
     * @return the estimated value in dollars, or null when it cannot be worked out
     */
    static Double estimatedValue(OrderRequest request, Double referencePrice) {
        if (request.isAmountBased()) {
            return request.notional().doubleValue();
        }
        BigDecimal unit = unitPrice(request, referencePrice);
        return unit == null ? null : unit.multiply(request.quantity()).doubleValue();
    }

    private static BigDecimal unitPrice(OrderRequest request, Double referencePrice) {
        if (request.type().needsLimitPrice()) {
            return request.limitPrice();
        }
        if (request.type() == OrderType.STOP) {
            // A stop becomes a market order at the stop price, so that - not today's price - is
            // roughly what it trades at.
            return request.stopPrice();
        }
        return referencePrice == null ? null : BigDecimal.valueOf(referencePrice);
    }

    /** A price at the tick Alpaca accepts: 2 decimal places at a dollar and up, 4 below it. */
    static BigDecimal price(BigDecimal value) {
        int scale = value.compareTo(BigDecimal.ONE) >= 0 ? PRICE_SCALE : SUB_DOLLAR_PRICE_SCALE;
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /** A dollar amount, at cents. */
    static BigDecimal money(BigDecimal value) {
        return value.setScale(PRICE_SCALE, RoundingMode.DOWN);
    }

    /** A share count, at Alpaca's nine decimal places, with trailing zeros trimmed. */
    static BigDecimal quantity(BigDecimal value) {
        BigDecimal scaled = value.scale() > QUANTITY_SCALE
                ? value.setScale(QUANTITY_SCALE, RoundingMode.DOWN)
                : value;
        BigDecimal trimmed = scaled.stripTrailingZeros();
        // stripTrailingZeros leaves an integer as 2E+1, which toPlainString would write as "20" but
        // which reads badly anywhere else; pull the scale back to zero for whole share counts.
        return trimmed.scale() < 0 ? trimmed.setScale(0) : trimmed;
    }
}
