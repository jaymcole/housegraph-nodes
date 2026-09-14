package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually goes on the wire when an order is placed.
 * <p>
 * This is the most consequential code in the library and the least visible: a wrong field name is a
 * rejected order, a wrong collar is an order that silently never fills, and a quantity written as
 * {@code 3.0000000000000004} is both. None of that is apparent from reading a node.
 */
class OrdersTest {

    private static final String ACCOUNT = "https://stub/accounts/123/";
    private static final String INSTRUMENT = "https://stub/instruments/AAPL/";

    private static OrderRequest buy(OrderType type, String quantity, String limit, String stop) {
        return new OrderRequest("AAPL", OrderSide.BUY, type,
                quantity == null ? null : new BigDecimal(quantity), null,
                limit == null ? null : new BigDecimal(limit),
                stop == null ? null : new BigDecimal(stop),
                TimeInForce.GTC, false);
    }

    @Test
    void marketOrdersCarryACollarAboveTheMarketForABuy() {
        // Robinhood requires a price on a market order and treats it as the worst acceptable one.
        // A buy's collar has to be ABOVE the market or the order cannot fill at all.
        JSONObject payload = Orders.payload(buy(OrderType.MARKET, "2", null, null),
                ACCOUNT, INSTRUMENT, new BigDecimal("2"), 100.0);

        assertEquals("market", payload.getString("type"));
        assertEquals("immediate", payload.getString("trigger"));
        assertEquals("105.00", payload.getString("price"));
    }

    @Test
    void marketOrdersCollarBelowTheMarketForASell() {
        OrderRequest sell = new OrderRequest("AAPL", OrderSide.SELL, OrderType.MARKET,
                new BigDecimal("2"), null, null, null, TimeInForce.GTC, false);

        JSONObject payload = Orders.payload(sell, ACCOUNT, INSTRUMENT, new BigDecimal("2"), 100.0);

        assertEquals("95.00", payload.getString("price"));
    }

    @Test
    void limitOrdersSendTheAuthoredLimitAndNotACollar() {
        JSONObject payload = Orders.payload(buy(OrderType.LIMIT, "1", "187.5", null),
                ACCOUNT, INSTRUMENT, new BigDecimal("1"), 200.0);

        assertEquals("limit", payload.getString("type"));
        assertEquals("immediate", payload.getString("trigger"));
        assertEquals("187.50", payload.getString("price"));
    }

    @Test
    void stopOrdersSendBothPricesAndTheStopTrigger() {
        JSONObject payload = Orders.payload(buy(OrderType.STOP_LIMIT, "1", "190", "188"),
                ACCOUNT, INSTRUMENT, new BigDecimal("1"), 200.0);

        assertEquals("limit", payload.getString("type"));
        assertEquals("stop", payload.getString("trigger"));
        assertEquals("190.00", payload.getString("price"));
        assertEquals("188.00", payload.getString("stop_price"));
    }

    @Test
    void stopLossOrdersAreMarketOrdersWithAStopOnThem() {
        JSONObject payload = Orders.payload(buy(OrderType.STOP_LOSS, "1", null, "188"),
                ACCOUNT, INSTRUMENT, new BigDecimal("1"), 200.0);

        assertEquals("market", payload.getString("type"));
        assertEquals("stop", payload.getString("trigger"));
        assertEquals("188.00", payload.getString("stop_price"));
    }

    @Test
    void everyOrderCarriesAFreshIdempotencyKey() {
        // ref_id is what makes a re-sent request the same order rather than a second one - and two
        // DIFFERENT orders sharing one would be worse than none at all.
        JSONObject first = Orders.payload(buy(OrderType.MARKET, "1", null, null),
                ACCOUNT, INSTRUMENT, new BigDecimal("1"), 100.0);
        JSONObject second = Orders.payload(buy(OrderType.MARKET, "1", null, null),
                ACCOUNT, INSTRUMENT, new BigDecimal("1"), 100.0);

        assertFalse(first.getString("ref_id").isBlank());
        assertNotEquals(first.getString("ref_id"), second.getString("ref_id"));
    }

    @Test
    void namesTheAccountAndInstrumentByUrl() {
        JSONObject payload = Orders.payload(buy(OrderType.MARKET, "1", null, null),
                ACCOUNT, INSTRUMENT, new BigDecimal("1"), 100.0);

        assertEquals(ACCOUNT, payload.getString("account"));
        assertEquals(INSTRUMENT, payload.getString("instrument"));
        assertEquals("AAPL", payload.getString("symbol"));
        assertEquals("buy", payload.getString("side"));
        assertEquals("gtc", payload.getString("time_in_force"));
        assertFalse(payload.getBoolean("extended_hours"));
    }

    @Test
    void sendsQuantityAsPlainTextRatherThanScientificNotation() {
        // A fractional order of 0.000001 shares is 1E-6 through toString(), which Robinhood rejects.
        JSONObject payload = Orders.payload(buy(OrderType.MARKET, "0.000001", null, null),
                ACCOUNT, INSTRUMENT, new BigDecimal("0.000001"), 100.0);

        assertEquals("0.000001", payload.getString("quantity"));
    }

    @Test
    void dollarAmountsBecomeAShareCountRoundedDown() {
        OrderRequest hundredDollars = new OrderRequest("AAPL", OrderSide.BUY, OrderType.MARKET,
                null, new BigDecimal("100"), null, null, TimeInForce.GTC, false);

        // 100 / 3 is 33.333333..., and rounding UP would spend more than the amount asked for.
        assertEquals(new BigDecimal("33.333333"), Orders.quantityFor(hundredDollars, 3.0));
    }

    @Test
    void refusesADollarAmountWithNoPriceToDivideBy() {
        OrderRequest hundredDollars = new OrderRequest("AAPL", OrderSide.BUY, OrderType.MARKET,
                null, new BigDecimal("100"), null, null, TimeInForce.GTC, false);

        RobinhoodException thrown =
                assertThrows(RobinhoodException.class, () -> Orders.quantityFor(hundredDollars, null));
        assertTrue(thrown.getMessage().contains("no current price"), thrown.getMessage());
    }

    @Test
    void refusesADollarAmountTooSmallToBuyAnything() {
        OrderRequest oneCent = new OrderRequest("BRK.A", OrderSide.BUY, OrderType.MARKET,
                null, new BigDecimal("0.01"), null, null, TimeInForce.GTC, false);

        assertThrows(RobinhoodException.class, () -> Orders.quantityFor(oneCent, 700000.0));
    }

    @Test
    void shareCountsAreUsedExactlyAsWritten() {
        OrderRequest twoShares = buy(OrderType.MARKET, "2", null, null);

        assertEquals(0, new BigDecimal("2").compareTo(Orders.quantityFor(twoShares, 100.0)));
    }

    @Test
    void sendsNoPriceForAMarketOrderNobodyCouldQuote() {
        // Better to let Robinhood decide than to invent a collar out of nothing.
        assertNull(Orders.priceFor(buy(OrderType.MARKET, "1", null, null), null));
    }
}
