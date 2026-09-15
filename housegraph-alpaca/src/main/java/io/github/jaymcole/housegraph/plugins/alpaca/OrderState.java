package io.github.jaymcole.housegraph.plugins.alpaca;

/**
 * Where an order has got to, and — the part nodes actually branch on — whether it is still going
 * anywhere.
 * <p>
 * Alpaca publishes eighteen of these, most of which a graph never sees. What a node needs from any
 * of them is three answers: is it finished, did it fill, was it refused. So each constant carries
 * them rather than every node re-deriving them from a string.
 *
 * <h2>{@link #UNKNOWN}, and why {@link #DONE_FOR_DAY} is not terminal</h2>
 * A state this library has not seen is reported as Alpaca spelled it and treated as <b>still
 * running</b>, which is the safe reading: a node waiting on a fill keeps waiting rather than
 * announcing one that did not happen.
 * <p>
 * {@code done_for_day} gets the same treatment for a less obvious reason. It means the order will
 * not trade again <em>today</em> — but a good-till-cancelled order in that state is back at the
 * exchange tomorrow morning, so calling it finished would tell a graph the order was over when it
 * was not. An order that really is finished reaches one of the five terminal states below.
 */
public enum OrderState {

    /** Accepted by Alpaca, not yet routed. */
    NEW("new", false, false),
    /** Received, not yet accepted - the first moment of an order's life. */
    PENDING_NEW("pending_new", false, false),
    /** Routed and live at the exchange. */
    ACCEPTED("accepted", false, false),
    /** Live in an opening or closing auction. */
    ACCEPTED_FOR_BIDDING("accepted_for_bidding", false, false),
    /** Held by Alpaca for a compliance check before routing. */
    PENDING_REVIEW("pending_review", false, false),
    /** Some of the shares are done; the rest are still working. */
    PARTIALLY_FILLED("partially_filled", false, false),
    /** A cancel has been asked for and the exchange has not agreed to it yet. */
    PENDING_CANCEL("pending_cancel", false, false),
    /** A replacement has been asked for and not yet applied. */
    PENDING_REPLACE("pending_replace", false, false),
    /** Waiting on a corporate action or a settlement before it can work. */
    HELD("held", false, false),
    /** Priced in an auction but not yet executed. */
    CALCULATED("calculated", false, false),
    /** Stopped: a trade is agreed and is being reported. */
    STOPPED("stopped", false, false),
    /** Suspended by Alpaca; it may resume. */
    SUSPENDED("suspended", false, false),
    /** Finished trading for today, but a gtc order is back tomorrow - see the class note. */
    DONE_FOR_DAY("done_for_day", false, false),

    /** Done. Every share asked for was bought or sold. */
    FILLED("filled", true, true),
    /** Cancelled, by a Cancel Order node, in Alpaca's dashboard, or at the close for a day order. */
    CANCELLED("canceled", true, false),
    /** The time in force ran out before it filled. */
    EXPIRED("expired", true, false),
    /** Superseded by a replacement order, which carries its own id. */
    REPLACED("replaced", true, false),
    /** Alpaca or the exchange refused it. */
    REJECTED("rejected", true, false),

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

    /** Alpaca's own spelling of this state. */
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

    /** Whether the order was refused, as opposed to cancelled, expired or filled. */
    public boolean isRejected() {
        return this == REJECTED;
    }

    /** Whether a cancel is worth asking for: the order is not finished and one isn't already in. */
    public boolean isCancellable() {
        return !terminal && this != PENDING_CANCEL;
    }

    /**
     * Reads a state from the wire, answering {@link #UNKNOWN} for anything unrecognised rather than
     * throwing. Alpaca spells it "canceled"; both spellings land on {@link #CANCELLED}.
     *
     * @param text the {@code status} field of an order, possibly null
     * @return the state, never null
     */
    public static OrderState parse(String text) {
        if (text == null) {
            return UNKNOWN;
        }
        String cleaned = text.trim().toLowerCase();
        if ("cancelled".equals(cleaned)) {
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
