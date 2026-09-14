package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Turning an authored order into the body Robinhood expects.
 * <p>
 * This and {@link RobinhoodApi} are where the unofficial API's shape is written down, so a payload
 * field that Robinhood renames is a change here and nowhere else.
 *
 * <h2>The two things that surprise people</h2>
 * <b>A market order carries a price.</b> Robinhood's {@code market} type is not "fill at any
 * price": the API requires a {@code price}, and uses it as a <em>collar</em> — the order will not
 * trade more than a short distance beyond it. Sending no price is rejected, and sending a stale one
 * is how a market order silently fails to fill, so the collar is taken from a quote fetched moments
 * before ({@link RobinhoodSession#prepareOrder}) and padded by {@link #COLLAR} in the direction the
 * order is going.
 * <p>
 * <b>Every order carries a {@code ref_id}.</b> It is Robinhood's idempotency key: two POSTs with
 * the same one are the same order, not two. This library generates a fresh one per prepared order
 * and never reuses it, which is what makes a retry — by a graph, or by a person clicking twice —
 * safe to send as long as it is the <em>same</em> prepared order.
 */
final class Orders {

    /**
     * How far past the current price a market order's collar is set, as a fraction: 5%.
     * <p>
     * It is a trade between two bad outcomes. Too tight and an ordinary spread means the order
     * doesn't fill and the graph is left holding a queued order it thinks is done. Too loose and a
     * market order placed into a fast move fills at a price nobody would have accepted. 5% is what
     * the unofficial clients settled on and it is wide enough for a normal open; a graph that wants
     * a promise about price should place a limit order, which is the tool for it.
     */
    static final BigDecimal COLLAR = new BigDecimal("0.05");

    /** Robinhood prices are two decimal places for anything trading at a dollar or more. */
    private static final int PRICE_SCALE = 2;

    /** Fractional shares go to six decimal places; more is rejected. */
    private static final int QUANTITY_SCALE = 6;

    private Orders() {
    }

    /**
     * The share count to order. A request that says shares uses them as written; one that says
     * dollars divides by the price and rounds <em>down</em>, so "$100 of AAPL" never spends $100.02.
     *
     * @param request the authored order
     * @param price   the current price, needed only for a dollar amount
     * @return the share count, at most six decimal places
     * @throws RobinhoodException if a dollar amount was given and no price could be had, or if the
     *                            amount is too small to buy any of the symbol at all
     */
    static BigDecimal quantityFor(OrderRequest request, Double price) {
        if (!request.isAmountBased()) {
            return request.quantity().stripTrailingZeros();
        }
        if (price == null || price <= 0) {
            throw new RobinhoodException("Cannot turn $" + request.amount().toPlainString() + " of "
                    + request.symbol() + " into a number of shares: Robinhood reported no current "
                    + "price for it. Set Quantity in shares instead.");
        }
        BigDecimal shares = request.amount()
                .divide(BigDecimal.valueOf(price), QUANTITY_SCALE, RoundingMode.DOWN)
                .stripTrailingZeros();
        if (shares.signum() <= 0) {
            throw new RobinhoodException("$" + request.amount().toPlainString() + " does not buy any "
                    + request.symbol() + " at $" + price + ".");
        }
        return shares;
    }

    /**
     * The body to POST to {@code /orders/}.
     *
     * @param request       the authored order
     * @param accountUrl    the account's own URL, which Robinhood wants rather than its number
     * @param instrumentUrl the symbol's instrument URL
     * @param quantity      the share count from {@link #quantityFor}
     * @param price         the current price, for a market order's collar; may be null for a shape
     *                      that carries its own price
     * @return the request body
     */
    static JSONObject payload(OrderRequest request, String accountUrl, String instrumentUrl,
                              BigDecimal quantity, Double price) {
        JSONObject body = new JSONObject();
        body.put("account", accountUrl);
        body.put("instrument", instrumentUrl);
        body.put("symbol", request.symbol());
        body.put("side", request.side().wireValue());
        body.put("type", request.type().wireType());
        body.put("trigger", request.type().wireTrigger());
        body.put("time_in_force", request.timeInForce().wireValue());
        body.put("quantity", quantity.toPlainString());
        body.put("extended_hours", request.extendedHours());
        body.put("ref_id", UUID.randomUUID().toString());
        // Robinhood's newer order form; the unofficial clients send it and orders without it are
        // accepted too, so this is belt and braces rather than load-bearing.
        body.put("order_form_version", "v2");

        BigDecimal limit = priceFor(request, price);
        if (limit != null) {
            body.put("price", limit.toPlainString());
        }
        if (request.stopPrice() != null) {
            body.put("stop_price", scale(request.stopPrice()).toPlainString());
        }
        return body;
    }

    /**
     * The {@code price} field: the authored limit for a limit shape, and a collar off the current
     * price for a market one.
     *
     * @param request the authored order
     * @param price   the current price, or null
     * @return the price to send, or null when there is nothing to send (a market order for which no
     *         price could be had - Robinhood will decide whether to take it)
     */
    static BigDecimal priceFor(OrderRequest request, Double price) {
        if (request.type().needsLimitPrice()) {
            return scale(request.limitPrice());
        }
        if (price == null) {
            return null;
        }
        BigDecimal current = BigDecimal.valueOf(price);
        BigDecimal padding = current.multiply(COLLAR);
        // A buy is collared above the market and a sell below it: the collar is the worst price the
        // order may accept, so it sits on the far side of where the order is going.
        BigDecimal collared = request.side() == OrderSide.BUY
                ? current.add(padding)
                : current.subtract(padding);
        if (collared.signum() <= 0) {
            return scale(current);
        }
        return scale(collared);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
