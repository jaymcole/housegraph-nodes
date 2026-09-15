package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaException;
import io.github.jaymcole.housegraph.plugins.alpaca.StubAlpaca;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.market.MarketClockNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The node that stops a strategy trading on Thanksgiving. */
class MarketClockNodeTest {

    private StubAlpaca alpaca;
    private MarketClockNode node;

    @BeforeEach
    void setUp() throws IOException {
        alpaca = StubAlpaca.openConnected();
        node = new MarketClockNode();
        Nodes.set(node, "Account", alpaca.connectedSession());
    }

    @AfterEach
    void tearDown() {
        alpaca.close();
    }

    @Test
    void readsTheRealCalendarRatherThanTheClockOnTheWall() {
        alpaca.on("GET", "/v2/clock", 200, StubAlpaca.clock(true));

        Nodes.run(node);

        assertEquals(true, Nodes.<Boolean>get(node, "Is Open"));
        assertEquals("2026-09-14T16:00:00-04:00", Nodes.<String>get(node, "Next Close"));
        assertEquals(1, alpaca.callsTo("GET", "/v2/clock").size());
    }

    @Test
    void aClosedMarketSaysWhenItOpensAgain() {
        alpaca.on("GET", "/v2/clock", 200, StubAlpaca.clock(false));

        Nodes.run(node);

        assertEquals(false, Nodes.<Boolean>get(node, "Is Open"));
        assertEquals("2026-09-15T09:30:00-04:00", Nodes.<String>get(node, "Next Open"));
    }

    @Test
    void itHasExactlyTwoBranchesSoThereIsNoThirdCaseToHandle() {
        assertEquals(List.of("Open", "Closed"), Nodes.flowOutputNames(node));
    }

    @Test
    void anUnreachableAlpacaFailsRatherThanReportingAClosedMarket() {
        // The dangerous failure would be answering "closed" when the truth is "don't know": a graph
        // that skips a day because a call failed has quietly changed its strategy.
        alpaca.on("GET", "/v2/clock", 500, StubAlpaca.error(50010000, "server error"));

        assertThrows(AlpacaException.class, () -> Nodes.run(node));
    }
}
