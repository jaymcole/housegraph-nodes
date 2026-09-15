package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaException;
import io.github.jaymcole.housegraph.plugins.alpaca.StubAlpaca;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders.PlaceOrderNode;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node that spends money, and the things that stop it spending money it shouldn't.
 * <p>
 * Every test here asserts on what reached the stub, not on what the node said it did — because the
 * failure worth catching is precisely the one where the node reports a dry run and places an order
 * anyway, or reports a refusal after the order has already gone.
 */
class PlaceOrderNodeTest {

    private StubAlpaca alpaca;
    private PlaceOrderNode node;

    @BeforeEach
    void setUp() throws IOException {
        alpaca = StubAlpaca.openConnected();
        alpaca.on("GET", "/v2/stocks/snapshots", 200, StubAlpaca.snapshots("AAPL", 100.0, 99.0));
        alpaca.on("POST", "/v2/orders", 200,
                StubAlpaca.order("order-1", "AAPL", "buy", "new").toString());

        node = new PlaceOrderNode();
        Nodes.set(node, "Account", alpaca.connectedSession());
        Nodes.set(node, "Symbol", "AAPL");
        Nodes.set(node, "Side", "buy");
        Nodes.set(node, "Quantity", 2.0);
    }

    @AfterEach
    void tearDown() {
        alpaca.close();
    }

    @Test
    void placesNothingUntilDryRunIsTurnedOff() {
        // The default the node ships with. A freshly dropped node wired into a trigger must not
        // trade on its first run.
        Nodes.run(node);

        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty());
        assertEquals(false, Nodes.<Boolean>get(node, "Was Placed"));
        assertEquals("dry run", Nodes.<String>get(node, "State"));
        assertNull(Nodes.get(node, "Order ID"));
    }

    @Test
    void aDryRunStillWorksTheOrderOutAndSaysWhatItWouldHaveDone() {
        Nodes.run(node);

        assertEquals(200.0, Nodes.<Double>get(node, "Estimated Value"));
        String summary = Nodes.get(node, "Summary");
        assertTrue(summary.startsWith("DRY RUN"), summary);
        assertTrue(summary.contains("buy 2 AAPL"), summary);
        assertTrue(summary.contains("Turn Dry Run off"), summary);
    }

    @Test
    void anUnsetDryRunPortIsStillADryRun() {
        // A Boolean port reads null when nothing has been set, and null must not mean "trade".
        Nodes.set(node, "Dry Run", null);

        Nodes.run(node);

        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty());
    }

    @Test
    void placesTheOrderOnceDryRunIsOff() {
        Nodes.set(node, "Dry Run", false);

        Nodes.run(node);

        assertEquals(1, alpaca.callsTo("POST", "/v2/orders").size());
        assertEquals("order-1", Nodes.<String>get(node, "Order ID"));
        assertEquals(true, Nodes.<Boolean>get(node, "Was Placed"));
        assertEquals("new", Nodes.<String>get(node, "State"));
    }

    @Test
    void whatGoesOnTheWireIsWhatTheNodesPortsSaid() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Order Type", "limit");
        Nodes.set(node, "Limit Price", 95.5);
        Nodes.set(node, "Time In Force", "gtc");

        Nodes.run(node);

        JSONObject body = alpaca.lastCallTo("POST", "/v2/orders").json();
        assertEquals("AAPL", body.getString("symbol"));
        assertEquals("buy", body.getString("side"));
        assertEquals("limit", body.getString("type"));
        assertEquals("95.50", body.getString("limit_price"));
        assertEquals("gtc", body.getString("time_in_force"));
        assertEquals("2", body.getString("qty"));
    }

    @Test
    void aDollarAmountGoesOutAsNotionalRatherThanBeingDividedIntoShares() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Quantity", null);
        Nodes.set(node, "Amount ($)", 100.0);

        Nodes.run(node);

        JSONObject body = alpaca.lastCallTo("POST", "/v2/orders").json();
        assertEquals("100.00", body.getString("notional"));
        assertFalse(body.has("qty"));
    }

    @Test
    void theCeilingRefusesAnOrderWorthMoreThanItAndPlacesNothing() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Max Order Value ($)", 100.0);

        AlpacaException failure = assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("over the Max Order Value"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Nothing was placed"), failure.getMessage());
        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty());
    }

    @Test
    void theCeilingAllowsAnOrderUnderIt() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Max Order Value ($)", 500.0);

        Nodes.run(node);

        assertEquals(1, alpaca.callsTo("POST", "/v2/orders").size());
    }

    @Test
    void anUncheckableCeilingFailsRatherThanLettingTheOrderThrough() {
        // An unchecked cap is not a cap. If the feed reported no price, the order cannot be valued,
        // and a graph that set a ceiling asked not to spend blind.
        alpaca.only("GET", "/v2/stocks/snapshots", 200, StubAlpaca.emptySnapshots("AAPL"));
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Max Order Value ($)", 500.0);

        AlpacaException failure = assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("cannot be valued"), failure.getMessage());
        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty());
    }

    @Test
    void aMalformedOrderNeverReachesAlpaca() {
        // Validation happens before anything is sent, so a mistake costs no round trip and names
        // the port to fix rather than coming back as a 422 naming a JSON field.
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Order Type", "limit");

        AlpacaException failure = assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("Limit Price"), failure.getMessage());
        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty());
        assertTrue(alpaca.callsTo("GET", "/v2/stocks/snapshots").isEmpty());
    }

    @Test
    void anOrderAlpacaRefusesFailsTheNodeWithAlpacasOwnReason() {
        alpaca.only("POST", "/v2/orders", 422,
                StubAlpaca.error(40310000, "insufficient buying power"));
        Nodes.set(node, "Dry Run", false);

        AlpacaException failure = assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("insufficient buying power"), failure.getMessage());
        assertEquals(false, Nodes.<Boolean>get(node, "Was Placed"));
    }

    @Test
    void anUnconnectedAccountFailsBeforeTheOrderIsEvenWorkedOut() {
        Nodes.set(node, "Account", alpaca.session());
        Nodes.set(node, "Dry Run", false);

        AlpacaException failure = assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("not connected"), failure.getMessage());
    }

    @Test
    void theNodeReportsWhichAccountItIsTradingOn() {
        // Which is what a graph with both a paper account and a live one wants on a display.
        Nodes.run(node);

        assertEquals(true, Nodes.<Boolean>get(node, "Is Paper"));
    }

    @Test
    void everyPortTheDocumentationNamesIsActuallyThere() {
        // The javadoc is the node's documentation, and a renamed port silently makes it wrong.
        assertTrue(Nodes.inputNames(node).containsAll(java.util.List.of(
                "Account", "Symbol", "Side", "Quantity", "Amount ($)", "Order Type", "Limit Price",
                "Stop Price", "Trail Price", "Trail Percent", "Time In Force", "Extended Hours",
                "Max Order Value ($)", "Dry Run")), Nodes.inputNames(node).toString());
        assertEquals(java.util.List.of("Placed", "Not Placed"), Nodes.flowOutputNames(node));
    }
}
