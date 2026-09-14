package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the account and placing orders, against a stub standing in for Robinhood.
 * <p>
 * What is checked is what the real thing would see: how many calls go out, in what order, carrying
 * what. Several of these are about the calls a graph must <em>not</em> make — a prepare that
 * accidentally placed an order, a portfolio priced one symbol at a time until the account is
 * rate-limited, a cancel sent for an order that had already filled.
 */
class RobinhoodSessionTradingTest {

    private StubRobinhood robinhood;
    private RobinhoodSession session;

    @BeforeEach
    void setUp() throws IOException {
        robinhood = StubRobinhood.openLoggedIn();
        session = robinhood.connectedSession();
    }

    @AfterEach
    void tearDown() {
        robinhood.close();
    }

    private String instrumentUrl(String symbol) {
        return robinhood.address() + "/instruments/" + symbol + "/";
    }

    /** Teaches the stub one tradable symbol, both ways round, at one price. */
    private void stubSymbol(String symbol, String price) {
        robinhood.on("GET", "/instruments/", 200,
                StubRobinhood.instrument(symbol, instrumentUrl(symbol)));
        robinhood.on("GET", "/instruments/" + symbol + "/", 200,
                new JSONObject().put("symbol", symbol).put("url", instrumentUrl(symbol)).toString());
        robinhood.on("GET", "/marketdata/quotes/" + symbol + "/", 200,
                StubRobinhood.quote(symbol, price, "99.00").toString());
    }

    @Test
    void readsAQuote() {
        stubSymbol("AAPL", "100.00");

        Quote quote = session.quote("aapl");

        assertEquals("AAPL", quote.symbol());
        assertEquals(100.0, quote.price());
        assertEquals(1.0, quote.changeFromPreviousClose());
    }

    @Test
    void readsTheAccountFromBothEndpoints() {
        robinhood.on("GET", "/portfolios/123456789/", 200,
                StubRobinhood.portfolio("5000.00", "4900.00"));

        AccountSummary summary = session.accountSummary();

        assertEquals("123456789", summary.accountNumber());
        assertEquals(1250.75, summary.buyingPower());
        assertEquals(980.10, summary.cash());
        assertEquals(5000.0, summary.equity());
        assertEquals(100.0, summary.dayChange());
    }

    @Test
    void pricesAWholePortfolioInOneCall() {
        // The bug this pins down: quoting per holding, which is how an account gets rate-limited by
        // a graph that is only drawing a table.
        robinhood.on("GET", "/positions/", 200, StubRobinhood.results(
                position("AAPL", "3.00000000", "90.0000"),
                position("MSFT", "1.00000000", "300.0000")));
        robinhood.on("GET", "/instruments/AAPL/", 200,
                new JSONObject().put("symbol", "AAPL").toString());
        robinhood.on("GET", "/instruments/MSFT/", 200,
                new JSONObject().put("symbol", "MSFT").toString());
        robinhood.on("GET", "/marketdata/quotes/", 200, StubRobinhood.results(
                StubRobinhood.quote("AAPL", "100.00", "99.00"),
                StubRobinhood.quote("MSFT", "310.00", "305.00")));

        List<Position> positions = session.positions(true);

        assertEquals(1, robinhood.callsTo("GET", "/marketdata/quotes/").size());
        assertEquals(List.of("AAPL", "MSFT"),
                positions.stream().map(Position::symbol).toList());
        assertEquals(300.0, positions.get(0).marketValue());
        assertEquals(270.0, positions.get(0).costBasis());
        assertEquals(30.0, positions.get(0).unrealisedGain());
    }

    @Test
    void doesNotPriceAPortfolioNobodyAskedToPrice() {
        robinhood.on("GET", "/positions/", 200,
                StubRobinhood.results(position("AAPL", "3.00000000", "90.0000")));
        robinhood.on("GET", "/instruments/AAPL/", 200,
                new JSONObject().put("symbol", "AAPL").toString());

        List<Position> positions = session.positions(false);

        assertTrue(robinhood.callsTo("GET", "/marketdata/quotes/").isEmpty());
        assertNull(positions.get(0).price());
        // And no market value, rather than a zero one: "not priced" and "worth nothing" must not
        // read the same downstream.
        assertNull(positions.get(0).marketValue());
    }

