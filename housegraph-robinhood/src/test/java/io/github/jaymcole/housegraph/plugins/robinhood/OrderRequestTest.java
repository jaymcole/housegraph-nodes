package io.github.jaymcole.housegraph.plugins.robinhood;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything that can be wrong with an order, refused before a call goes out.
 * <p>
 * Each of these is a mistake somebody will make on the canvas, and each message is checked for
 * naming the field to fix — because "Invalid request" coming back from Robinhood, which is what
 * happens without this, names nothing.
 */
class OrderRequestTest {

    private static OrderRequest order(OrderType type, BigDecimal quantity, BigDecimal amount,
                                      BigDecimal limit, BigDecimal stop) {
        return new OrderRequest("AAPL", OrderSide.BUY, type, quantity, amount, limit, stop,
                TimeInForce.GTC, false);
    }

    private static String messageOf(OrderRequest request) {
        return assertThrows(RobinhoodException.class, request::validate).getMessage();
    }

    @Test
    void acceptsAPlainMarketOrder() {
        assertDoesNotThrow(() -> order(OrderType.MARKET, new BigDecimal("1"), null, null, null).validate());
    }

    @Test
    void refusesAnOrderWithNoSize() {
        assertTrue(messageOf(order(OrderType.MARKET, null, null, null, null)).contains("Quantity"));
    }

    @Test
    void refusesAnOrderSizedTwoWaysAtOnce() {
        // The dangerous one: guessing which the user meant could trade ten times what they wanted.
        String message = messageOf(order(OrderType.MARKET, new BigDecimal("3"), new BigDecimal("300"),
                null, null));
        assertTrue(message.contains("not both"), message);
    }

    @Test
    void refusesNegativeAndZeroSizes() {
        assertTrue(messageOf(order(OrderType.MARKET, new BigDecimal("-1"), null, null, null))
                .contains("greater than zero"));
        assertTrue(messageOf(order(OrderType.MARKET, BigDecimal.ZERO, null, null, null))
                .contains("greater than zero"));
        assertTrue(messageOf(order(OrderType.MARKET, null, new BigDecimal("-5"), null, null))
                .contains("greater than zero"));
    }

    @Test
    void refusesALimitOrderWithNoLimitPrice() {
        assertTrue(messageOf(order(OrderType.LIMIT, new BigDecimal("1"), null, null, null))
                .contains("Limit Price"));
    }

    @Test
    void refusesAStopOrderWithNoStopPrice() {
        assertTrue(messageOf(order(OrderType.STOP_LOSS, new BigDecimal("1"), null, null, null))
                .contains("Stop Price"));
        assertTrue(messageOf(order(OrderType.STOP_LIMIT, new BigDecimal("1"), null,
                new BigDecimal("10"), null)).contains("Stop Price"));
    }

    @Test
    void refusesADollarAmountOnALimitSell() {
        // "$500 worth" priced off a quote the order is specifically not taking is a fiction, and
        // the message says which field to use instead.
        OrderRequest limitSell = new OrderRequest("AAPL", OrderSide.SELL, OrderType.LIMIT,
                null, new BigDecimal("500"), new BigDecimal("190"), null, TimeInForce.GTC, false);

        String message = messageOf(limitSell);
        assertTrue(message.contains("Quantity"), message);
    }

    @Test
    void allowsADollarAmountOnAMarketBuy() {
        assertDoesNotThrow(() -> order(OrderType.MARKET, null, new BigDecimal("100"), null, null)
                .validate());
    }

    @Test
    void refusesAnEmptySymbol() {
        OrderRequest noSymbol = new OrderRequest("  ", OrderSide.BUY, OrderType.MARKET,
                new BigDecimal("1"), null, null, null, TimeInForce.GTC, false);

        assertTrue(messageOf(noSymbol).contains("Symbol"));
    }
}
