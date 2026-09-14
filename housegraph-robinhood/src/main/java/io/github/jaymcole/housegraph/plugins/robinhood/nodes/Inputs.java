package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodException;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;

import java.math.BigDecimal;

/**
 * The three readings every node in this library has to make, written once.
 * <p>
 * All three exist because an <em>unset</em> port and a <em>wrongly set</em> one need different
 * answers, and getting that wrong in a trading node is expensive. An empty Limit Price means "this
 * order shape doesn't use one"; a Limit Price of "12.O0" means somebody typed a letter and the
 * order must not go anywhere near Robinhood. Reading them field by field inside each node is how
 * the two end up conflated.
 */
final class Inputs {

    private Inputs() {
    }

    /**
     * The session an action node was wired to.
     *
     * @param account the node's Account input
     * @return the session, connected
     * @throws RobinhoodException if nothing is wired in, or what is wired in is not connected -
     *                            both of which are the node being unable to run at all, rather
     *                            than the call it was about to make failing
     */
    static RobinhoodSession session(NodeVariable<RobinhoodSession> account) {
        RobinhoodSession session = account.getValue();
        if (session == null) {
            throw new RobinhoodException("No Robinhood account is wired into this node's Account "
                    + "input. Drop a Robinhood Account node (or a Robinhood Account Ref) and wire "
                    + "its Account output in.");
        }
        if (!session.isConnected()) {
            throw new RobinhoodException("The Robinhood account this node is wired to is not "
                    + "connected. Press Connect on it, or wire something into its Connect port.");
        }
        return session;
    }

    /**
     * A symbol, upper-cased and checked for being there at all.
     *
     * @param symbol the node's Symbol input
     * @return the ticker
     * @throws RobinhoodException if the input is empty
     */
    static String symbol(NodeVariable<String> symbol) {
        String value = symbol.getValue();
        if (value == null || value.isBlank()) {
            throw new RobinhoodException("Symbol is empty - name the stock or ETF, e.g. AAPL.");
        }
        return value.trim().toUpperCase();
    }

    /**
     * An optional money or quantity field as an exact decimal.
     * <p>
     * <b>{@link BigDecimal}, not the {@code double} the port carries.</b> The value goes into an
     * order payload as text, and {@code BigDecimal.valueOf(0.1 + 0.2)} is the difference between
     * sending "0.3" and sending "0.30000000000000004" - which Robinhood rejects.
     *
     * @param input the port
     * @return the value, or null when the port is empty
     */
    static BigDecimal decimal(NodeVariable<Double> input) {
        Double value = input.getValue();
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value.isInfinite()) {
            throw new RobinhoodException(input.name + " is not a usable number (" + value + ").");
        }
        // Through the string form on purpose: new BigDecimal(0.1) is 0.1000000000000000055511...,
        // which would reach Robinhood as written.
        return new BigDecimal(Double.toString(value)).stripTrailingZeros();
    }
}
