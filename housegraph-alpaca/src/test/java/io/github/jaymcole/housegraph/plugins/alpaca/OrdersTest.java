package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually goes on the wire for a given set of node inputs.
 * <p>
 * This is the highest-value unit test in the library: the payload is the thing that spends money,
 * and every field in it is one a person filled in on a node somewhere. A field that silently stops
 * being sent — or one that is sent rounded differently than the person typed it — is a bug nobody
 * notices until an order fills at the wrong price.
 */
class OrdersTest {

    private static OrderRequest request(OrderType type, BigDecimal quantity, BigDecimal notional,
                                        BigDecimal limit, BigDecimal stop, TimeInForce tif) {
        return new OrderRequest("AAPL", OrderSide.BUY, type, quantity, notional, limit, stop,
                null, null, tif, false).validate();
    }

    private static JSONObject payloadFor(OrderRequest request) {
        return Orders.payload(request, Orders.newClientOrderId());
    }

    @Test
    void aMarketOrderCarriesTheSizeTheTypeAndNoPrice() {
        JSONObject body = payloadFor(
                request(OrderType.MARKET, BigDecimal.valueOf(2), null, null, null, TimeInForce.DAY));

        assertEquals("AAPL", body.getString("symbol"));
        assertEquals("buy", body.getString("side"));
        assertEquals("market", body.getString("type"));
        assertEquals("day", body.getString("time_in_force"));
        assertEquals("2", body.getString("qty"));
        assertFalse(body.has("notional"), "a share-denominated order must not also send notional");
        // Alpaca does not collar market orders the way Robinhood's private API does, so sending a
        // price here would be sending a limit the person never asked for.
        assertFalse(body.has("limit_price"), body.toString());
        assertFalse(body.has("stop_price"), body.toString());
    }

    @Test
    void aDollarAmountIsSentAsNotionalAndNotConvertedToShares() {
        // The reason to prefer notional over dividing by a quote: Alpaca sizes it at execution, so
        // "$100 of AAPL" really is $100 rather than a share count rounded beforehand.
        JSONObject body = payloadFor(request(OrderType.MARKET, null, BigDecimal.valueOf(100.00),
                null, null, TimeInForce.DAY));

        assertEquals("100.00", body.getString("notional"));
        assertFalse(body.has("qty"), "a dollar-denominated order must not also send qty");
    }

    @Test
    void aLimitOrderCarriesItsLimitAndAStopLimitCarriesBoth() {
        JSONObject limit = payloadFor(request(OrderType.LIMIT, BigDecimal.ONE, null,
                BigDecimal.valueOf(190.25), null, TimeInForce.GTC));
        assertEquals("limit", limit.getString("type"));
        assertEquals("190.25", limit.getString("limit_price"));
        assertEquals("gtc", limit.getString("time_in_force"));

        JSONObject stopLimit = payloadFor(request(OrderType.STOP_LIMIT, BigDecimal.ONE, null,
                BigDecimal.valueOf(190.25), BigDecimal.valueOf(191), TimeInForce.DAY));
        assertEquals("stop_limit", stopLimit.getString("type"));
        assertEquals("190.25", stopLimit.getString("limit_price"));
        assertEquals("191.00", stopLimit.getString("stop_price"));
    }

    @Test
    void aTrailingStopSendsWhicheverTrailWasFilledIn() {
        JSONObject byPrice = payloadFor(new OrderRequest("AAPL", OrderSide.SELL,
                OrderType.TRAILING_STOP, BigDecimal.ONE, null, null, null, BigDecimal.valueOf(1.5),
                null, TimeInForce.GTC, false).validate());
        assertEquals("trailing_stop", byPrice.getString("type"));
        assertEquals("1.50", byPrice.getString("trail_price"));
        assertFalse(byPrice.has("trail_percent"));

        JSONObject byPercent = payloadFor(new OrderRequest("AAPL", OrderSide.SELL,
                OrderType.TRAILING_STOP, BigDecimal.ONE, null, null, null, null,
                BigDecimal.valueOf(5.0), TimeInForce.GTC, false).validate());
        assertEquals("5", byPercent.getString("trail_percent"));
        assertFalse(byPercent.has("trail_price"));
    }

