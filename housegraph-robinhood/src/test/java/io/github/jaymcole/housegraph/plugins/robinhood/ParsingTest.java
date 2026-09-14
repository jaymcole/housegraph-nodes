package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the fields a node's user types, and the fields Robinhood sends back. Two directions, one
 * rule each: what the user typed fails loudly when it is wrong, and what Robinhood sent fails
 * quietly - because a typo must not place the opposite trade, and an unfamiliar reply must not stop
 * a graph that is only displaying it.
 */
class ParsingTest {

    @Test
    void sidesAreCaseAndSpaceInsensitive() {
        assertEquals(OrderSide.BUY, OrderSide.parse("buy"));
        assertEquals(OrderSide.BUY, OrderSide.parse("  BUY "));
        assertEquals(OrderSide.SELL, OrderSide.parse("Sell"));
    }

    @Test
    void anUnrecognisedSideFailsRatherThanDefaulting() {
        // The one place a lenient default would be catastrophic: defaulting "sel" to buy sells
        // nothing and buys something.
        String message = assertThrows(RobinhoodException.class, () -> OrderSide.parse("sel"))
                .getMessage();
        assertTrue(message.contains("buy, sell"), message);
        assertThrows(RobinhoodException.class, () -> OrderSide.parse(""));
        assertThrows(RobinhoodException.class, () -> OrderSide.parse(null));
    }

    @Test
    void orderTypesAcceptTheThreeWaysPeopleWriteTwoWordOnes() {
        assertEquals(OrderType.STOP_LIMIT, OrderType.parse("stop limit"));
        assertEquals(OrderType.STOP_LIMIT, OrderType.parse("stop_limit"));
        assertEquals(OrderType.STOP_LIMIT, OrderType.parse("STOP-LIMIT"));
        assertEquals(OrderType.STOP_LOSS, OrderType.parse("stop loss"));
    }

    @Test
    void anEmptyOrderTypeMeansMarket() {
        assertEquals(OrderType.MARKET, OrderType.parse(null));
        assertEquals(OrderType.MARKET, OrderType.parse("  "));
        assertThrows(RobinhoodException.class, () -> OrderType.parse("stoplos"));
    }

    @Test
    void anEmptyTimeInForceMeansGoodTillCancelled() {
        assertEquals(TimeInForce.GTC, TimeInForce.parse(null));
        assertEquals(TimeInForce.IOC, TimeInForce.parse("IOC"));
        assertThrows(RobinhoodException.class, () -> TimeInForce.parse("forever"));
    }

    @Test
    void orderStatesReadBothSpellingsOfCancelled() {
        // Robinhood spells it "canceled"; a graph branching on cancellation must not miss it.
        assertEquals(OrderState.CANCELLED, OrderState.parse("canceled"));
        assertEquals(OrderState.CANCELLED, OrderState.parse("cancelled"));
    }

    @Test
    void anUnfamiliarOrderStateIsTreatedAsStillRunning() {
        // The safe reading: a graph waiting on a fill keeps waiting instead of announcing one.
        OrderState invented = OrderState.parse("pending_review_by_a_new_robinhood_feature");

        assertEquals(OrderState.UNKNOWN, invented);
        assertFalse(invented.isTerminal());
        assertFalse(invented.isFilled());
    }

    @Test
    void terminalStatesAreTheOnesThatWillNotChangeAgain() {
        assertTrue(OrderState.FILLED.isTerminal());
        assertTrue(OrderState.FILLED.isFilled());
        assertTrue(OrderState.REJECTED.isTerminal());
        assertTrue(OrderState.REJECTED.isRejected());
        assertTrue(OrderState.CANCELLED.isTerminal());
        assertFalse(OrderState.CANCELLED.isRejected());
        assertFalse(OrderState.PARTIALLY_FILLED.isTerminal());
        assertFalse(OrderState.QUEUED.isTerminal());
    }

    @Test
    void moneyArrivesAsStringsAndIsReadAsNumbers() {
        JSONObject body = new JSONObject("{\"price\": \"179.4300\", \"count\": 3}");

        assertEquals(179.43, Json.number(body, "price"));
        assertEquals(3.0, Json.number(body, "count"));
    }

    @Test
    void anAbsentOrNullFieldReadsAsNullRatherThanThrowing() {
        // Robinhood sends JSON null for a field that has no value yet - an unfilled order's
        // average_price - and getString() on that throws. Every read in this library goes through
        // Json for exactly this reason.
        JSONObject body = new JSONObject("{\"average_price\": null, \"state\": \"\"}");

        assertNull(Json.number(body, "average_price"));
        assertNull(Json.string(body, "average_price"));
        assertNull(Json.string(body, "state"));
        assertNull(Json.string(body, "not_there"));
        assertNull(Json.number(null, "anything"));
    }

    @Test
    void aFieldThatHasStoppedBeingANumberReadsAsNull() {
        JSONObject body = new JSONObject("{\"price\": \"unavailable\"}");

        assertNull(Json.number(body, "price"));
    }

    @Test
    void listEndpointsAreReadThroughTheirResultsEnvelope() {
        JSONObject body = new JSONObject("{\"results\": [{\"symbol\": \"AAPL\"}, 7, null,"
                + " {\"symbol\": \"MSFT\"}], \"next\": null}");

        assertEquals(2, Json.objects(body, "results").size());
        assertEquals("AAPL", Json.string(Json.firstResult(body), "symbol"));
        assertNull(Json.firstResult(new JSONObject("{\"results\": []}")));
    }
}
