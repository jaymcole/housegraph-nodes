package io.github.jaymcole.housegraph.plugins.alpaca;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way an order can be wrong, caught before anything is sent.
 * <p>
 * <b>These are the cheapest tests in the library and the ones most worth having.</b> Each case here
 * is a mistake that would otherwise cost a round trip and come back as a 422 naming a JSON field,
 * and three of them are Alpaca's own rules rather than this library's — the ones a person reading
 * the node's ports has no way to guess. The assertions are on the <em>message</em> as much as on the
 * throw: a validation failure whose message doesn't name the field to fix is barely better than the
 * 422.
 */
class OrderRequestTest {

    private static OrderRequest order(BigDecimal quantity, BigDecimal notional, OrderType type,
                                      TimeInForce tif) {
        return new OrderRequest("AAPL", OrderSide.BUY, type, quantity, notional,
                null, null, null, null, tif, false);
    }

    private static OrderRequest market(double shares) {
        return order(BigDecimal.valueOf(shares), null, OrderType.MARKET, TimeInForce.DAY);
    }

    @Test
    void aPlainMarketOrderIsFine() {
        OrderRequest request = market(2);
        assertSame(request, request.validate(), "validate() returns the request so it can chain");
    }

    @Test
    void sizeMustBeSaidExactlyOnce() {
        AlpacaException neither = assertThrows(AlpacaException.class,
                () -> order(null, null, OrderType.MARKET, TimeInForce.DAY).validate());
        assertTrue(neither.getMessage().contains("neither is filled in"), neither.getMessage());

        AlpacaException both = assertThrows(AlpacaException.class,
                () -> order(BigDecimal.ONE, BigDecimal.TEN, OrderType.MARKET, TimeInForce.DAY)
                        .validate());
        assertTrue(both.getMessage().contains("not both"), both.getMessage());
    }

    @Test
    void sizesMustBePositive() {
        assertThrows(AlpacaException.class, () -> market(0).validate());
        assertThrows(AlpacaException.class, () -> market(-1).validate());
        assertThrows(AlpacaException.class,
                () -> order(null, BigDecimal.valueOf(-5), OrderType.MARKET, TimeInForce.DAY)
                        .validate());
    }

    @Test
    void aDollarAmountOnlyWorksOnAMarketOrderGoodForTheDay() {
        // Alpaca's rule, not this library's: it sizes a notional order at execution, which it can
        // only do for an order taking the market price today.
        AlpacaException wrongType = assertThrows(AlpacaException.class,
                () -> new OrderRequest("AAPL", OrderSide.BUY, OrderType.LIMIT, null,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(190), null, null, null,
                        TimeInForce.DAY, false).validate());
        assertTrue(wrongType.getMessage().contains("market order"), wrongType.getMessage());
        assertTrue(wrongType.getMessage().contains("Quantity"), wrongType.getMessage());

        AlpacaException wrongTif = assertThrows(AlpacaException.class,
                () -> order(null, BigDecimal.valueOf(100), OrderType.MARKET, TimeInForce.GTC)
                        .validate());
        assertTrue(wrongTif.getMessage().contains("day"), wrongTif.getMessage());
    }

    @Test
    void theLimitShapesNeedALimitPriceAndTheStopShapesAStopPrice() {
        assertTrue(assertThrows(AlpacaException.class,
                () -> order(BigDecimal.ONE, null, OrderType.LIMIT, TimeInForce.DAY).validate())
                .getMessage().contains("Limit Price"));
        assertTrue(assertThrows(AlpacaException.class,
                () -> order(BigDecimal.ONE, null, OrderType.STOP, TimeInForce.DAY).validate())
                .getMessage().contains("Stop Price"));

        // stop limit needs both, and says so one at a time.
        AlpacaException stopLimit = assertThrows(AlpacaException.class,
                () -> order(BigDecimal.ONE, null, OrderType.STOP_LIMIT, TimeInForce.DAY).validate());
        assertTrue(stopLimit.getMessage().contains("Limit Price"), stopLimit.getMessage());
    }

