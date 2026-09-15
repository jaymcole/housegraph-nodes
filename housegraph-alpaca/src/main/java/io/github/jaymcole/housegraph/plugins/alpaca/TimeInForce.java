package io.github.jaymcole.housegraph.plugins.alpaca;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How long an order stays live before Alpaca gives up on it.
 * <p>
 * <b>{@link #DAY} is this library's default, where the Robinhood one defaults to good-till-cancelled.</b>
 * Two reasons, and both are Alpaca's rather than a preference: a dollar-denominated order
 * ({@code notional}) is only accepted with {@code day}, and an order left working overnight is the
 * one that fills at a price nobody looked at. A graph that wants a resting order says {@code gtc}
 * explicitly.
 */
public enum TimeInForce {

    /** Good for the day: cancelled at the close if it hasn't filled. The default here. */
    DAY("day"),

    /** Good till cancelled: stays open across days until it fills or is cancelled. */
    GTC("gtc"),

    /** At the open: takes part in the opening auction only, cancelled if it misses it. */
    OPG("opg"),

    /** At the close: takes part in the closing auction only. */
    CLS("cls"),

    /** Immediate or cancel: fill what can be filled at once, cancel the rest. */
    IOC("ioc"),

    /** Fill or kill: fill the whole order at once or cancel all of it. */
    FOK("fok");

    private final String wireValue;

    TimeInForce(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value Alpaca's {@code time_in_force} field takes. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Reads a time in force from what somebody typed, defaulting to {@link #DAY} for a blank field.
     *
     * @param text the authored value, or null/blank for {@link #DAY}
     * @return the time in force
     * @throws AlpacaException naming the alternatives, if {@code text} is not one of them
     */
    public static TimeInForce parse(String text) {
        if (text == null || text.isBlank()) {
            return DAY;
        }
        String cleaned = text.trim().toLowerCase();
        for (TimeInForce value : values()) {
            if (value.wireValue.equals(cleaned)) {
                return value;
            }
        }
        throw new AlpacaException("Time In Force must be one of " + names() + " - got '"
                + text.trim() + "'.");
    }

    private static String names() {
        return Arrays.stream(values()).map(value -> value.wireValue).collect(Collectors.joining(", "));
    }
}
