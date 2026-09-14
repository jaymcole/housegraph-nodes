package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which number a quote calls "the price", which is the one everything downstream - a collar, a
 * dollar-amount order, a graph deciding whether to sell - is worked out from.
 */
class QuoteTest {

    @Test
    void thePriceIsTheLastTradeDuringMarketHours() {
        Quote quote = Quote.from("AAPL", new JSONObject()
                .put("last_trade_price", "180.00")
                .put("previous_close", "178.00"));

        assertEquals(180.0, quote.price());
        assertEquals(2.0, quote.changeFromPreviousClose());
    }

    @Test
    void thePriceIsTheExtendedHoursOneWhenTheMarketIsClosed() {
        // The bug this prevents: pricing a 7pm order off the 4pm close, which is exactly what
        // reading last_trade_price alone would do.
        Quote quote = Quote.from("AAPL", new JSONObject()
                .put("last_trade_price", "180.00")
                .put("last_extended_hours_trade_price", "182.50")
                .put("previous_close", "178.00"));

        assertEquals(182.5, quote.price());
        assertEquals(180.0, quote.lastTradePrice());
        assertEquals(4.5, quote.changeFromPreviousClose());
    }

    @Test
    void aSymbolThatHasNotTradedTodayHasNoPriceRatherThanAZeroOne() {
        Quote quote = Quote.from("NEWCO", new JSONObject().put("symbol", "NEWCO"));

        assertNull(quote.price());
        assertNull(quote.changeFromPreviousClose());
    }

    @Test
    void readsTheHaltedFlag() {
        Quote quote = Quote.from("AAPL", new JSONObject()
                .put("last_trade_price", "180.00")
                .put("trading_halted", true));

        assertTrue(quote.tradingHalted());
    }
}
