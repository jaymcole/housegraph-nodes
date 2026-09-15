package io.github.jaymcole.housegraph.plugins.alpaca;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The five order shapes Alpaca accepts for equities, and what each one needs filled in.
 * <p>
 * Unlike Robinhood's private API — where "stop limit" is a combination of two independent fields —
 * Alpaca has a single {@code type} with exactly these values, so this enum is a straight mapping.
 * What it adds is <b>which of a node's price fields each shape requires</b>, which is the part
 * {@link OrderRequest#validate()} checks before anything is sent.
 */
public enum OrderType {

    /** Fill now, at whatever the market is. Alpaca needs no price for one. */
    MARKET("market", "market"),

    /** Fill at {@code Limit Price} or better, or don't fill. */
    LIMIT("limit", "limit"),

    /** Do nothing until the price reaches {@code Stop Price}, then become a market order. */
    STOP("stop", "stop"),

    /** Do nothing until the price reaches {@code Stop Price}, then become a limit order. */
    STOP_LIMIT("stop limit", "stop_limit"),

    /**
     * A stop that follows the price up (or down), staying {@code Trail Price} dollars or
     * {@code Trail Percent} percent behind the best level the order has seen. Alpaca maintains the
     * trail itself, which is why this is one order rather than a graph that keeps replacing a stop.
     */
    TRAILING_STOP("trailing stop", "trailing_stop");

    private final String label;
    private final String wireValue;

    OrderType(String label, String wireValue) {
        this.label = label;
        this.wireValue = wireValue;
    }

    /** What a node's Order Type field accepts for this one. */
    public String label() {
        return label;
    }

    /** Alpaca's {@code type} field value. */
    public String wireValue() {
        return wireValue;
    }

    /** Whether a Limit Price is required for this shape. */
    public boolean needsLimitPrice() {
        return this == LIMIT || this == STOP_LIMIT;
    }

    /** Whether a Stop Price is required for this shape. */
    public boolean needsStopPrice() {
        return this == STOP || this == STOP_LIMIT;
    }

    /** Whether one of Trail Price / Trail Percent is required for this shape. */
    public boolean needsTrail() {
        return this == TRAILING_STOP;
    }

    /**
     * Reads an order type from what somebody typed. Underscores, hyphens and extra spaces are all
     * accepted for the two-word ones, because "stop_limit", "stop-limit" and "stop limit" are all
     * the same intent — and {@code stop_limit} is what Alpaca's own documentation prints.
     *
     * @param text the authored order type, or null/blank for {@link #MARKET}
     * @return the order type
     * @throws AlpacaException naming the alternatives, if {@code text} is not one of them
     */
    public static OrderType parse(String text) {
        if (text == null || text.isBlank()) {
            return MARKET;
        }
        String cleaned = text.trim().toLowerCase().replace('_', ' ').replace('-', ' ')
                .replaceAll("\\s+", " ");
        for (OrderType type : values()) {
            if (type.label.equals(cleaned)) {
                return type;
            }
        }
        throw new AlpacaException("Order Type must be one of " + names() + " - got '"
                + text.trim() + "'.");
    }

    private static String names() {
        return Arrays.stream(values()).map(type -> type.label).collect(Collectors.joining(", "));
    }
}
