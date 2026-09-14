package io.github.jaymcole.housegraph.plugins.robinhood;

/**
 * Where an order has got to, and — the part nodes actually branch on — whether it is still going
 * anywhere.
 * <p>
 * <b>{@link #UNKNOWN} exists because this is not a published API.</b> Robinhood has added states
 * before, and a client that threw on an unrecognised one would turn "there is a new state" into
 * "the graph stopped". An unknown state is treated as not finished, which is the safe reading: a
 * node waiting on it keeps waiting and keeps reporting the raw text, rather than announcing a fill
 * that did not happen.
 */
public enum OrderState {

    /** Accepted by Robinhood, not yet sent to the exchange (outside market hours, usually). */
    QUEUED("queued", false, false),
    /** Sent, not yet acknowledged. */
    UNCONFIRMED("unconfirmed", false, false),
    /** Live at the exchange, waiting for a price. */
    CONFIRMED("confirmed", false, false),
    /** Some of the shares are done; the rest are still working. */
    PARTIALLY_FILLED("partially_filled", false, false),
    /** Done. Every share asked for was bought or sold. */
    FILLED("filled", true, true),
    /** Cancelled, by a Cancel Order node, by the app, or by Robinhood at the close. */
    CANCELLED("cancelled", true, false),
    /** Robinhood would not accept it - {@code reject_reason} says why. */
    REJECTED("rejected", true, false),
    /** Accepted and then broken on the way to the exchange. */
    FAILED("failed", true, false),
    /** Undone after the fact, e.g. a fill busted by the exchange. */
    VOIDED("voided", true, false),
    /** A state this library has not seen before. Treated as still running - see the class note. */
    UNKNOWN("unknown", false, false);

    private final String wireValue;
    private final boolean terminal;
    private final boolean filled;

    OrderState(String wireValue, boolean terminal, boolean filled) {
        this.wireValue = wireValue;
        this.terminal = terminal;
        this.filled = filled;
    }

    /** Robinhood's own spelling of this state. */
    public String wireValue() {
        return wireValue;
    }

    /** Whether the order has finished and will not change again. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Whether the order completed in full. */
    public boolean isFilled() {
        return filled;
    }

    /** Whether Robinhood refused or broke the order, as opposed to it being cancelled or filled. */
    public boolean isRejected() {
        return this == REJECTED || this == FAILED || this == VOIDED;
    }

    /**
     * Reads a state from the wire, answering {@link #UNKNOWN} for anything unrecognised rather than
     * throwing. Robinhood spells cancelled "canceled"; both spellings land on {@link #CANCELLED}.
     *
     * @param text the {@code state} field of an order, possibly null
     * @return the state, never null
     */
    public static OrderState parse(String text) {
        if (text == null) {
            return UNKNOWN;
        }
        String cleaned = text.trim().toLowerCase();
        if ("canceled".equals(cleaned)) {
            return CANCELLED;
        }
        for (OrderState state : values()) {
            if (state.wireValue.equals(cleaned)) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
