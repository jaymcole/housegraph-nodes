package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaException;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;

import java.math.BigDecimal;

/**
 * The readings every node in this library has to make, written once.
 * <p>
 * They exist because an <em>unset</em> port and a <em>wrongly set</em> one need different answers,
 * and getting that wrong in a trading node is expensive. An empty Limit Price means "this order
 * shape doesn't use one"; a Limit Price of "12.O0" means somebody typed a letter and the order must
 * not go anywhere near Alpaca. Reading them field by field inside each node is how the two end up
 * conflated.
 * <p>
 * Public for the same reason {@link Status} is: the nodes live in subpackages of this one.
 */
public final class Inputs {

    private Inputs() {
    }

    /**
     * The session an action node was wired to.
     *
     * @param account the node's Account input
     * @return the session, connected
     * @throws AlpacaException if nothing is wired in, or what is wired in is not connected - both of
     *                         which are the node being unable to run at all, rather than the call it
     *                         was about to make failing
     */
    public static AlpacaSession session(NodeVariable<AlpacaSession> account) {
        AlpacaSession session = account.getValue();
        if (session == null) {
            throw new AlpacaException("No Alpaca account is wired into this node's Account input. "
                    + "Drop an Alpaca Account node (or an Alpaca Account Ref) and wire its Account "
                    + "output in.");
        }
        if (!session.isConnected()) {
            throw new AlpacaException("The Alpaca account this node is wired to is not connected. "
                    + "Press Connect on it, or wire something into its Connect port.");
        }
        return session;
    }

    /**
     * A symbol, upper-cased and checked for being there at all.
     *
     * @param symbol the node's Symbol input
     * @return the ticker
     * @throws AlpacaException if the input is empty
     */
    public static String symbol(NodeVariable<String> symbol) {
        String value = symbol.getValue();
        if (value == null || value.isBlank()) {
            throw new AlpacaException("Symbol is empty - name the stock or ETF, e.g. AAPL.");
        }
        return value.trim().toUpperCase();
    }

    /**
     * An optional money or quantity field as an exact decimal.
     * <p>
     * <b>{@link BigDecimal}, not the {@code double} the port carries.</b> The value goes into an
     * order payload as text, and {@code BigDecimal.valueOf(0.1 + 0.2)} is the difference between
     * sending "0.3" and sending "0.30000000000000004" - which Alpaca rejects.
     *
     * @param input the port
     * @return the value, or null when the port is empty
     * @throws AlpacaException if the port holds something that is not a usable number
     */
    public static BigDecimal decimal(NodeVariable<Double> input) {
        Double value = input.getValue();
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value.isInfinite()) {
            throw new AlpacaException(input.name + " is not a usable number (" + value + ").");
        }
        // Through the string form on purpose: new BigDecimal(0.1) is 0.1000000000000000055511...,
        // which would reach Alpaca as written.
        return new BigDecimal(Double.toString(value)).stripTrailingZeros();
    }

    /**
     * A text field with a fallback, for the ones where blank means "the usual".
     *
     * @param input    the port
     * @param fallback what a blank port means
     * @return the trimmed text, or {@code fallback}
     */
    public static String textOr(NodeVariable<String> input, String fallback) {
        String value = input.getValue();
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /**
     * A whole-number field with a fallback and a floor of one, for "how many" ports.
     *
     * @param input    the port
     * @param fallback what an empty port means
     * @return the count, at least 1
     */
    public static int countOr(NodeVariable<Integer> input, int fallback) {
        Integer value = input.getValue();
        int count = value == null ? fallback : value;
        return Math.max(1, count);
    }
}
