package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.Order;
import io.github.jaymcole.housegraph.plugins.robinhood.OrderState;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * Asks Robinhood to cancel an order that hasn't finished.
 * <p>
 * <b>An order that was already done is not a failure here.</b> A cancel racing a fill is the normal
 * case, not the exceptional one — the order was filled, or cancelled in the app, or pulled at the
 * close a moment before this ran — and a node that threw for it would make every "cancel my stale
 * orders" graph fragile by design. So a finished order fires <b>Too Late</b> and reports the state
 * it finished in; only an order that could not be read, or a cancel Robinhood actually refused,
 * fails the node.
 * <p>
 * <b>Cancelled</b> fires when the order is no longer working. Cancelling is a request rather than an
 * act — Robinhood accepts it and the exchange agrees a moment later — so this node reads the order
 * back afterwards and reports the state that actually resulted.
 * <p>
 * Wire it from a Get Recent Orders node walking the day's orders, or straight from the Order ID a
 * Place Order node handed back.
 */
@Display.Name("Cancel Order")
@Display.Description("Cancels a Robinhood order that has not finished yet.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"robinhood", "cancel", "order", "stop", "withdraw", "pull", "abort", "trade"})
@Node.Type("robinhood.CancelOrderNode")
public class CancelOrderNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<RobinhoodSession> accountInput =
            new NodeVariable<>("Account", RobinhoodSession.class, true).required().transientValue()
                    .describedAs("Wire this from a Robinhood Account node's Account output, or a "
                            + "Robinhood Account Ref node pointing at one.");
    private final NodeVariable<String> orderIdInput =
            new NodeVariable<>("Order ID", String.class, true).required()
                    .describedAs("Wire this from a Get Recent Orders node's Open Order IDs, or from "
                            + "Place Order's Order ID output.");

    private final NodeVariable<String> stateOutput = new NodeVariable<>("State", String.class)
            .describedAs("The order's state exactly as Robinhood spelled it, e.g. \"cancelled\" or "
                    + "\"queued\".");
    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<Boolean> wasCancelledOutput =
            new NodeVariable<>("Was Cancelled", Boolean.class)
                    .describedAs("False covers two different cases alike: the order was already "
                            + "filled, or it is still working and the cancel hasn't caught up yet.");
    private final NodeVariable<String> summaryOutput = new NodeVariable<>("Summary", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort cancelled = new FlowPort("Cancelled", FlowPort.Direction.OUT);
    private final FlowPort tooLate = new FlowPort("Too Late", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Cancels, or reports that there was nothing left to cancel. See {@code GetQuoteNode.process}
     * for why the failure path says "nothing fires".
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            RobinhoodSession session = Inputs.session(accountInput);
            String orderId = orderIdInput.getValue();

            ctx.checkCancelled();
            Order order = session.cancel(orderId);

            stateOutput.setValue(order.rawState() == null ? order.state().wireValue() : order.rawState());
            symbolOutput.setValue(order.symbol());

            boolean wasCancelled = order.state() == OrderState.CANCELLED;
            wasCancelledOutput.setValue(wasCancelled);
            // "Still working" after a cancel means the exchange has not caught up yet, not that the
            // cancel failed - but it is not a cancellation this node can claim yet, so it goes out the
            // same port as an order that had already finished, with the summary saying which it was.
            summaryOutput.setValue(order.state().isTerminal()
                    ? order.describe()
                    : order.describe() + " - the cancel was accepted but the order is still working; "
                            + "check it again in a moment");
            activate(wasCancelled ? cancelled : tooLate);
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
        addOutput(wasCancelledOutput);
        addOutput(summaryOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(cancelled);
        addFlowOutput(tooLate);
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
