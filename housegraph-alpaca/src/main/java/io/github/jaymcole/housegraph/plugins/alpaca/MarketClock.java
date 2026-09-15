package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

/**
 * Whether the US equity market is open, and when it next isn't.
 * <p>
 * <b>This is the answer that cannot be worked out locally.</b> A graph could compare the time
 * against 9:30 and 4:00 Eastern and be wrong nine or ten times a year — market holidays, the half
 * days around Thanksgiving and Christmas, the occasional unscheduled close — and each of those
 * would be a day on which a strategy quietly did nothing, or worse, queued orders it thought were
 * going in now. Alpaca publishes the real calendar, so this library asks.
 *
 * @param timestamp when Alpaca answered, as the ISO-8601 text it sent
 * @param open      whether the market is open for regular trading right now
 * @param nextOpen  when it next opens, as ISO-8601 text
 * @param nextClose when it next closes, as ISO-8601 text
 */
public record MarketClock(String timestamp, boolean open, String nextOpen, String nextClose) {

    /** One line for a node's status label. */
    public String describe() {
        if (open) {
            return nextClose == null ? "Market is open" : "Market is open until " + nextClose;
        }
        return nextOpen == null ? "Market is closed" : "Market is closed until " + nextOpen;
    }

    /**
     * Reads {@code GET /v2/clock}.
     *
     * @param body the clock
     * @return the clock
     */
    static MarketClock from(JSONObject body) {
        return new MarketClock(
                Json.string(body, "timestamp"),
                Json.bool(body, "is_open", false),
                Json.string(body, "next_open"),
                Json.string(body, "next_close"));
    }
}
