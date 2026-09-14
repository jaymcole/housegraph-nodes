package io.github.jaymcole.housegraph.plugins.robinhood;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The four order shapes this library places, and how each one is spelled on the wire.
 * <p>
 * Robinhood does not have an "order type" field with four values. It has two independent ones — a
 * {@code type} of {@code market} or {@code limit}, and a {@code trigger} of {@code immediate} or
 * {@code stop} — and the four useful combinations are what a person actually means by "stop limit".
 * Keeping that mapping here rather than on the node means a node's {@code Order Type} field can say
 * the words a broker's own UI uses.
 */
public enum OrderType {

    /** Fill now, at whatever the market is. Carries a collar price - see {@link Orders#payload}. */
    MARKET("market", "market", "immediate", false),

    /** Fill at {@code Limit Price} or better, or don't fill. */
    LIMIT("limit", "limit", "immediate", false),

    /** Do nothing until the price reaches {@code Stop Price}, then become a market order. */
    STOP_LOSS("stop loss", "market", "stop", true),

    /** Do nothing until the price reaches {@code Stop Price}, then become a limit order. */
    STOP_LIMIT("stop limit", "limit", "stop", true);

    private final String label;
    private final String wireType;
    private final String wireTrigger;
    private final boolean needsStopPrice;

    OrderType(String label, String wireType, String wireTrigger, boolean needsStopPrice) {
        this.label = label;
        this.wireType = wireType;
        this.wireTrigger = wireTrigger;
        this.needsStopPrice = needsStopPrice;
    }

    /** What a node's Order Type field accepts for this one. */
    public String label() {
        return label;
    }

    /** Robinhood's {@code type}: market or limit. */
    public String wireType() {
        return wireType;
    }

    /** Robinhood's {@code trigger}: immediate or stop. */
    public String wireTrigger() {
        return wireTrigger;
    }

    /** Whether a Stop Price is required for this shape. */
    public boolean needsStopPrice() {
        return needsStopPrice;
    }

    /** Whether a Limit Price is required for this shape. */
    public boolean needsLimitPrice() {
        return "limit".equals(wireType);
    }

    /**
     * Reads an order type from what somebody typed. Underscores, hyphens and extra spaces are all
     * accepted for the two-word ones, because "stop_limit", "stop-limit" and "stop limit" are all
     * the same intent.
     *
     * @param text the authored order type, or null/blank for {@link #MARKET}
     * @return the order type
     * @throws RobinhoodException naming the alternatives, if {@code text} is not one of them
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
        throw new RobinhoodException("Order Type must be one of " + names() + " - got '"
                + text.trim() + "'.");
    }

    private static String names() {
        return Arrays.stream(values()).map(type -> type.label).collect(Collectors.joining(", "));
    }
}
