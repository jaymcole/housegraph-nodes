package io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.Order;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * Asks what happened to one order, and branches on the answer.
 * <p>
 * <b>Placing an order and finding out how it went are different jobs, which is why they are
 * different nodes.</b> An order can sit queued until the next open, fill in pieces over an hour, or
 * be rejected a second after Alpaca accepted it — so "did it fill?" is a question with a schedule
 * attached, and schedules belong to triggers. Wire a repeating trigger into this node and the Place
 * Order node's <b>Order ID</b> into its Order ID, and the graph asks as often as it wants to know.
 * A Place Order that waited for its own fill would instead hold a graph open for hours and still
 * only work on one schedule: whichever one was built into it.
 *
 * <h2>The flow-outs</h2>
 * <ul>
 *   <li><b>Checked</b> fires every time, whatever the answer. It is the one to wire when the point
 *       is to record or display the state.</li>
 *   <li><b>Filled</b> fires when the order is completely filled.</li>
 *   <li><b>Cancelled</b> fires when it finished without filling — cancelled, expired at the close,
 *       or replaced.</li>
 *   <li><b>Rejected</b> fires when Alpaca or the exchange refused it.</li>
 * </ul>
 * An order still working fires only Checked. <b>Finished</b> says the same thing as "one of the last
 * three fired", for a graph that would rather branch on a boolean than on flow.
 *
 * <h2>Alpaca does not say why it rejected one</h2>
 * There is no reject-reason field on an Alpaca order — the explanation goes out over Alpaca's trade
 * update stream, which this library does not hold open — so <b>State</b> reads {@code rejected} and
 * that is all there is. Alpaca's own dashboard is where the reason is. It is worth knowing before
 * building a graph that expects to be told.
 * <p>
 * The other kind of refusal <em>is</em> explained: an order Alpaca will not even accept (bad
 * symbol, not enough buying power) fails the Place Order node outright, with Alpaca's own message.
 * That one never reaches this node, because it never became an order.
 * <p>
 * A state this library has not seen before is reported on <b>State</b> as Alpaca spelled it and
 * treated as still running — so a graph waiting on a fill keeps waiting rather than announcing one.
 */
@Display.Name("Order Status")
@Display.Description("Reads one Alpaca order and branches on filled, cancelled or rejected.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "order", "status", "state", "filled", "fill", "rejected", "cancelled",
        "pending", "check", "poll", "trade", "confirm"})
@Node.Type("alpaca.OrderStatusNode")
public class OrderStatusNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();
    private final NodeVariable<String> orderIdInput =
            new NodeVariable<>("Order ID", String.class, true).required();

    private final NodeVariable<String> stateOutput = new NodeVariable<>("State", String.class);
    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<String> sideOutput = new NodeVariable<>("Side", String.class);
    private final NodeVariable<Double> quantityOutput = new NodeVariable<>("Quantity", Double.class);
    private final NodeVariable<Double> filledQuantityOutput =
            new NodeVariable<>("Filled Quantity", Double.class);
    private final NodeVariable<Double> averagePriceOutput =
            new NodeVariable<>("Average Price", Double.class);
    private final NodeVariable<Double> filledValueOutput = new NodeVariable<>("Filled Value", Double.class);
    private final NodeVariable<Boolean> finishedOutput = new NodeVariable<>("Finished", Boolean.class);
    private final NodeVariable<String> filledAtOutput = new NodeVariable<>("Filled At", String.class);
    private final NodeVariable<String> summaryOutput = new NodeVariable<>("Summary", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort checked = new FlowPort("Checked", FlowPort.Direction.OUT);
    private final FlowPort filled = new FlowPort("Filled", FlowPort.Direction.OUT);
    private final FlowPort cancelled = new FlowPort("Cancelled", FlowPort.Direction.OUT);
    private final FlowPort rejected = new FlowPort("Rejected", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the order, publishes it, and fires Checked plus whichever outcome port the state calls
     * for. See {@code GetQuoteNode.process} for why the failure path says "nothing fires" — here it
     * is what stops a failed read from firing Filled.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            AlpacaSession session = Inputs.session(accountInput);
            String orderId = orderIdInput.getValue();

            ctx.checkCancelled();
            Order order = session.order(orderId);

            stateOutput.setValue(order.displayState());
            symbolOutput.setValue(order.symbol());
            sideOutput.setValue(order.side() == null ? null : order.side().wireValue());
            quantityOutput.setValue(order.quantity());
            filledQuantityOutput.setValue(order.filledQuantity());
            averagePriceOutput.setValue(order.averagePrice());
            filledValueOutput.setValue(order.filledValue());
            finishedOutput.setValue(order.state().isTerminal());
            filledAtOutput.setValue(order.filledAt());
            summaryOutput.setValue(order.describe());

            activate(checked);
            if (order.state().isFilled()) {
                activate(filled);
            } else if (order.state().isRejected()) {
                activate(rejected);
            } else if (order.state().isTerminal()) {
                activate(cancelled);
            }
        } catch (RuntimeException failure) {
            // See GetQuoteNode.process: nothing fires on a failure, and saying so has to come last.
            activateNone();
            throw failure;
        }
    }

    @Override
    public void configureInputs() {
        addInput(accountInput);
        addInput(orderIdInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(stateOutput);
        addOutput(symbolOutput);
        addOutput(sideOutput);
        addOutput(quantityOutput);
        addOutput(filledQuantityOutput);
        addOutput(averagePriceOutput);
        addOutput(filledValueOutput);
        addOutput(finishedOutput);
        addOutput(filledAtOutput);
        addOutput(summaryOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(checked);
        addFlowOutput(filled);
        addFlowOutput(cancelled);
        addFlowOutput(rejected);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        statusLabel = Status.label("Not run yet");
        return statusLabel;
    }

    @Override
    protected void onExecuted() {
        Throwable error = getLastError();
        if (error != null) {
            Status.set(statusLabel, "Failed - " + error.getMessage());
            return;
        }
        String summary = summaryOutput.getValue();
        Status.set(statusLabel, summary == null ? "Not run yet" : summary);
    }
}
