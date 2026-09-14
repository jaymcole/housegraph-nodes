package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodException;
import io.github.jaymcole.housegraph.plugins.robinhood.StubRobinhood;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

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

    private StubRobinhood robinhood;
    private PlaceOrderNode node;

    @BeforeEach
    void setUp() throws IOException {
        robinhood = StubRobinhood.openLoggedIn();
        robinhood.on("GET", "/instruments/", 200,
                StubRobinhood.instrument("AAPL", robinhood.address() + "/instruments/AAPL/"));
        robinhood.on("GET", "/marketdata/quotes/AAPL/", 200,
                StubRobinhood.quote("AAPL", "100.00", "99.00").toString());
        robinhood.on("POST", "/orders/", 201,
                StubRobinhood.order("order-1", "AAPL", "buy", "queued").toString());

        node = new PlaceOrderNode();
        Nodes.set(node, "Account", robinhood.connectedSession());
        Nodes.set(node, "Symbol", "AAPL");
        Nodes.set(node, "Side", "buy");
        Nodes.set(node, "Quantity", 2.0);
    }

    @AfterEach
    void tearDown() {
        robinhood.close();
    }

    @Test
    void placesNothingUntilDryRunIsTurnedOff() {
        // The default the node ships with. A freshly dropped node wired into a trigger must not
        // trade on its first run.
        Nodes.run(node);

        assertTrue(robinhood.callsTo("POST", "/orders/").isEmpty());
        assertEquals(false, Nodes.<Boolean>get(node, "Was Placed"));
        assertEquals("dry run", Nodes.<String>get(node, "State"));
        assertNull(Nodes.get(node, "Order ID"));
    }

    @Test
    void aDryRunStillWorksTheOrderOutAndSaysWhatItWouldHaveDone() {
        Nodes.run(node);

        assertEquals(2.0, Nodes.<Double>get(node, "Ordered Quantity"));
        assertEquals(200.0, Nodes.<Double>get(node, "Estimated Value"));
        String summary = Nodes.get(node, "Summary");
        assertTrue(summary.startsWith("DRY RUN"), summary);
        assertTrue(summary.contains("buy 2 AAPL"), summary);
        assertTrue(summary.contains("Turn Dry Run off"), summary);
    }

    @Test
    void placesTheOrderOnceDryRunIsOff() {
        Nodes.set(node, "Dry Run", false);

        Nodes.run(node);

        assertEquals(1, robinhood.callsTo("POST", "/orders/").size());
        assertEquals("order-1", Nodes.<String>get(node, "Order ID"));
        assertEquals("queued", Nodes.<String>get(node, "State"));
        assertEquals(true, Nodes.<Boolean>get(node, "Was Placed"));

        JSONObject sent = robinhood.lastCallTo("POST", "/orders/").json();
        assertEquals("AAPL", sent.getString("symbol"));
        assertEquals("2", sent.getString("quantity"));
    }

    @Test
    void anUnsetDryRunPortIsStillADryRun() {
        // A Boolean port with nothing in it reads null, and null must not mean "trade".
        Nodes.set(node, "Dry Run", null);

        Nodes.run(node);

        assertTrue(robinhood.callsTo("POST", "/orders/").isEmpty());
    }

    @Test
    void refusesAnOrderOverTheCeilingWithoutPlacingIt() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Max Order Value ($)", 150.0);

        String message = assertThrows(RobinhoodException.class, () -> Nodes.run(node)).getMessage();

        assertTrue(message.contains("$200.00"), message);
        assertTrue(message.contains("Nothing was placed"), message);
        assertTrue(robinhood.callsTo("POST", "/orders/").isEmpty());
    }

    @Test
    void placesAnOrderThatFitsUnderTheCeiling() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Max Order Value ($)", 250.0);

        Nodes.run(node);

        assertEquals(1, robinhood.callsTo("POST", "/orders/").size());
    }

    @Test
    void refusesToGuessWhenACeilingIsSetAndNothingCanBePriced() {
        // An unchecked ceiling is not a ceiling. Better to stop than to place an order whose value
        // nobody could establish.
        robinhood.only("GET", "/marketdata/quotes/AAPL/", 200, "{}");
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Max Order Value ($)", 250.0);

        assertThrows(RobinhoodException.class, () -> Nodes.run(node));
        assertTrue(robinhood.callsTo("POST", "/orders/").isEmpty());
    }

    @Test
    void refusesAMisconfiguredOrderBeforeCallingRobinhoodAtAll() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Amount ($)", 500.0);

        // Quantity AND Amount: two different order sizes, and guessing between them could trade
        // 250 times what was meant.
        String message = assertThrows(RobinhoodException.class, () -> Nodes.run(node)).getMessage();

        assertTrue(message.contains("not both"), message);
        assertTrue(robinhood.callsTo("POST", "/orders/").isEmpty());
        assertTrue(robinhood.callsTo("GET", "/instruments/").isEmpty());
    }

    @Test
    void refusesAMistypedSideRatherThanDefaultingToOne() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Side", "byu");

        assertThrows(RobinhoodException.class, () -> Nodes.run(node));
        assertTrue(robinhood.callsTo("POST", "/orders/").isEmpty());
    }

    @Test
    void saysWhatToWireWhenNoAccountIsConnected() {
        PlaceOrderNode unwired = new PlaceOrderNode();
        Nodes.set(unwired, "Symbol", "AAPL");
        Nodes.set(unwired, "Quantity", 1.0);

        String message = assertThrows(RobinhoodException.class, () -> Nodes.run(unwired)).getMessage();

        assertTrue(message.contains("Robinhood Account"), message);
    }

    @Test
    void turnsADollarAmountIntoSharesAtTheCurrentPrice() {
        Nodes.set(node, "Quantity", null);
        Nodes.set(node, "Amount ($)", 50.0);
        Nodes.set(node, "Dry Run", false);

        Nodes.run(node);

        assertEquals("0.5", robinhood.lastCallTo("POST", "/orders/").json().getString("quantity"));
    }

    @Test
    void hasThePortsItsDocumentationDescribes() {
        assertEquals(List.of("Account", "Symbol", "Side", "Quantity", "Amount ($)", "Order Type",
                        "Limit Price", "Stop Price", "Time In Force", "Extended Hours",
                        "Max Order Value ($)", "Dry Run"),
                Nodes.inputNames(node));
        assertEquals(List.of("Order ID", "State", "Symbol", "Ordered Quantity", "Estimated Value",
                        "Was Placed", "Summary"),
                Nodes.outputNames(node));
        assertEquals(List.of("Placed", "Not Placed"), Nodes.flowOutputNames(node));
        assertEquals(1, node.getFlowInputs().size());
    }

    @Test
    void refusesToRunTwoOrdersAtOnceFromOneNode() {
        // "The trigger fired twice and bought twice" is the shape of the accident this prevents.
        assertEquals(1, node.getMaxConcurrency());
        assertFalse(node.isMisconfigured());
    }
}
