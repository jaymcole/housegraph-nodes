package io.github.jaymcole.housegraph.plugins.alpaca;

import java.math.BigDecimal;

/**
 * An order as the person authored it, before anything has been looked up or sent.
 * <p>
 * <b>Everything that can be wrong with an order is caught here</b>, in {@link #validate()}, before
 * a single call goes out: a blank symbol, a negative quantity, a limit order with no limit price,
 * both a share count and a dollar amount. That ordering is the point — a validation failure costs
 * nothing and says exactly which field to fix, while the same mistake caught by Alpaca costs a
 * round trip and comes back as a 422 naming a JSON field rather than a node port.
 *
 * <h2>Three of these rules are Alpaca's, not this library's</h2>
 * They look arbitrary from inside a graph, so they are worth knowing about before the order is
 * written:
 * <ul>
 *   <li><b>A dollar amount only works on a market order, good for the day.</b> Alpaca sizes a
 *       {@code notional} order at execution, which it can only do when the order is taking the
 *       market price today.</li>
 *   <li><b>An extended-hours order must be a limit order, good for the day.</b> There is no
 *       continuous auction outside regular hours for a market order to be filled against.</li>
 *   <li><b>A trailing stop needs exactly one of Trail Price and Trail Percent.</b> Two trails would
 *       be two different stops.</li>
 * </ul>
 *
 * @param symbol        the ticker to trade, upper-cased
 * @param side          buy or sell
 * @param type          market, limit, stop, stop limit or trailing stop
 * @param quantity      shares to trade, or null when {@code notional} says it in dollars instead
 * @param notional      dollars to trade, or null when {@code quantity} says it in shares
 * @param limitPrice    the limit price, required for the limit shapes
 * @param stopPrice     the stop price, required for the stop shapes
 * @param trailPrice    how many dollars behind the high-water mark a trailing stop follows
 * @param trailPercent  how many percent behind it follows, as an alternative to {@code trailPrice}
 * @param timeInForce   how long the order stays live
 * @param extendedHours whether the order may fill in the pre/post-market session
 */
public record OrderRequest(String symbol,
                           OrderSide side,
                           OrderType type,
                           BigDecimal quantity,
                           BigDecimal notional,
                           BigDecimal limitPrice,
                           BigDecimal stopPrice,
                           BigDecimal trailPrice,
                           BigDecimal trailPercent,
                           TimeInForce timeInForce,
                           boolean extendedHours) {

    /**
     * Checks the combination, throwing with the name of the field to fix.
     *
     * @return this request, so it can be validated inline
     * @throws AlpacaException if the order could not be placed as authored
     */
    public OrderRequest validate() {
        if (symbol == null || symbol.isBlank()) {
            throw new AlpacaException("Symbol is empty - name the stock or ETF to trade, e.g. AAPL.");
        }
        if (side == null) {
            throw new AlpacaException("Side is empty - it must be buy or sell.");
        }
        if (type == null) {
            throw new AlpacaException("Order Type is empty on " + symbol + ".");
        }
        if (timeInForce == null) {
            throw new AlpacaException("Time In Force is empty on " + symbol + ".");
        }

        checkSize();
        checkPrices();
        checkTrail();

        if (extendedHours && (type != OrderType.LIMIT || timeInForce != TimeInForce.DAY)) {
            throw new AlpacaException("Alpaca only fills extended-hours orders as limit orders good "
                    + "for the day. Set Order Type to limit and Time In Force to day on " + symbol
                    + ", or turn Extended Hours off.");
        }
        return this;
    }

    private void checkSize() {
        if (quantity == null && notional == null) {
            throw new AlpacaException("Set either Quantity (in shares) or Amount ($) on " + symbol
                    + " - neither is filled in, so there is nothing to trade.");
        }
        if (quantity != null && notional != null) {
            throw new AlpacaException("Set either Quantity (in shares) or Amount ($) on " + symbol
                    + ", not both - they would each name a different order size.");
        }
        if (quantity != null && quantity.signum() <= 0) {
            throw new AlpacaException("Quantity must be greater than zero - got " + quantity + ".");
        }
        if (notional == null) {
            return;
        }
        if (notional.signum() <= 0) {
            throw new AlpacaException("Amount ($) must be greater than zero - got " + notional + ".");
        }
        if (type != OrderType.MARKET) {
            throw new AlpacaException("Alpaca only accepts an Amount ($) on a market order; for a "
                    + type.label() + " order on " + symbol + ", say how many shares in Quantity.");
        }
        if (timeInForce != TimeInForce.DAY) {
            throw new AlpacaException("Alpaca only accepts an Amount ($) on an order good for the "
                    + "day. Set Time In Force to day on " + symbol + ", or say how many shares in "
                    + "Quantity instead.");
        }
    }

    private void checkPrices() {
        if (type.needsLimitPrice() && limitPrice == null) {
            throw new AlpacaException("A " + type.label() + " order needs a Limit Price.");
        }
        if (type.needsStopPrice() && stopPrice == null) {
            throw new AlpacaException("A " + type.label() + " order needs a Stop Price.");
        }
        if (limitPrice != null && limitPrice.signum() <= 0) {
            throw new AlpacaException("Limit Price must be greater than zero - got " + limitPrice + ".");
        }
        if (stopPrice != null && stopPrice.signum() <= 0) {
            throw new AlpacaException("Stop Price must be greater than zero - got " + stopPrice + ".");
        }
    }

    private void checkTrail() {
        boolean hasTrail = trailPrice != null || trailPercent != null;
        if (type.needsTrail() && !hasTrail) {
            throw new AlpacaException("A trailing stop order needs either a Trail Price (how many "
                    + "dollars behind the best price it follows) or a Trail Percent.");
        }
        if (!type.needsTrail() && hasTrail) {
            // Silently ignoring them would be worse: the order would go in with no trail at all,
            // and the field that was filled in would look like it had done something.
            throw new AlpacaException("Trail Price and Trail Percent only apply to a trailing stop "
                    + "order, and this is a " + type.label() + " order on " + symbol + ".");
        }
        if (trailPrice != null && trailPercent != null) {
            throw new AlpacaException("Set either Trail Price or Trail Percent on " + symbol
                    + ", not both - they would each name a different stop.");
        }
        if (trailPrice != null && trailPrice.signum() <= 0) {
            throw new AlpacaException("Trail Price must be greater than zero - got " + trailPrice + ".");
        }
        if (trailPercent != null
                && (trailPercent.signum() <= 0 || trailPercent.compareTo(BigDecimal.valueOf(100)) >= 0)) {
            throw new AlpacaException("Trail Percent must be between 0 and 100 - got "
                    + trailPercent + ".");
        }
    }

    /** Whether the size was given in dollars rather than shares. */
    public boolean isAmountBased() {
        return notional != null;
    }
}
