package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading Alpaca's answers, including the ones that are missing half their fields.
 * <p>
 * <b>Documented is not the same as always present.</b> Money arrives as strings on the trading API
 * and as numbers on the market-data one, a field with no value yet arrives as JSON null rather than
 * being absent, and Alpaca does remove fields — it dropped {@code pattern_day_trader} and two others
 * from the account response in July 2026. Every test here is a body that is legal and incomplete,
 * and the assertion is that it reads as null rather than throwing.
 */
class ParsingTest {

    @Test
    void moneyReadsWhetherItArrivesAsAStringOrANumber() {
        JSONObject body = new JSONObject().put("string", "179.43").put("number", 179.43);
        assertEquals(179.43, Json.number(body, "string"), 0.0001);
        assertEquals(179.43, Json.number(body, "number"), 0.0001);
    }

    @Test
    void anAbsentFieldAJsonNullAndAnEmptyStringAllReadAsNothing() {
        JSONObject body = new JSONObject().put("null", JSONObject.NULL).put("blank", "  ");
        assertNull(Json.string(body, "missing"));
        assertNull(Json.string(body, "null"));
        assertNull(Json.string(body, "blank"));
        assertNull(Json.number(body, "null"));
        assertNull(Json.number(new JSONObject().put("text", "n/a"), "text"),
                "a field that has stopped being a number is the same problem as a missing one");
    }

    @Test
    void anOrderReadsWithItsOptionalFieldsUnfilled() {
        Order order = Order.from(StubAlpaca.order("order-1", "AAPL", "buy", "new"));

        assertEquals("order-1", order.id());
        assertEquals("AAPL", order.symbol());
        assertEquals(OrderSide.BUY, order.side());
        assertEquals(OrderState.NEW, order.state());
        assertEquals("new", order.rawState());
        assertEquals(2.0, order.quantity(), 0.0001);
        assertEquals(0.0, order.filledQuantity(), 0.0001);
        assertNull(order.averagePrice(), "an unfilled order has no average price yet");
        assertNull(order.filledValue());
        assertNull(order.filledAt());
        assertFalse(order.state().isTerminal());
    }

    @Test
    void aFilledOrderCarriesWhatItActuallyCost() {
        Order order = Order.from(StubAlpaca.order("order-1", "AAPL", "buy", "filled"));

        assertTrue(order.state().isFilled());
        assertTrue(order.state().isTerminal());
        assertEquals(101.50, order.averagePrice(), 0.0001);
        assertEquals(203.00, order.filledValue(), 0.0001);
        assertTrue(order.describe().contains("filled"), order.describe());
    }

    @Test
    void anUnknownStateIsReportedAsAlpacaSpelledItAndTreatedAsStillRunning() {
        // A client that threw on a new state would turn "Alpaca added a status" into "the graph
        // stopped". A node waiting on a fill has to keep waiting instead.
        Order order = Order.from(StubAlpaca.order("order-1", "AAPL", "buy", "something_new"));

        assertEquals(OrderState.UNKNOWN, order.state());
        assertEquals("something_new", order.displayState());
        assertFalse(order.state().isTerminal());
        assertFalse(order.state().isFilled());
    }

    @Test
    void doneForDayIsNotTerminalBecauseAGtcOrderComesBackTomorrow() {
        assertFalse(OrderState.parse("done_for_day").isTerminal());
        assertTrue(OrderState.parse("expired").isTerminal());
        assertTrue(OrderState.parse("replaced").isTerminal());
        assertEquals(OrderState.CANCELLED, OrderState.parse("canceled"), "Alpaca spells it this way");
        assertEquals(OrderState.CANCELLED, OrderState.parse("cancelled"), "and people spell it this way");
        assertFalse(OrderState.PENDING_CANCEL.isCancellable(), "one cancel request is enough");
    }

    @Test
    void aPositionReadsPricedAndMapsToAlpacasOwnFieldNames() {
        Position position = Position.from(StubAlpaca.position("AAPL", "2", "100.00"));

        assertEquals("AAPL", position.symbol());
        assertEquals(2.0, position.quantity(), 0.0001);
        assertEquals(200.00, position.marketValue(), 0.0001);
        assertEquals(20.00, position.unrealisedProfit(), 0.0001);
        assertFalse(position.isShort());

        Map<String, Object> map = position.asMap();
        assertEquals("AAPL", map.get("symbol"));
        assertEquals(200.00, (Double) map.get("market_value"), 0.0001);
        assertEquals(0.1111, (Double) map.get("unrealized_plpc"), 0.0001);
    }

    @Test
    void aFieldAlpacaDidNotReportIsLeftOutOfTheMapRatherThanMappedToNull() {
        // A Map Get on an absent key and one on a null value read the same downstream; leaving it
        // out is what keeps Map Contains honest.
        Position position = Position.from(new JSONObject().put("symbol", "AAPL").put("qty", "1"));

        Map<String, Object> map = position.asMap();
        assertTrue(map.containsKey("symbol"));
        assertFalse(map.containsKey("market_value"));
        assertNull(position.marketValue());
    }

    @Test
    void aShortPositionIsNegativeAndSaysSo() {
        JSONObject body = StubAlpaca.position("AAPL", "-3", "100.00").put("side", "short");
        assertTrue(Position.from(body).isShort());
    }

