package io.github.jaymcole.housegraph.plugins.robinhood;

import java.math.BigDecimal;

/**
 * An order as the person authored it, before anything has been looked up or sent.
 * <p>
 * <b>Everything that can be wrong with an order is caught here</b>, in {@link #validate()}, before
 * a single call goes out: a blank symbol, a negative quantity, a limit order with no limit price,
 * both a share count and a dollar amount. That ordering is the point — a validation failure costs
 * nothing and says exactly which field to fix, while the same mistake caught by Robinhood costs a
 * round trip and comes back as "Invalid request".
 *
 * @param symbol        the ticker to trade, upper-cased
 * @param side          buy or sell
 * @param type          market, limit, stop loss or stop limit
 * @param quantity      shares to trade, or null when {@code amount} says it in dollars instead
 * @param amount        dollars to trade, or null when {@code quantity} says it in shares
 * @param limitPrice    the limit price, required for the limit shapes
 * @param stopPrice     the stop price, required for the stop shapes
 * @param timeInForce   how long the order stays live
 * @param extendedHours whether the order may fill in the pre/post-market session
 */
public record OrderRequest(String symbol,
                           OrderSide side,
                           OrderType type,
                           BigDecimal quantity,
                           BigDecimal amount,
                           BigDecimal limitPrice,
                           BigDecimal stopPrice,
                           TimeInForce timeInForce,
                           boolean extendedHours) {

    /**
     * Checks the combination, throwing with the name of the field to fix.
     *
     * @return this request, so it can be validated inline
     * @throws RobinhoodException if the order could not be placed as authored
     */
    public OrderRequest validate() {
        if (symbol == null || symbol.isBlank()) {
            throw new RobinhoodException("Symbol is empty - name the stock or ETF to trade, e.g. AAPL.");
        }
        if (side == null) {
            throw new RobinhoodException("Side is empty - it must be buy or sell.");
        }
        if (quantity == null && amount == null) {
            throw new RobinhoodException("Set either Quantity (in shares) or Amount ($) on "
                    + symbol + " - neither is filled in, so there is nothing to trade.");
        }
        if (quantity != null && amount != null) {
            throw new RobinhoodException("Set either Quantity (in shares) or Amount ($) on " + symbol
                    + ", not both - they would each name a different order size.");
        }
        if (quantity != null && quantity.signum() <= 0) {
            throw new RobinhoodException("Quantity must be greater than zero - got " + quantity + ".");
        }
        if (amount != null && amount.signum() <= 0) {
            throw new RobinhoodException("Amount ($) must be greater than zero - got " + amount + ".");
        }
        if (amount != null && side == OrderSide.SELL && type != OrderType.MARKET) {
            // A dollar amount is turned into a share count from the current price, which is only
            // honest for an order that fills at roughly that price. A limit sell of "$500 worth"
            // would be a share count derived from a price the order is specifically not taking.
            throw new RobinhoodException("Amount ($) works with market orders; for a "
                    + type.label() + " order, say how many shares to sell in Quantity.");
        }
        if (type != null && type.needsLimitPrice() && limitPrice == null) {
            throw new RobinhoodException("A " + type.label() + " order needs a Limit Price.");
        }
        if (type != null && type.needsStopPrice() && stopPrice == null) {
            throw new RobinhoodException("A " + type.label() + " order needs a Stop Price.");
        }
        if (limitPrice != null && limitPrice.signum() <= 0) {
            throw new RobinhoodException("Limit Price must be greater than zero - got " + limitPrice + ".");
        }
        if (stopPrice != null && stopPrice.signum() <= 0) {
            throw new RobinhoodException("Stop Price must be greater than zero - got " + stopPrice + ".");
        }
        return this;
    }

    /** Whether the size was given in dollars rather than shares. */
    public boolean isAmountBased() {
        return amount != null;
    }
}
