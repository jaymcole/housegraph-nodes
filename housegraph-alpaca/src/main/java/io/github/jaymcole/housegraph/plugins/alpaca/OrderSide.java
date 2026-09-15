package io.github.jaymcole.housegraph.plugins.alpaca;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Which way an order goes. What the user types into a node's {@code Side} field lands here. */
public enum OrderSide {

    BUY("buy"),
    SELL("sell");

    private final String wireValue;

    OrderSide(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value Alpaca's {@code side} field takes. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Reads a side from what somebody typed, ignoring case and surrounding space.
     *
     * @param text the authored side
     * @return the side
     * @throws AlpacaException naming the alternatives, if {@code text} is not one of them - the
     *                         node's Side field is free text, so a typo has to fail loudly rather
     *                         than default to one of two options that move money in opposite
     *                         directions
     */
    public static OrderSide parse(String text) {
        if (text != null) {
            String cleaned = text.trim().toLowerCase();
            for (OrderSide side : values()) {
                if (side.wireValue.equals(cleaned)) {
                    return side;
                }
            }
        }
        throw new AlpacaException("Side must be one of " + names() + " - got "
                + (text == null || text.isBlank() ? "nothing" : "'" + text.trim() + "'") + ".");
    }

    /** Reads a side off a response, answering null for one this library doesn't know. */
    static OrderSide read(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim().toLowerCase();
        for (OrderSide side : values()) {
            if (side.wireValue.equals(cleaned)) {
                return side;
            }
        }
        // Never throws: an order that came back with a side this library doesn't know is still an
        // order worth reporting, and parse() exists to stop a *typo* placing the wrong trade, which
        // is not what is happening on the way back.
        return null;
    }

    private static String names() {
        return Arrays.stream(values()).map(side -> side.wireValue).collect(Collectors.joining(", "));
    }
}
