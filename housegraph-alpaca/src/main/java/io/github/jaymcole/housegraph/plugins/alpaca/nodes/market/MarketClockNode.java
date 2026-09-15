package io.github.jaymcole.housegraph.plugins.alpaca.nodes.market;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.MarketClock;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * Asks Alpaca whether the US equity market is open, and branches on the answer.
 * <p>
 * <b>Wire this in front of anything that trades.</b> A graph can work out that it is a Tuesday
 * afternoon; it cannot work out that this particular Tuesday is Juneteenth, or that Christmas Eve
 * closes at one o'clock. There are nine or ten such days a year, and on each of them a strategy
 * that assumed regular hours either does nothing and doesn't say so, or queues orders it believes
 * are going in now and that actually fill on the next open at a price nobody looked at. Alpaca
 * publishes the real calendar, so this asks.
 *
 * <h2>The flow-outs</h2>
 * <ul>
 *   <li><b>Open</b> fires when regular trading is live right now.</li>
 *   <li><b>Closed</b> fires when it isn't — before the open, after the close, at a weekend, on a
 *       holiday. <b>Next Open</b> says when that changes.</li>
 * </ul>
 * Exactly one of the two fires on a successful check, so wiring the trading half of a graph to Open
 * is enough; there is no third case to handle.
 * <p>
 * Note that <b>Is Open</b> is about the regular session. An extended-hours order is placeable while
 * this says closed — see Place Order's Extended Hours input — so a graph that trades pre-market
 * should branch on this node rather than gate on it.
 *
 * <h2>Why this is an action node and not a control node</h2>
 * It has two flow-outs and it looks like a branch, so it is worth being explicit: this node does not
 * decide <em>when</em> anything runs. It runs once when something upstream fires it, makes one call,
 * and reports which of two things was true at that moment — the same shape as Order Status. A node
 * that owned a timer and checked the clock on its own schedule would be the fused control-plus-action
 * design that {@code CLAUDE.md} warns about; the schedule belongs to a repeating trigger wired into
 * this node's flow-in.
 */
@Display.Name("Market Clock")
@Display.Description("Checks whether the US stock market is open right now, and branches on it.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "market", "clock", "open", "closed", "hours", "holiday", "calendar",
        "session", "trading", "time", "schedule"})
@Node.Type("alpaca.MarketClockNode")
public class MarketClockNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();

    private final NodeVariable<Boolean> isOpenOutput = new NodeVariable<>("Is Open", Boolean.class);
    private final NodeVariable<String> nextOpenOutput = new NodeVariable<>("Next Open", String.class);
    private final NodeVariable<String> nextCloseOutput = new NodeVariable<>("Next Close", String.class);
    private final NodeVariable<String> timestampOutput = new NodeVariable<>("Timestamp", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort openOut = new FlowPort("Open", FlowPort.Direction.OUT);
    private final FlowPort closedOut = new FlowPort("Closed", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the clock and fires exactly one of the two branches. See {@code GetQuoteNode.process}
     * for why the failure path says "nothing fires" — here it is what stops an unreachable Alpaca
     * from firing Open.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            AlpacaSession session = Inputs.session(accountInput);

            ctx.checkCancelled();
            MarketClock clock = session.clock();

            isOpenOutput.setValue(clock.open());
            nextOpenOutput.setValue(clock.nextOpen());
            nextCloseOutput.setValue(clock.nextClose());
            timestampOutput.setValue(clock.timestamp());
            activate(clock.open() ? openOut : closedOut);
        } catch (RuntimeException failure) {
            // See GetQuoteNode.process: nothing fires on a failure, and saying so has to come last.
            activateNone();
            throw failure;
        }
    }

    @Override
    public void configureInputs() {
        addInput(accountInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(isOpenOutput);
        addOutput(nextOpenOutput);
        addOutput(nextCloseOutput);
        addOutput(timestampOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(openOut);
        addFlowOutput(closedOut);
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
        Boolean open = isOpenOutput.getValue();
        if (open == null) {
            Status.set(statusLabel, "Not run yet");
            return;
        }
        Status.set(statusLabel, open
                ? "Open" + (nextCloseOutput.getValue() == null
                        ? "" : " until " + nextCloseOutput.getValue())
                : "Closed" + (nextOpenOutput.getValue() == null
                        ? "" : " until " + nextOpenOutput.getValue()));
    }
}
