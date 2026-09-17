package io.github.jaymcole.housegraph.plugins.alpaca.nodes.portfolio;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaException;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.Order;
import io.github.jaymcole.housegraph.plugins.alpaca.Position;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sells a holding — all of it, or a percentage — at the market. <b>This node spends money.</b>
 *
 * <h2>Why this exists when Place Order can already sell</h2>
 * Because "sell everything I hold of X" is surprisingly hard to say as an ordinary order and easy to
 * get wrong. The exact quantity includes whatever fractional tail dividends and past fractional buys
 * have left behind; a graph would have to read the positions, find the row, pull the quantity out,
 * and place a sell for exactly that — and if it rounds, the position is not closed, it is 0.0003
 * shares smaller than it was. Alpaca closes a position as one call and works the quantity out
 * itself. <b>Percentage</b> covers "take half off the table" the same way.
 *
 * <h2>Dry Run is on to begin with, on purpose</h2>
 * A freshly dropped node reports what it <em>would</em> sell — the symbol, the quantity, what it is
 * currently worth — fires <b>Not Closed</b>, and sends nothing. Turn it off when the graph around it
 * is right. The reasoning is the same as Place Order's: a flow port fires when something upstream
 * says so, and the mistakes that are cheap everywhere else in HouseGraph are not cheap here.
 * <p>
 * The dry run is a real read, not a guess: it looks the position up, so it also tells you whether
 * there is one.
 *
 * <h2>Closed is not sold</h2>
 * <b>Closed</b> fires when Alpaca has <em>accepted</em> the liquidating order, which is not the same
 * as it having traded — outside market hours it will sit queued. Wire <b>Order ID</b> into an Order
 * Status node, driven by a repeating trigger, to find out what actually happened.
 * <p>
 * <b>Nothing to Close</b> fires when the account holds none of the symbol. That is not a failure: a
 * graph that closes a position when a stop is hit should be safe to run twice, and the second run
 * finding nothing is the normal outcome rather than an error worth halting a branch for.
 */
@Display.Name("Close Position")
@Display.Description("Sells all or part of an Alpaca holding at the market.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "close", "sell", "liquidate", "exit", "position", "holding", "flatten",
        "unwind", "trade", "stop"})