    @Test
    void dropsAHoldingItCannotNameRatherThanListingBlanks() {
        robinhood.on("GET", "/positions/", 200, StubRobinhood.results(
                position("AAPL", "1.00000000", "90.0000"),
                position("GONE", "1.00000000", "10.0000")));
        robinhood.on("GET", "/instruments/AAPL/", 200,
                new JSONObject().put("symbol", "AAPL").toString());
        robinhood.on("GET", "/instruments/GONE/", 404, "{\"detail\": \"Not found.\"}");
        robinhood.on("GET", "/marketdata/quotes/", 200,
                StubRobinhood.results(StubRobinhood.quote("AAPL", "100.00", "99.00")));

        List<Position> positions = session.positions(true);

        assertEquals(1, positions.size());
        assertEquals("AAPL", positions.get(0).symbol());
    }

    @Test
    void looksAnInstrumentUpOnceAndRemembersIt() {
        stubSymbol("AAPL", "100.00");
        OrderRequest request = marketBuy("AAPL", "1");

        session.prepareOrder(request);
        session.prepareOrder(request);

        assertEquals(1, robinhood.callsTo("GET", "/instruments/").size());
    }

    @Test
    void preparingAnOrderPlacesNothing() {
        // The whole point of preparing separately: a dry run and a refused ceiling both stop here,
        // and neither may have spent anything.
        stubSymbol("AAPL", "100.00");

        PreparedOrder prepared = session.prepareOrder(marketBuy("AAPL", "2"));

        assertTrue(robinhood.callsTo("POST", "/orders/").isEmpty());
        assertEquals(0, new BigDecimal("2").compareTo(prepared.quantity()));
        assertEquals(200.0, prepared.estimatedValue());
        assertFalse(prepared.referenceId().isBlank());
    }

    @Test
    void estimatesADollarAmountAsTheSharesItComesTo() {
        stubSymbol("AAPL", "100.00");
        OrderRequest fiftyDollars = new OrderRequest("AAPL", OrderSide.BUY, OrderType.MARKET,
                null, new BigDecimal("50"), null, null, TimeInForce.GTC, false);

        PreparedOrder prepared = session.prepareOrder(fiftyDollars);

        assertEquals(0, new BigDecimal("0.5").compareTo(prepared.quantity()));
        assertEquals(50.0, prepared.estimatedValue());
    }

