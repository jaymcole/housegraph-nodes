package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.StubAlpaca;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders.PlaceOrderNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which branch actually fires when a Place Order node runs inside a real graph — the question no
 * test that calls {@code process()} directly can answer, because {@code activate()} outside a run
 * has no context to record itself on.
 *
 * <h2>Why this is the test worth having</h2>
 * A node that activates <em>no</em> flow-out falls back to firing <em>every</em> one of them. For
 * most nodes that default is a convenience. Here it is a trap: a Place Order node that threw before
 * deciding anything would fire <b>Placed</b>, and whatever is wired downstream — a Discord message
 * saying the order went in, a store recording a position the account does not hold, a second order
 * sized from the first — would run on an order that was never sent. The node's failure path says
 * {@code activateNone()} for exactly that reason, and this is where that is checked rather than
 * asserted in a comment.
 */
class OrderFlowGraphTest {

    /** Stands in for whatever drives the order: a trigger, a command, a branch. */
    private static final class Trigger extends BaseNode {
        private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(out);
        }
    }

    /** Stands in for whatever is wired to one of the order node's branches. */
    private static final class Recorder extends BaseNode {
        private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public void process(ProcessContext ctx) {
            runs.incrementAndGet();
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(in);
        }
    }

    private StubAlpaca alpaca;
    private NodeGraph graph;
    private Trigger trigger;
    private PlaceOrderNode order;
    private Recorder onPlaced;
    private Recorder onNotPlaced;

    @BeforeEach
    void setUp() throws IOException {
        alpaca = StubAlpaca.openConnected();
        alpaca.on("GET", "/v2/stocks/snapshots", 200, StubAlpaca.snapshots("AAPL", 100.0, 99.0));
        alpaca.on("POST", "/v2/orders", 200,
                StubAlpaca.order("order-1", "AAPL", "buy", "new").toString());

        graph = new NodeGraph();
        trigger = new Trigger();
        order = new PlaceOrderNode();
        onPlaced = new Recorder();
        onNotPlaced = new Recorder();
        for (BaseNode node : List.of(trigger, order, onPlaced, onNotPlaced)) {
            graph.addNode(node);
        }
        Nodes.set(order, "Account", alpaca.connectedSession());
        Nodes.set(order, "Symbol", "AAPL");
        Nodes.set(order, "Side", "buy");
        Nodes.set(order, "Quantity", 2.0);

        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, order, order.getFlowInputs().get(0)));
        graph.registerFlowEdge(
                new FlowEdge(order, Nodes.flowOut(order, "Placed"), onPlaced, onPlaced.in));
        graph.registerFlowEdge(
                new FlowEdge(order, Nodes.flowOut(order, "Not Placed"), onNotPlaced, onNotPlaced.in));
    }

    @AfterEach
    void tearDown() {
        alpaca.close();
    }

    private void fire() {
        graph.execute(trigger);
        graph.awaitIdle();
    }

    @Test
    void aDryRunTakesTheNotPlacedBranchAndOnlyThat() {
        fire();

        assertEquals(0, onPlaced.runs.get(), "Placed fired for an order that was never sent");
        assertEquals(1, onNotPlaced.runs.get());
        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty());
    }

    @Test
    void aRealOrderTakesThePlacedBranchAndOnlyThat() {
        Nodes.set(order, "Dry Run", false);

        fire();

        assertEquals(1, onPlaced.runs.get(), "the order went in but nothing downstream was told: "
                + order.getLastError());
        assertEquals(0, onNotPlaced.runs.get());
        assertEquals("order-1", Nodes.<String>get(order, "Order ID"));
    }

    @Test
    void anOrderTheCeilingRefusesFiresNeitherBranch() {
        // The one that matters. Without the node's activateNone(), an engine told about no
        // activation fires every flow-out - so a failure would announce a fill.
        Nodes.set(order, "Dry Run", false);
        Nodes.set(order, "Max Order Value ($)", 10.0);

        fire();

        assertNotNull(order.getLastError(), "the ceiling should have failed this order");
        assertEquals(0, onPlaced.runs.get(), "Placed fired for an order the ceiling refused");
        assertEquals(0, onNotPlaced.runs.get(), "Not Placed fired for an order that failed");
        assertTrue(alpaca.callsTo("POST", "/v2/orders").isEmpty());
    }

    @Test
    void anOrderAlpacaRefusesFiresNeitherBranch() {
        alpaca.only("POST", "/v2/orders", 422,
                StubAlpaca.error(40310000, "insufficient buying power"));
        Nodes.set(order, "Dry Run", false);

        fire();

        assertNotNull(order.getLastError());
        assertEquals(0, onPlaced.runs.get(), "Placed fired for an order Alpaca refused");
        assertEquals(0, onNotPlaced.runs.get());
    }
}
