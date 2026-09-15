package io.github.jaymcole.housegraph.plugins.alpaca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the session sends, what it makes of what comes back, and what it refuses to do at all. */
class AlpacaSessionTest {

    private StubAlpaca alpaca;

    @BeforeEach
    void setUp() throws IOException {
        alpaca = StubAlpaca.openConnected();
    }

    @AfterEach
    void tearDown() {
        alpaca.close();
    }

    @Test
    void connectingChecksTheKeysAndReadsTheAccount() {
        // The whole value of a Connect step against a key-authenticated API: a mistyped key becomes
        // a red status line now rather than a failed trade at 09:30 tomorrow.
        AlpacaSession session = alpaca.session();
        assertFalse(session.isConnected());

        session.connect(StubAlpaca.credentials());

        assertTrue(session.isConnected());
        assertEquals("PA123456789", session.accountNumber());
        assertEquals(List.of("/v2/account"),
                alpaca.calls().stream().map(StubAlpaca.Call::path).toList());
    }

    @Test
    void everyCallCarriesBothKeyHeaders() {
        alpaca.connectedSession();

        StubAlpaca.Call call = alpaca.lastCallTo("GET", "/v2/account");
        assertEquals("PKTESTKEYID", call.apiKey());
        assertEquals("test-secret-key", call.secretKey());
    }

    @Test
    void aFailedConnectLeavesTheSessionAsItWas() {
        // A failed reconnect that had cleared a working session would take every other node in the
        // graph down with it.
        AlpacaSession session = alpaca.connectedSession();
        alpaca.only("GET", "/v2/account", 401, StubAlpaca.error(40110000, "access key verification failed"));

        assertThrows(AlpacaException.class, () -> session.connect(StubAlpaca.credentials()));

        assertTrue(session.isConnected(), "the session that was working should still be working");
        assertEquals("PA123456789", session.accountNumber());
    }

    @Test
    void connectingWithIncompleteKeysNeverReachesTheNetwork() {
        AlpacaSession session = alpaca.session();

        AlpacaException failure = assertThrows(AlpacaException.class,
                () -> session.connect(new AlpacaCredentials("PKID", "  ", true)));

        assertTrue(failure.getMessage().contains("Secret Key"), failure.getMessage());
        assertTrue(alpaca.calls().isEmpty(), "nothing should have been sent");
    }

    @Test
    void anUnconnectedSessionRefusesToDoAnythingAndSaysHowToFixIt() {
        AlpacaSession session = alpaca.session();

        AlpacaException failure = assertThrows(AlpacaException.class, session::accountSummary);

        assertTrue(failure.getMessage().contains("not connected"), failure.getMessage());
        assertTrue(alpaca.calls().isEmpty());
    }

    @Test
    void disconnectingForgetsTheKeysWithoutTouchingTheNetwork() {
        AlpacaSession session = alpaca.connectedSession();
        int before = alpaca.calls().size();

        session.disconnect();

        assertFalse(session.isConnected());
        assertNull(session.accountNumber());
        assertEquals(before, alpaca.calls().size(), "there is no token to hand back");
    }

    @Test
    void theStatusLineShoutsAboutALiveAccount() {
        // The single most consequential fact about a graph on the canvas is whether it is spending
        // real money, and it should be readable without opening a node's inputs.
        AlpacaSession paper = alpaca.connectedSession();
        assertTrue(paper.statusText().contains("paper"), paper.statusText());
        assertTrue(paper.isPaper());

        AlpacaSession live = alpaca.session();
        live.connect(new AlpacaCredentials("AKID", "secret", false));
        assertTrue(live.statusText().contains("LIVE"), live.statusText());
        assertFalse(live.isPaper());
    }

    @Test
    void aQuoteIsOneSnapshotCallAndPrefersTheLastTrade() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/stocks/snapshots", 200, StubAlpaca.snapshots("AAPL", 100.0, 99.0));

        Quote quote = session.quote("aapl", "iex");