    @Test
    void estimatesALimitOrderAtItsLimitRatherThanTheMarket() {
        // A limit buy will not trade through its limit, so that is the honest worst case - and the
        // number a spending ceiling has to be checked against.
        stubSymbol("AAPL", "100.00");
        OrderRequest limitBuy = new OrderRequest("AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("2"), null, new BigDecimal("90"), null, TimeInForce.GTC, false);

        assertEquals(180.0, session.prepareOrder(limitBuy).estimatedValue());
    }

    @Test
    void placesExactlyOneOrder() {
        stubSymbol("AAPL", "100.00");
        robinhood.on("POST", "/orders/", 201,
                StubRobinhood.order("order-1", "AAPL", "buy", "queued").toString());

        Order order = session.submit(session.prepareOrder(marketBuy("AAPL", "2")));

        assertEquals(1, robinhood.callsTo("POST", "/orders/").size());
        assertEquals("order-1", order.id());
        assertEquals(OrderState.QUEUED, order.state());
        JSONObject sent = robinhood.lastCallTo("POST", "/orders/").json();
        assertEquals("AAPL", sent.getString("symbol"));
        assertEquals("buy", sent.getString("side"));
        assertEquals("2", sent.getString("quantity"));
        assertEquals("105.00", sent.getString("price"));
        assertEquals(instrumentUrl("AAPL"), sent.getString("instrument"));
    }

    @Test
    void reportsARejectionRobinhoodPutInTheOrderRatherThanInTheResponse() {
        // Robinhood answers 201 and rejects the order a moment later, which is why "placed" and
        // "worked" are different questions - and why this is not an exception.
        stubSymbol("AAPL", "100.00");
        robinhood.on("POST", "/orders/", 201,
                StubRobinhood.order("order-2", "AAPL", "buy", "rejected").toString());

        Order order = session.submit(session.prepareOrder(marketBuy("AAPL", "2")));

        assertEquals(OrderState.REJECTED, order.state());
        assertTrue(order.state().isRejected());
        assertEquals("Not enough buying power", order.rejectReason());
    }

    @Test
    void refusesAnOrderRobinhoodRefused() {
        stubSymbol("AAPL", "100.00");
        robinhood.on("POST", "/orders/", 400,
                "{\"detail\": \"You can only purchase 1.00 shares of this security\"}");

        String message = assertThrows(RobinhoodException.class,
                () -> session.submit(session.prepareOrder(marketBuy("AAPL", "2")))).getMessage();

        assertTrue(message.contains("You can only purchase 1.00 shares"), message);
    }

    @Test
    void refusesASymbolRobinhoodDoesNotKnow() {
        robinhood.on("GET", "/instruments/", 200, "{\"results\": []}");

        String message = assertThrows(RobinhoodException.class,
                () -> session.prepareOrder(marketBuy("NOSUCH", "1"))).getMessage();

        assertTrue(message.contains("NOSUCH"), message);
        assertTrue(message.contains("Check the ticker"), message);
    }

    @Test
    void readsAnOrderAndResolvesItsSymbol() {
        robinhood.on("GET", "/orders/order-1/", 200,
                StubRobinhood.order("order-1", "AAPL", "buy", "filled")
                        .put("instrument", instrumentUrl("AAPL")).toString());
        robinhood.on("GET", "/instruments/AAPL/", 200,
                new JSONObject().put("symbol", "AAPL").toString());

        Order order = session.order("order-1");

        assertEquals("AAPL", order.symbol());
        assertEquals(OrderState.FILLED, order.state());
        assertEquals(2.0, order.filledQuantity());
        assertEquals(101.50, order.averagePrice());
        assertEquals(203.0, order.filledValue());
    }

    @Test
    void sendsNoCancelForAnOrderThatAlreadyFinished() {
        // A cancel racing a fill is the normal case. Sending one anyway would be a call Robinhood
        // refuses, reported to the user as a failure that wasn't one.
        robinhood.on("GET", "/orders/order-1/", 200,
                StubRobinhood.order("order-1", "AAPL", "buy", "filled").toString());

        Order order = session.cancel("order-1");

        assertTrue(robinhood.callsTo("POST", "/orders/order-1/cancel/").isEmpty());
        assertEquals(OrderState.FILLED, order.state());
    }

    @Test
    void cancelsAWorkingOrderAndReadsBackWhatResulted() {
        robinhood.on("GET", "/orders/order-1/", 200,
                StubRobinhood.order("order-1", "AAPL", "buy", "confirmed").toString());
        robinhood.on("GET", "/orders/order-1/", 200,
                StubRobinhood.order("order-1", "AAPL", "buy", "canceled").toString());
        robinhood.on("POST", "/orders/order-1/cancel/", 200, "{}");

        Order order = session.cancel("order-1");

        assertEquals(1, robinhood.callsTo("POST", "/orders/order-1/cancel/").size());
        assertEquals(OrderState.CANCELLED, order.state());
    }

    @Test
    void listsRecentOrdersNewestFirstAndCapsWhatItAsksFor() {
        robinhood.on("GET", "/orders/", 200, StubRobinhood.results(
                StubRobinhood.order("order-3", "AAPL", "buy", "confirmed"),
                StubRobinhood.order("order-2", "AAPL", "sell", "filled"),
                StubRobinhood.order("order-1", "AAPL", "buy", "filled")));
        robinhood.on("GET", "/instruments/AAPL/", 200,
                new JSONObject().put("symbol", "AAPL").toString());

        assertEquals(2, session.recentOrders(2).size());
        assertEquals("order-3", session.recentOrders(2).get(0).id());
        assertEquals(3, session.recentOrders(99).size());
    }

    @Test
    void anOrderWithoutAnIdIsRefusedBeforeAnyCall() {
        assertThrows(RobinhoodException.class, () -> session.order(" "));
        assertTrue(robinhood.calls().stream().noneMatch(call -> call.path().startsWith("/orders/")));
    }

    @Test
    void ordersAndPositionsConvertToMapsWithoutNullValues() {
        // The collections library reads these; a key mapped to null and an absent key look the same
        // to Map Get but not to Map Contains, so absent is the honest one.
        Map<String, Object> unfilled = Order.from(
                StubRobinhood.order("order-1", "AAPL", "buy", "queued"), "AAPL").asMap();

        assertFalse(unfilled.containsKey("average_price"));
        assertFalse(unfilled.containsKey("reject_reason"));
        assertEquals("queued", unfilled.get("state"));
        assertNotNull(unfilled.get("quantity"));

        Map<String, Object> unpriced = new Position("AAPL", 3, 90.0, null).asMap();
        assertFalse(unpriced.containsKey("price"));
        assertFalse(unpriced.containsKey("market_value"));
        assertEquals(270.0, unpriced.get("cost_basis"));
    }

    private OrderRequest marketBuy(String symbol, String quantity) {
        return new OrderRequest(symbol, OrderSide.BUY, OrderType.MARKET,
                new BigDecimal(quantity), null, null, null, TimeInForce.GTC, false);
    }

    private JSONObject position(String symbol, String quantity, String averageBuyPrice) {
        return new JSONObject()
                .put("instrument", instrumentUrl(symbol))
                .put("quantity", quantity)
                .put("average_buy_price", averageBuyPrice);
    }
}