    @Test
    void aTrailingStopNeedsExactlyOneTrail() {
        assertTrue(assertThrows(AlpacaException.class,
                () -> order(BigDecimal.ONE, null, OrderType.TRAILING_STOP, TimeInForce.DAY).validate())
                .getMessage().contains("Trail Price"));

        AlpacaException both = assertThrows(AlpacaException.class, () -> new OrderRequest("AAPL",
                OrderSide.SELL, OrderType.TRAILING_STOP, BigDecimal.ONE, null, null, null,
                BigDecimal.ONE, BigDecimal.TEN, TimeInForce.DAY, false).validate());
        assertTrue(both.getMessage().contains("not both"), both.getMessage());

        new OrderRequest("AAPL", OrderSide.SELL, OrderType.TRAILING_STOP, BigDecimal.ONE, null,
                null, null, null, BigDecimal.valueOf(5), TimeInForce.DAY, false).validate();
    }

    @Test
    void aTrailOnSomethingThatIsNotATrailingStopFailsRatherThanBeingIgnored() {
        // Ignoring it would be worse: the order would go in with no trail at all, and the field that
        // was filled in would look like it had done something.
        AlpacaException failure = assertThrows(AlpacaException.class, () -> new OrderRequest("AAPL",
                OrderSide.SELL, OrderType.MARKET, BigDecimal.ONE, null, null, null,
                BigDecimal.ONE, null, TimeInForce.DAY, false).validate());
        assertTrue(failure.getMessage().contains("only apply to a trailing stop"),
                failure.getMessage());
    }

    @Test
    void trailPercentIsAPercentage() {
        assertThrows(AlpacaException.class, () -> new OrderRequest("AAPL", OrderSide.SELL,
                OrderType.TRAILING_STOP, BigDecimal.ONE, null, null, null, null,
                BigDecimal.valueOf(150), TimeInForce.DAY, false).validate());
    }

    @Test
    void extendedHoursOnlyWorksOnALimitOrderGoodForTheDay() {
        // Alpaca's rule: there is no continuous auction outside regular hours for a market order to
        // fill against.
        AlpacaException failure = assertThrows(AlpacaException.class, () -> new OrderRequest("AAPL",
                OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE, null, null, null, null, null,
                TimeInForce.DAY, true).validate());
        assertTrue(failure.getMessage().contains("limit"), failure.getMessage());

        new OrderRequest("AAPL", OrderSide.BUY, OrderType.LIMIT, BigDecimal.ONE, null,
                BigDecimal.valueOf(190), null, null, null, TimeInForce.DAY, true).validate();
    }

    @Test
    void aBlankSymbolFailsBeforeAnythingElseIsLookedAt() {
        AlpacaException failure = assertThrows(AlpacaException.class, () -> new OrderRequest("  ",
                OrderSide.BUY, OrderType.MARKET, null, null, null, null, null, null,
                TimeInForce.DAY, false).validate());
        assertTrue(failure.getMessage().contains("Symbol"), failure.getMessage());
    }

    @Test
    void sidesAndTypesAreReadFromWhatSomebodyTyped() {
        assertEquals(OrderSide.SELL, OrderSide.parse(" Sell "));
        assertEquals(OrderType.STOP_LIMIT, OrderType.parse("stop_limit"));
        assertEquals(OrderType.STOP_LIMIT, OrderType.parse("Stop-Limit"));
        assertEquals(OrderType.TRAILING_STOP, OrderType.parse("trailing stop"));
        assertEquals(OrderType.MARKET, OrderType.parse(""));
        assertEquals(TimeInForce.DAY, TimeInForce.parse(null), "a blank Time In Force means day");

        // A typo must not quietly become one of two options that move money in opposite directions.
        AlpacaException typo = assertThrows(AlpacaException.class, () -> OrderSide.parse("byu"));
        assertTrue(typo.getMessage().contains("buy, sell"), typo.getMessage());
    }
}
