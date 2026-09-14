package io.github.jaymcole.housegraph.plugins.robinhood;

import java.util.Arrays;
import java.util.stream.Collectors;

/** How long an order stays live before Robinhood gives up on it. */
public enum TimeInForce {

    /** Good till cancelled: stays open across days until it fills or is cancelled. */
    GTC("gtc"),

    /** Good for day: cancelled by Robinhood at the close if it hasn't filled. */
    GFD("gfd"),

    /** Immediate or cancel: fill what can be filled at once, cancel the rest. */
    IOC("ioc"),

    /** At the open: takes part in the opening auction, cancelled if it misses it. */
    OPG("opg");

    private final String wireValue;

    TimeInForce(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value Robinhood's {@code time_in_force} field takes. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Reads a time in force from what somebody typed, defaulting to {@link #GTC} for a blank field.
     *
     * @param text the authored value, or null/blank for {@link #GTC}
     * @return the time in force
     * @throws RobinhoodException naming the alternatives, if {@code text} is not one of them
     */
    public static TimeInForce parse(String text) {
        if (text == null || text.isBlank()) {
            return GTC;
        }
        String cleaned = text.trim().toLowerCase();
        for (TimeInForce value : values()) {
            if (value.wireValue.equals(cleaned)) {
                return value;
            }
        }
        throw new RobinhoodException("Time In Force must be one of " + names() + " - got '"
                + text.trim() + "'.");
    }

    private static String names() {
        return Arrays.stream(values()).map(value -> value.wireValue).collect(Collectors.joining(", "));
    }
}
