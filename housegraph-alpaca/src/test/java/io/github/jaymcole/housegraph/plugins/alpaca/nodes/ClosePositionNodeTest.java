package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaException;
import io.github.jaymcole.housegraph.plugins.alpaca.StubAlpaca;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.portfolio.ClosePositionNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The other node that spends money, and the holding it refuses to invent. */
class ClosePositionNodeTest {

    private StubAlpaca alpaca;
    private ClosePositionNode node;

    @BeforeEach
    void setUp() throws IOException {
        alpaca = StubAlpaca.openConnected();
        alpaca.on("GET", "/v2/positions", 200,
                StubAlpaca.list(StubAlpaca.position("AAPL", "2.5", "100.00")));
        alpaca.on("DELETE", "/v2/positions/AAPL", 200,
                StubAlpaca.order("order-9", "AAPL", "sell", "new").toString());

        node = new ClosePositionNode();
        Nodes.set(node, "Account", alpaca.connectedSession());
        Nodes.set(node, "Symbol", "AAPL");
    }

    @AfterEach
    void tearDown() {
        alpaca.close();
    }

    @Test
    void sellsNothingUntilDryRunIsTurnedOff() {
        Nodes.run(node);

        assertTrue(alpaca.callsTo("DELETE", "/v2/positions/AAPL").isEmpty());
        assertEquals(false, Nodes.<Boolean>get(node, "Was Closed"));
        assertTrue(Nodes.<String>get(node, "Summary").startsWith("DRY RUN"));
    }

    @Test
    void aDryRunStillLooksTheHoldingUpSoItCanSayWhatWouldGo() {
        // The value of a dry run here is that it also answers "is there a position at all?", which
        // is the question the node exists to avoid a graph having to ask for itself.
        Nodes.run(node);

        assertEquals(2.5, Nodes.<Double>get(node, "Quantity"));
        assertEquals(200.0, Nodes.<Double>get(node, "Market Value"));
        assertTrue(Nodes.<String>get(node, "Summary").contains("2.5 AAPL"),
                Nodes.<String>get(node, "Summary"));
    }

    @Test
    void closesTheWholePositionOnceDryRunIsOff() {
        Nodes.set(node, "Dry Run", false);

        Nodes.run(node);

        assertEquals(1, alpaca.callsTo("DELETE", "/v2/positions/AAPL").size());
        assertNull(alpaca.lastCallTo("DELETE", "/v2/positions/AAPL").query(),
                "no percentage means all of it");
        assertEquals("order-9", Nodes.<String>get(node, "Order ID"));
        assertEquals(true, Nodes.<Boolean>get(node, "Was Closed"));
    }

    @Test
    void aPercentageIsPassedThroughAndAHundredPercentMeansAllOfIt() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Percent", 50.0);
        Nodes.run(node);
        assertEquals("percentage=50", alpaca.lastCallTo("DELETE", "/v2/positions/AAPL").query());

        Nodes.set(node, "Percent", 100.0);
        Nodes.run(node);
        assertNull(alpaca.lastCallTo("DELETE", "/v2/positions/AAPL").query());
    }

    @Test
    void aNonsensePercentageIsRefusedBeforeAnythingIsSold() {
        Nodes.set(node, "Dry Run", false);
        Nodes.set(node, "Percent", 150.0);

        AlpacaException failure = assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("between 0 and 100"), failure.getMessage());
        assertTrue(alpaca.callsTo("DELETE", "/v2/positions/AAPL").isEmpty());
    }

    @Test
    void holdingNoneOfTheSymbolIsAnOutcomeRatherThanAFailure() {
        // A graph that closes a position when a stop is hit should be safe to run twice, and the
        // second run finding nothing is the normal case.
        alpaca.only("GET", "/v2/positions", 200, StubAlpaca.list());
        Nodes.set(node, "Dry Run", false);

        Nodes.run(node);

        assertNull(node.getLastError());
        assertEquals(false, Nodes.<Boolean>get(node, "Was Closed"));
        assertTrue(Nodes.<String>get(node, "Summary").contains("holds no AAPL"));
        assertTrue(alpaca.callsTo("DELETE", "/v2/positions/AAPL").isEmpty());
    }
}
