package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

/**
 * An order worked out in full but not yet sent: what it comes to, what that is worth at the current
 * price, and the exact body that would be POSTed.
 * <p>
 * <b>Preparing and sending are separate steps because the interesting decisions happen in
 * between.</b> A node has to be able to say "this would spend $4,180, which is over the cap you
 * set" and stop — or, in a dry run, say "this is what I would have sent" and stop — and neither is
 * possible for code that decides and places in one call. It also makes the part worth testing
 * testable: what goes on the wire for a given set of fields is a value this returns, not a side
 * effect of a call to Alpaca.
 *
 * @param request        the order as authored
 * @param referencePrice the price the estimate was worked out from, or null when no quote could be
 *                       read - which a dollar-denominated order does not need at all
 * @param payload        the body that would be sent to {@code /v2/orders}
 */
public record PreparedOrder(OrderRequest request, Double referencePrice, JSONObject payload) {

    /**
     * What this order is expected to be worth — the number a spending cap is checked against, and
     * the one a dry run reports. Null when it cannot be worked out, which is itself a reason not to
     * place a market order blind.
     *
     * @return the estimated value in dollars, or null
     */
    public Double estimatedValue() {
        return Orders.estimatedValue(request, referencePrice);
    }

    /** The unique id this order would be placed under. See {@link Orders} for what it is for. */
    public String clientOrderId() {
        return payload.optString("client_order_id", "");
    }

    /** One line saying what would be, or was, sent - for a node's status label and the log. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(request.side().wireValue()).append(' ');
        if (request.isAmountBased()) {
            text.append('$').append(Orders.money(request.notional()).toPlainString())
                    .append(" of ").append(request.symbol());
        } else {
            text.append(Orders.quantity(request.quantity()).toPlainString())
                    .append(' ').append(request.symbol());
        }
        text.append(" (").append(request.type().label()).append(", ")
                .append(request.timeInForce().wireValue()).append(')');
        if (request.limitPrice() != null) {
            text.append(" limit ").append(Orders.price(request.limitPrice()).toPlainString());
        }
        if (request.stopPrice() != null) {
            text.append(" stop ").append(Orders.price(request.stopPrice()).toPlainString());
        }
        Double value = estimatedValue();
        if (value != null && !request.isAmountBased()) {
            text.append(String.format(" ~$%,.2f", value));
        }
        return text.toString();
    }
}