        assertEquals("AAPL", quote.symbol(), "a lower-case ticker is normalised on the way out");
        assertEquals(100.0, quote.price(), 0.0001);
        StubAlpaca.Call call = alpaca.lastCallTo("GET", "/v2/stocks/snapshots");
        assertTrue(call.query().contains("symbols=AAPL"), call.query());
        assertTrue(call.query().contains("feed=iex"), call.query());
    }

    @Test
    void aSymbolTheFeedDoesNotKnowFailsWithSomethingWorthReading() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/stocks/snapshots", 200, StubAlpaca.emptySnapshots("NOPE"));

        AlpacaException failure = assertThrows(AlpacaException.class,
                () -> session.quote("NOPE", "iex"));

        assertTrue(failure.getMessage().contains("NOPE"), failure.getMessage());
        assertTrue(failure.getMessage().contains("iex"), failure.getMessage());
    }

    @Test
    void oneBadTickerInAListDoesNotCostThePricesOfTheOthers() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/stocks/snapshots", 200, StubAlpaca.snapshots("AAPL", 100.0, 99.0));

        var quotes = session.quotes(List.of("AAPL", "NOPE"), "iex");

        assertEquals(1, quotes.size());
        assertNotNull(quotes.get("AAPL"));
        assertEquals(1, alpaca.callsTo("GET", "/v2/stocks/snapshots").size(),
                "a watchlist should be one call, not one per symbol");
    }

    @Test
    void barsComeBackOldestFirstHoweverTheyWereAskedFor() {
        // Asked newest-first so that "the last 50 daily bars" needs no dates; turned round here
        // because that is the order an average wants them in.
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/stocks/bars", 200, StubAlpaca.bars("AAPL",
                StubAlpaca.bar("2026-09-14T04:00:00Z", 100, 103),
                StubAlpaca.bar("2026-09-13T04:00:00Z", 99, 100),
                StubAlpaca.bar("2026-09-12T04:00:00Z", 98, 99)));

        List<Bar> bars = session.bars("AAPL", "1Day", 3, "iex");

        assertEquals(3, bars.size());
        assertEquals("2026-09-12T04:00:00Z", bars.get(0).timestamp());
        assertEquals(103.0, bars.get(2).close(), 0.0001);
        StubAlpaca.Call call = alpaca.lastCallTo("GET", "/v2/stocks/bars");
        assertTrue(call.query().contains("sort=desc"), call.query());
        assertTrue(call.query().contains("timeframe=1Day"), call.query());
        assertTrue(call.query().contains("adjustment=all"),
                "unadjusted prices put a cliff in any average taken across a split");
    }

    @Test
    void positionsComePricedWithNoExtraCalls() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/positions", 200, StubAlpaca.list(
                StubAlpaca.position("AAPL", "2", "100.00"),
                StubAlpaca.position("MSFT", "1", "400.00")));

        List<Position> positions = session.positions();

        assertEquals(2, positions.size());
        assertEquals(200.00, positions.get(0).marketValue(), 0.0001);
        assertEquals(1, alpaca.callsTo("GET", "/v2/positions").size(),
                "Alpaca prices the holdings itself; nothing else should have been fetched");
    }

    @Test
    void placingAnOrderSendsExactlyOnePost() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/stocks/snapshots", 200, StubAlpaca.snapshots("AAPL", 100.0, 99.0));
        alpaca.on("POST", "/v2/orders", 200,
                StubAlpaca.order("order-1", "AAPL", "buy", "new").toString());

        PreparedOrder prepared = session.prepareOrder(new OrderRequest("AAPL", OrderSide.BUY,
                OrderType.MARKET, new java.math.BigDecimal("2"), null, null, null, null, null,
                TimeInForce.DAY, false));
        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty(),
                "preparing an order must not place it");
        assertEquals(200.0, prepared.estimatedValue(), 0.0001);

        Order order = session.submit(prepared);

        assertEquals("order-1", order.id());
        assertEquals(1, alpaca.callsTo("POST", "/v2/orders").size());
    }

    @Test
    void aLimitOrderIsPreparedWithoutAskingForAQuoteAtAll() {
        // It is valued at its own limit, so a quiet feed cannot stop a limit order going in.
        AlpacaSession session = alpaca.connectedSession();

        PreparedOrder prepared = session.prepareOrder(new OrderRequest("AAPL", OrderSide.BUY,
                OrderType.LIMIT, new java.math.BigDecimal("2"), null,
                new java.math.BigDecimal("95"), null, null, null, TimeInForce.DAY, false));

        assertEquals(190.0, prepared.estimatedValue(), 0.0001);
        assertTrue(alpaca.callsTo("GET", "/v2/stocks/snapshots").isEmpty());
    }

    @Test
    void aMarketOrderPreparesEvenWhenNoPriceCanBeRead() {
        // Whether an unpriceable order may go out is the node's decision, not the session's: Place
        // Order fails only when a spending cap it cannot check is set.
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/stocks/snapshots", 200, StubAlpaca.emptySnapshots("AAPL"));

        PreparedOrder prepared = session.prepareOrder(new OrderRequest("AAPL", OrderSide.BUY,
                OrderType.MARKET, new java.math.BigDecimal("2"), null, null, null, null, null,
                TimeInForce.DAY, false));

        assertNull(prepared.estimatedValue());
        assertNotNull(prepared.payload().getString("client_order_id"));
    }

    @Test
    void cancellingReadsTheOrderBackSoTheStateReportedIsTheOneThatResulted() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/orders/order-1", 200,
                        StubAlpaca.order("order-1", "AAPL", "buy", "new").toString())
                .on("GET", "/v2/orders/order-1", 200,
                        StubAlpaca.order("order-1", "AAPL", "buy", "canceled").toString());
        alpaca.on("DELETE", "/v2/orders/order-1", 204, null);

        Order order = session.cancel("order-1");

        assertEquals(OrderState.CANCELLED, order.state());
        assertEquals(1, alpaca.callsTo("DELETE", "/v2/orders/order-1").size());
    }

    @Test
    void anOrderThatHasAlreadyFinishedIsNotCancelledAndIsNotAnError() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/orders/order-1", 200,
                StubAlpaca.order("order-1", "AAPL", "buy", "filled").toString());

        Order order = session.cancel("order-1");

        assertEquals(OrderState.FILLED, order.state());
        assertTrue(alpaca.callsTo("DELETE", "/v2/orders/order-1").isEmpty(),
                "there was nothing left to cancel, so nothing should have been asked for");
    }

    @Test
    void aCancelThatRacedAFillReportsTheFillRatherThanFailing() {
        // The normal case, not the exceptional one: Alpaca answers 422 for an order that finished
        // between the read and the cancel. A node that threw for it would make every "cancel my
        // stale orders" graph fragile by design.
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/orders/order-1", 200,
                        StubAlpaca.order("order-1", "AAPL", "buy", "new").toString())
                .on("GET", "/v2/orders/order-1", 200,
                        StubAlpaca.order("order-1", "AAPL", "buy", "filled").toString());
        alpaca.on("DELETE", "/v2/orders/order-1", 422,
                StubAlpaca.error(42210000, "order is not cancelable"));

        Order order = session.cancel("order-1");

        assertEquals(OrderState.FILLED, order.state());
    }

    @Test
    void aCancelThatFailedForSomeOtherReasonStillFails() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/orders/order-1", 200,
                StubAlpaca.order("order-1", "AAPL", "buy", "new").toString());
        alpaca.on("DELETE", "/v2/orders/order-1", 500, StubAlpaca.error(50010000, "server error"));

        assertThrows(AlpacaException.class, () -> session.cancel("order-1"));
    }

    @Test
    void recentOrdersAsksForWhatTheCallerWantedAndIsCappedAtAlpacasLimit() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/orders", 200, StubAlpaca.list(
                StubAlpaca.order("order-2", "MSFT", "sell", "new"),
                StubAlpaca.order("order-1", "AAPL", "buy", "filled")));

        List<Order> orders = session.recentOrders(9000, true);

        assertEquals(2, orders.size());
        assertEquals("order-2", orders.get(0).id());
        String query = alpaca.lastCallTo("GET", "/v2/orders").query();
        assertTrue(query.contains("status=open"), query);
        assertTrue(query.contains("limit=500"), query);
    }

    @Test
    void closingAPositionAsksForAPercentageOnlyWhenOneWasGiven() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("DELETE", "/v2/positions/AAPL", 200,
                StubAlpaca.order("order-9", "AAPL", "sell", "new").toString());

        session.closePosition("AAPL", null);
        assertNull(alpaca.lastCallTo("DELETE", "/v2/positions/AAPL").query());

        session.closePosition("AAPL", new java.math.BigDecimal("50"));
        assertEquals("percentage=50", alpaca.lastCallTo("DELETE", "/v2/positions/AAPL").query());
    }

    @Test
    void theClockReadsFromTheTradingHost() {
        AlpacaSession session = alpaca.connectedSession();
        alpaca.on("GET", "/v2/clock", 200, StubAlpaca.clock(true));

        assertTrue(session.clock().open());
    }

    @Test
    void anEmptyOrderIdIsRefusedBeforeAnythingIsSent() {
        AlpacaSession session = alpaca.connectedSession();

        AlpacaException failure = assertThrows(AlpacaException.class, () -> session.order("  "));

        assertTrue(failure.getMessage().contains("Order ID"), failure.getMessage());
        assertTrue(alpaca.callsTo("GET", "/v2/orders/").isEmpty());
    }
}
