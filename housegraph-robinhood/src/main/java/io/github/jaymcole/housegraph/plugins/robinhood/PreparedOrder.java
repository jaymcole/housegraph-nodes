package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * An order worked out in full but not yet sent: the instrument it names, the share count it comes
 * to, what that is worth at the current price, and the exact body that would be POSTed.
 * <p>
 * <b>Preparing and sending are separate steps because the interesting decisions happen in
 * between.</b> A node has to be able to say "this would spend $4,180, which is over the cap you
 * set" and stop — or, in a dry run, say "this is what I would have sent" and stop — and neither is
 * possible for code that decides and places in one call. It also makes the part worth testing
 * testable: what goes on the wire for a given set of fields is a value this returns, not a side
 * effect of a call to Robinhood.
 *
 * @param request         the order as authored
 * @param instrumentUrl   Robinhood's URL for the symbol's instrument
 * @param quantity        the share count actually being ordered, after a dollar amount was divided
 *                        by the price and rounded
 * @param referencePrice  the price the estimate and any collar were worked out from, or null when
 *                        Robinhood reported no price for the symbol
 * @param payload         the body that would be sent to {@code /orders/}
 */
public record PreparedOrder(OrderRequest request,
                            String instrumentUrl,
                            BigDecimal quantity,
                            Double referencePrice,
                            JSONObject payload) {

    /**
     * What this order is worth at the price it was worked out from — the number a spending cap is
     * checked against, and the one a dry run reports. Null when no price was available, which is
     * itself a reason not to place a market order blind.
     *
     * @return the estimated value in dollars, or null
     */
    public Double estimatedValue() {
        if (referencePrice == null) {
            return null;
        }
        // A limit order will not trade through its limit, so that is the honest worst case for a
        // buy; a market order's honest estimate is the current price.
        BigDecimal price = request.limitPrice() != null && request.type() != null
                && request.type().needsLimitPrice()
                ? request.limitPrice()
                : BigDecimal.valueOf(referencePrice);
        return price.multiply(quantity).doubleValue();
    }

    /** The order's idempotency key, which is what stops a re-sent request trading twice. */
    public String referenceId() {
        return payload.optString("ref_id", "");
    }

    /** One line saying what would be, or was, sent - for a node's status label and the log. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(request.side().wireValue()).append(' ').append(quantity.toPlainString())
                .append(' ').append(request.symbol())
                .append(" (").append(request.type().label()).append(')');
        Double value = estimatedValue();
        if (value != null) {
            text.append(String.format(" ~$%.2f", value));
        }
        return text.toString();
    }
}