    @Test
    void pricesAreRoundedToTheTickAlpacaAccepts() {
        // Alpaca rejects a limit price with more precision than the tick outright, so rounding here
        // is the difference between an order and a 422. It happens before the dry run reports it,
        // so what the summary says is what would be sent.
        JSONObject dollarsAndUp = payloadFor(request(OrderType.LIMIT, BigDecimal.ONE, null,
                new BigDecimal("190.2549"), null, TimeInForce.DAY));
        assertEquals("190.25", dollarsAndUp.getString("limit_price"));

        JSONObject subDollar = payloadFor(request(OrderType.LIMIT, BigDecimal.ONE, null,
                new BigDecimal("0.123456"), null, TimeInForce.DAY));
        assertEquals("0.1235", subDollar.getString("limit_price"), "sub-dollar ticks are 4 places");
    }

    @Test
    void fractionalSharesSurviveAndWholeOnesDoNotGrowATail() {
        assertEquals("0.5", Orders.quantity(new BigDecimal("0.500")).toPlainString());
        assertEquals("20", Orders.quantity(new BigDecimal("20.00")).toPlainString(),
                "stripTrailingZeros turns 20.00 into 2E+1; a share count must not read like that");
        assertEquals("1.123456789",
                Orders.quantity(new BigDecimal("1.1234567891")).toPlainString(),
                "Alpaca takes nine decimal places, and rounds down rather than up");
    }

    @Test
    void everyOrderCarriesAFreshClientOrderId() {
        // Alpaca refuses to reuse one, which is what stops a re-sent request becoming a second
        // trade - and what makes a genuinely new order need a new id.
        String first = Orders.newClientOrderId();
        String second = Orders.newClientOrderId();
        assertTrue(first.startsWith("housegraph-"), first);
        assertFalse(first.equals(second), "two prepared orders must not share an id");

        JSONObject body = payloadFor(
                request(OrderType.MARKET, BigDecimal.ONE, null, null, null, TimeInForce.DAY));
        assertTrue(body.getString("client_order_id").startsWith("housegraph-"));
    }

    @Test
    void anOrderIsValuedAtThePriceItIsLikelyToTradeAt() {
        // A limit order at its limit, a stop at its stop, a market order at the current price - and
        // a dollar order at the dollars, with no price needed at all.
        assertEquals(380.50, Orders.estimatedValue(request(OrderType.LIMIT, BigDecimal.valueOf(2),
                null, BigDecimal.valueOf(190.25), null, TimeInForce.DAY), 500.0), 0.0001,
                "a limit order valued off a quote rather than its own limit would be nonsense");
        assertEquals(400.0, Orders.estimatedValue(request(OrderType.STOP, BigDecimal.valueOf(2),
                null, null, BigDecimal.valueOf(200), TimeInForce.DAY), 500.0), 0.0001);
        assertEquals(1000.0, Orders.estimatedValue(request(OrderType.MARKET, BigDecimal.valueOf(2),
                null, null, null, TimeInForce.DAY), 500.0), 0.0001);
        assertEquals(100.0, Orders.estimatedValue(request(OrderType.MARKET, null,
                BigDecimal.valueOf(100), null, null, TimeInForce.DAY), null), 0.0001);
    }

    @Test
    void aMarketOrderWithNoPriceCannotBeValued() {
        // Which is what makes Max Order Value ($) fail the node rather than let the order through:
        // an unchecked cap is not a cap.
        assertNull(Orders.estimatedValue(request(OrderType.MARKET, BigDecimal.valueOf(2), null,
                null, null, TimeInForce.DAY), null));
    }

    @Test
    void theSummaryOfAPreparedOrderSaysWhatWouldBeSent() {
        PreparedOrder shares = new PreparedOrder(
                request(OrderType.MARKET, BigDecimal.valueOf(2), null, null, null, TimeInForce.DAY),
                190.25, new JSONObject());
        assertEquals("buy 2 AAPL (market, day) ~$380.50", shares.describe());

        PreparedOrder dollars = new PreparedOrder(
                request(OrderType.MARKET, null, BigDecimal.valueOf(100), null, null, TimeInForce.DAY),
                190.25, new JSONObject());
        assertEquals("buy $100.00 of AAPL (market, day)", dollars.describe());
    }
}