@Node.Type("alpaca.ClosePositionNode")
public class ClosePositionNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(ClosePositionNode.class);

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();
    private final NodeVariable<String> symbolInput =
            new NodeVariable<>("Symbol", String.class, true).required();
    private final NodeVariable<Double> percentInput =
            new NodeVariable<>("Percent", Double.class, true)
                    .describedAs("Blank closes the whole position (100%). Valid range is 0 to 100.");
    private final NodeVariable<Boolean> dryRunInput =
            withDefault(new NodeVariable<>("Dry Run", Boolean.class, true), Boolean.TRUE)
                    .describedAs("Defaults to true, for the same reason as Place Order's Dry Run: "
                            + "nothing is sold until this is explicitly turned off.");

    private final NodeVariable<String> orderIdOutput = new NodeVariable<>("Order ID", String.class)
            .describedAs("Null on a dry run, or when there is nothing to close.");
    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<Double> quantityOutput = new NodeVariable<>("Quantity", Double.class)
            .describedAs("Shares held. Null when there is nothing to close.");
    private final NodeVariable<Double> marketValueOutput =
            new NodeVariable<>("Market Value", Double.class)
                    .describedAs("The whole holding's value, even when Percent is closing only part "
                            + "of it.");
    private final NodeVariable<Double> unrealisedProfitOutput =
            new NodeVariable<>("Unrealised P/L", Double.class)
                    .describedAs("Dollars versus cost basis for the whole holding, not just the "
                            + "portion Percent is closing.");
    private final NodeVariable<Boolean> wasClosedOutput = new NodeVariable<>("Was Closed", Boolean.class);
    private final NodeVariable<String> summaryOutput = new NodeVariable<>("Summary", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort closed = new FlowPort("Closed", FlowPort.Direction.OUT);
    private final FlowPort notClosed = new FlowPort("Not Closed", FlowPort.Direction.OUT);
    private final FlowPort nothingToClose = new FlowPort("Nothing to Close", FlowPort.Direction.OUT);

    private Label statusLabel;

    public ClosePositionNode() {
        // One liquidation at a time from one node. Two overlapping runs is the shape of "the trigger
        // fired twice and sold the position twice"; the engine queues the second on this node's
        // permit instead.
        setMaxConcurrency(1);
    }

    /**
     * Finds the holding, reports it, and — unless this is a dry run — closes it.
     * <p>
     * The order of those steps is the design: everything that can refuse happens before anything is
     * sold, and the single call that sells is the last thing in the method, with a cancellation
     * check immediately before it.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            AlpacaSession session = Inputs.session(accountInput);
            String symbol = Inputs.symbol(symbolInput);
            BigDecimal percent = percentage();

            ctx.checkCancelled();
            Position holding = find(session, symbol);
            if (holding == null) {
                symbolOutput.setValue(symbol);
                quantityOutput.setValue(null);
                marketValueOutput.setValue(null);
                unrealisedProfitOutput.setValue(null);
                wasClosedOutput.setValue(false);
                summaryOutput.setValue("This account holds no " + symbol + ".");
                activate(nothingToClose);
                return;
            }
            publishHolding(holding);

            String what = (percent == null ? "all" : percent.toPlainString() + "%") + " of "
                    + describe(holding);
            if (isDryRun()) {
                wasClosedOutput.setValue(false);
                summaryOutput.setValue("DRY RUN - would have sold " + what
                        + ". Turn Dry Run off to place it.");
                log.info("Dry run: would have sold {}", what);
                activate(notClosed);
                return;
            }

            // The last moment a cancelled or superseded run can stop without having traded.
            ctx.checkCancelled();
            Order order = session.closePosition(symbol, percent);

            orderIdOutput.setValue(order.id());
            wasClosedOutput.setValue(true);
            summaryOutput.setValue("Selling " + what + " - " + order.displayState());
            activate(closed);
        } catch (RuntimeException failure) {
            // See GetQuoteNode.process: nothing fires on a failure, and saying so has to come last.
            activateNone();
            throw failure;
        }
    }

    /**
     * The holding in {@code symbol}, or null if there isn't one.
     * <p>
     * Read from the whole list rather than from Alpaca's per-symbol endpoint on purpose: that one
     * answers 404 for a symbol the account does not hold, and "you hold none of this" is an ordinary
     * answer here rather than an error. One request either way.
     */
    private static Position find(AlpacaSession session, String symbol) {
        List<Position> positions = session.positions();
        for (Position position : positions) {
            if (symbol.equalsIgnoreCase(position.symbol())) {
                return position;
            }
        }
        return null;
    }

    private void publishHolding(Position holding) {
        symbolOutput.setValue(holding.symbol());
        quantityOutput.setValue(holding.quantity());
        marketValueOutput.setValue(holding.marketValue());
        unrealisedProfitOutput.setValue(holding.unrealisedProfit());
        orderIdOutput.setValue(null);
        wasClosedOutput.setValue(false);
    }

    private static String describe(Position holding) {
        String text = holding.quantity() + " " + holding.symbol();
        return holding.marketValue() == null
                ? text
                : text + String.format(" (~$%,.2f)", holding.marketValue());
    }

    /**
     * The Percent input as a decimal, checked.
     *
     * @return the percentage to sell, or null for the whole holding
     * @throws AlpacaException if the value is outside 0 to 100
     */
    private BigDecimal percentage() {
        BigDecimal percent = Inputs.decimal(percentInput);
        if (percent == null) {
            return null;
        }
        if (percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new AlpacaException("Percent must be between 0 and 100 - got "
                    + percent.toPlainString() + ". Leave it empty to close the whole position.");
        }
        return percent.compareTo(BigDecimal.valueOf(100)) == 0 ? null : percent;
    }

    private boolean isDryRun() {
        // Anything other than an explicit false is a dry run: an unset Boolean port reads null, and
        // null must not mean "sell".
        return !Boolean.FALSE.equals(dryRunInput.getValue());
    }

    @Override
    public void configureInputs() {
        addInput(accountInput);
        addInput(symbolInput);
        addInput(percentInput);
        addInput(dryRunInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(orderIdOutput);
        addOutput(symbolOutput);
        addOutput(quantityOutput);
        addOutput(marketValueOutput);
        addOutput(unrealisedProfitOutput);
        addOutput(wasClosedOutput);
        addOutput(summaryOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(closed);
        addFlowOutput(notClosed);
        addFlowOutput(nothingToClose);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        statusLabel = Status.label(isDryRun() ? "Dry Run is on - nothing will be sold" : "Not run yet");
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

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
