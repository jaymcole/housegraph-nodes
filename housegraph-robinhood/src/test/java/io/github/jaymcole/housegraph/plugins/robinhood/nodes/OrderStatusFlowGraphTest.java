package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.StubRobinhood;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Which of the Order Status node's four branches fire for each outcome, driven through a real
 * {@link NodeGraph} because {@code activate()} records nothing outside a run.
 * <p>
 * Two things are being pinned down. That <b>Checked fires every time</b> — it is what a graph wires
 * to record the state, and a version that only fired it for unfinished orders would silently stop
 * logging the moment an order completed. And that <b>Checked fires alongside an outcome</b> rather
 * than instead of it, which is an assumption about the engine (a node may activate several ports in
 * one pass) rather than about this library, so it is worth a test that would notice it changing.
 */
class OrderStatusFlowGraphTest {

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

    private StubRobinhood robinhood;
    private NodeGraph graph;
    private Trigger trigger;
    private OrderStatusNode status;
    private final Map<String, Recorder> recorders = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        robinhood = StubRobinhood.openLoggedIn();
        robinhood.on("GET", "/instruments/AAPL/", 200, "{\"symbol\": \"AAPL\"}");

        graph = new NodeGraph();
        trigger = new Trigger();
        status = new OrderStatusNode();
        graph.addNode(trigger);
        graph.addNode(status);
        Nodes.set(status, "Account", robinhood.connectedSession());
        Nodes.set(status, "Order ID", "order-1");
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, status, status.getFlowInputs().get(0)));

        for (FlowPort port : status.getFlowOutputs()) {
            Recorder recorder = new Recorder();
            graph.addNode(recorder);
            graph.registerFlowEdge(new FlowEdge(status, port, recorder, recorder.in));
            recorders.put(port.name, recorder);
        }
    }

    @AfterEach
    void tearDown() {
        robinhood.close();
    }

    /** Runs the graph against an order in {@code state} and returns which branches fired. */
    private Map<String, Integer> fireFor(String state) {
        robinhood.only("GET", "/orders/order-1/", 200,
                StubRobinhood.order("order-1", "AAPL", "buy", state)
                        .put("instrument", robinhood.address() + "/instruments/AAPL/").toString());
        graph.execute(trigger);
        graph.awaitIdle();
        assertNotNull(Nodes.get(status, "State"), "the status read failed: " + status.getLastError());
        Map<String, Integer> fired = new LinkedHashMap<>();
        recorders.forEach((name, recorder) -> fired.put(name, recorder.runs.get()));
        return fired;
    }

    @Test
    void aFilledOrderFiresCheckedAndFilled() {
        assertEquals(Map.of("Checked", 1, "Filled", 1, "Cancelled", 0, "Rejected", 0),
                fireFor("filled"));
    }

    @Test
    void aRejectedOrderFiresCheckedAndRejected() {
        assertEquals(Map.of("Checked", 1, "Filled", 0, "Cancelled", 0, "Rejected", 1),
                fireFor("rejected"));
        assertEquals("Not enough buying power", Nodes.<String>get(status, "Reject Reason"));
    }

    @Test
    void aCancelledOrderFiresCheckedAndCancelled() {
        assertEquals(Map.of("Checked", 1, "Filled", 0, "Cancelled", 1, "Rejected", 0),
                fireFor("canceled"));
    }

    @Test
    void anOrderStillWorkingFiresOnlyChecked() {
        assertEquals(Map.of("Checked", 1, "Filled", 0, "Cancelled", 0, "Rejected", 0),
                fireFor("confirmed"));
        assertEquals(false, Nodes.<Boolean>get(status, "Finished"));
    }

    @Test
    void aStateThisLibraryHasNotSeenFiresOnlyCheckedAndIsReportedAsRobinhoodSpeltIt() {
        assertEquals(Map.of("Checked", 1, "Filled", 0, "Cancelled", 0, "Rejected", 0),
                fireFor("pending_some_new_robinhood_state"));
        assertEquals("pending_some_new_robinhood_state", Nodes.<String>get(status, "State"));
    }

    @Test
    void aFailedReadFiresNothingAtAll() {
        robinhood.only("GET", "/orders/order-1/", 404, "{\"detail\": \"Not found.\"}");

        graph.execute(trigger);
        graph.awaitIdle();

        assertNotNull(status.getLastError());
        assertEquals(List.of(0, 0, 0, 0), recorders.values().stream().map(r -> r.runs.get()).toList());
    }
}
