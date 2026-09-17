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
import io.github.jaymcole.housegraph.plugins.alpaca.OrderState;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * Asks Alpaca to cancel an order that hasn't finished.
 * <p>
 * <b>An order that was already done is not a failure here.</b> A cancel racing a fill is the normal
 * case, not the exceptional one — the order filled, or was cancelled in Alpaca's dashboard, or
 * expired at the close a moment before this ran — and a node that threw for it would make every
 * "cancel my stale orders" graph fragile by design. So a finished order fires <b>Too Late</b> and
 * reports the state it finished in; only an order that could not be read, or a cancel Alpaca
 * actually refused for some other reason, fails the node.
 * <p>
 * <b>Cancelled</b> fires when the order is no longer working. Cancelling is a request rather than an
 * act — Alpaca accepts it with no content at all and the exchange agrees a moment later — so this
 * node reads the order back afterwards and reports the state that actually resulted. An order that
 * is still {@code pending_cancel} when it is read back fires Cancelled too: the request is in, and
 * the order is no longer one a graph should expect to fill.
 * <p>
 * Wire it from a Get Recent Orders node walking the day's open orders, or straight from the Order ID
 * a Place Order node handed back.
 */
@Display.Name("Cancel Order")
@Display.Description("Cancels an Alpaca order that has not finished yet.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "cancel", "order", "stop", "withdraw", "pull", "abort", "trade"})
@Node.Type("alpaca.CancelOrderNode")
public class CancelOrderNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();
    private final NodeVariable<String> orderIdInput =
            new NodeVariable<>("Order ID", String.class, true).required();

    private final NodeVariable<String> stateOutput = new NodeVariable<>("State", String.class)
            .describedAs("The order's raw status after the cancel attempt. A cancel can race a fill, "
                    + "so this is not necessarily \"cancelled\".");
    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<Double> filledQuantityOutput =
            new NodeVariable<>("Filled Quantity", Double.class)
                    .describedAs("Shares filled, possibly fractional. Can be nonzero even on a Too "
                            + "Late outcome - the order filled before the cancel caught up with it.");
    private final NodeVariable<Boolean> wasCancelledOutput =
            new NodeVariable<>("Was Cancelled", Boolean.class);
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
            AlpacaSession session = Inputs.session(accountInput);
            String orderId = orderIdInput.getValue();

            ctx.checkCancelled();
            Order order = session.cancel(orderId);

            stateOutput.setValue(order.displayState());
            symbolOutput.setValue(order.symbol());
            filledQuantityOutput.setValue(order.filledQuantity());

            boolean wasCancelled = order.state() == OrderState.CANCELLED
                    || order.state() == OrderState.PENDING_CANCEL;
            wasCancelledOutput.setValue(wasCancelled);
            summaryOutput.setValue(wasCancelled
                    ? "Cancelled - " + order.describe()
                    : "Too late - " + order.describe());
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
        addOutput(filledQuantityOutput);
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