    @Test
    void aQuoteReadsFromTheSnapshotAndPrefersTheLastTrade() {
        Quote quote = Quote.from("AAPL", snapshotOf(StubAlpaca.snapshots("AAPL", 100.0, 99.0)));

        assertEquals("AAPL", quote.symbol());
        assertEquals(100.0, quote.price(), 0.0001);
        assertEquals(99.0, quote.previousClose(), 0.0001);
        assertEquals(1.0, quote.changeFromPreviousClose(), 0.0001);
        assertEquals(0.0101, quote.changePercent(), 0.0001);
        assertEquals(0.10, quote.spread(), 0.0001);
        assertFalse(quote.isEmpty());
    }

    @Test
    void aQuoteWithNoTradeFallsBackToTheMidpoint() {
        // A symbol that has not traded on the chosen feed today still has a bid and an ask, and
        // that midpoint is a far better answer than null for anything about to place an order.
        JSONObject snapshot = new JSONObject().put("latestQuote",
                new JSONObject().put("bp", 99.0).put("ap", 101.0));

        Quote quote = Quote.from("AAPL", snapshot);
        assertEquals(100.0, quote.price(), 0.0001);
        assertNull(quote.lastTradePrice());
        assertFalse(quote.isEmpty());
    }

    @Test
    void anUnknownTickerComesBackAsAnEmptySnapshotRatherThanAn404() {
        // Which is why isEmpty() exists: "no such symbol" and "this symbol was quiet" arrive
        // looking identical, and only the caller can turn that into a message.
        Quote quote = Quote.from("NOPE", new JSONObject());

        assertTrue(quote.isEmpty());
        assertNull(quote.price());
    }

    @Test
    void anAccountReadsWithTheDayChangeWorkedOut() {
        AccountSummary summary = AccountSummary.from(new JSONObject(StubAlpaca.account("PA1")));

        assertEquals("PA1", summary.accountNumber());
        assertEquals(5250.75, summary.equity(), 0.0001);
        assertEquals(150.50, summary.dayChange(), 0.0001);
        assertEquals(0.0295, summary.dayChangePercent(), 0.0001);
        assertTrue(summary.canTrade());
    }

    @Test
    void aBlockedAccountCannotTradeWhicheverFlagIsSet() {
        JSONObject body = new JSONObject(StubAlpaca.account("PA1")).put("trading_blocked", true);
        assertFalse(AccountSummary.from(body).canTrade());

        JSONObject other = new JSONObject(StubAlpaca.account("PA1")).put("account_blocked", true);
        assertFalse(AccountSummary.from(other).canTrade());
    }

    @Test
    void anAccountMissingTheFieldsAlpacaRemovedStillReads() {
        // pattern_day_trader, daytrade_count and daytrading_buying_power went away in July 2026.
        // Nothing here reads them, and a body without them is not a parse failure.
        AccountSummary summary = AccountSummary.from(new JSONObject()
                .put("account_number", "PA1").put("equity", "100.00"));

        assertEquals("PA1", summary.accountNumber());
        assertEquals(100.00, summary.equity(), 0.0001);
        assertNull(summary.dayChange(), "no previous close means no day change, not a zero");
        assertTrue(summary.canTrade(), "absent blocked flags mean not blocked");
    }

    @Test
    void aBarReadsAlpacasSingleLetterKeysIntoWords() {
        Bar bar = Bar.from("AAPL", StubAlpaca.bar("2026-09-14T04:00:00Z", 99.0, 101.0));

        assertEquals(99.0, bar.open(), 0.0001);
        assertEquals(101.0, bar.close(), 0.0001);
        assertEquals(2.0, bar.change(), 0.0001);
        assertEquals("2026-09-14T04:00:00Z", bar.asMap().get("timestamp"));
        assertEquals(1_000_000.0, (Double) bar.asMap().get("volume"), 0.0001);
    }

    @Test
    void theClockReads() {
        MarketClock open = MarketClock.from(new JSONObject(StubAlpaca.clock(true)));
        assertTrue(open.open());
        assertTrue(open.describe().startsWith("Market is open"), open.describe());

        MarketClock closed = MarketClock.from(new JSONObject(StubAlpaca.clock(false)));
        assertFalse(closed.open());
        assertTrue(closed.describe().contains("closed"), closed.describe());
    }

    @Test
    void credentialsNeverPrintTheSecret() {
        // A record's generated toString would put the secret into any log line that interpolated
        // one, which is the kind of leak only noticed once it is in somebody's log file.
        AlpacaCredentials credentials = new AlpacaCredentials("PKID", "super-secret", true);
        assertFalse(credentials.toString().contains("super-secret"), credentials.toString());
        assertEquals("paper", credentials.accountKind());
        assertEquals(AlpacaApi.TRADING_PAPER, credentials.tradingHost());
        assertEquals(AlpacaApi.TRADING_LIVE,
                new AlpacaCredentials("AKID", "s", false).tradingHost());
    }

    /** The one snapshot out of a {@code {"snapshots": {...}}} body. */
    private static JSONObject snapshotOf(String body) {
        return new JSONObject(body).getJSONObject("snapshots").getJSONObject("AAPL");
    }
}
