package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.Quote;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * What one symbol is trading at right now.
 * <p>
 * <b>Price is the number to use.</b> Outside market hours Robinhood stops updating the last regular
 * trade and reports the extended-hours price in a field of its own; Price answers whichever of
 * those is current, which is the same rule the phone app's headline number follows. Last Trade and
 * Extended Hours Price are there separately for a graph that needs to tell them apart.
 * <p>
 * <b>It reads; it never trades.</b> Wire it to a repeating trigger to watch a price, and wire what
 * it says into a branch node to decide something — the decision is the branch's job, and placing
 * anything is Place Order's.
 * <p>
 * <b>Halted</b> is worth branching on before acting: a halted symbol will take an order and sit on
 * it until trading resumes, which is rarely what a graph that just decided to sell wants.
 */
@Display.Name("Get Quote")
@Display.Description("Reads the current price, bid and ask for one stock or ETF.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"robinhood", "quote", "price", "stock", "share", "ticker", "symbol", "bid", "ask",
        "market", "value", "trading", "finance"})
@Node.Type("robinhood.GetQuoteNode")
public class GetQuoteNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<RobinhoodSession> accountInput =
            new NodeVariable<>("Account", RobinhoodSession.class, true).required().transientValue();
    private final NodeVariable<String> symbolInput =
            new NodeVariable<>("Symbol", String.class, true).required();

    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<Double> priceOutput = new NodeVariable<>("Price", Double.class);
    private final NodeVariable<Double> lastTradeOutput = new NodeVariable<>("Last Trade", Double.class);
    private final NodeVariable<Double> extendedOutput =
            new NodeVariable<>("Extended Hours Price", Double.class);
    private final NodeVariable<Double> bidOutput = new NodeVariable<>("Bid", Double.class);
    private final NodeVariable<Double> askOutput = new NodeVariable<>("Ask", Double.class);
    private final NodeVariable<Double> previousCloseOutput =
            new NodeVariable<>("Previous Close", Double.class);
    private final NodeVariable<Double> changeOutput = new NodeVariable<>("Change", Double.class);
    private final NodeVariable<Boolean> haltedOutput = new NodeVariable<>("Halted", Boolean.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the quote and publishes it.
     * <p>
     * {@link #activateNone()} comes first, and every node in this library does the same: a node that
     * has activated nothing when it throws leaves the engine to fall back on firing <em>every</em>
     * flow-out, which for this node would tell the branch downstream that a price arrived when none
     * did. Deciding "nothing fires" up front and opting back in on success is correct whether or not
     * the host engine also discards activations on failure.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            RobinhoodSession session = Inputs.session(accountInput);
            String symbol = Inputs.symbol(symbolInput);

            ctx.checkCancelled();
            Quote quote = session.quote(symbol);

            symbolOutput.setValue(quote.symbol());
            priceOutput.setValue(quote.price());
            lastTradeOutput.setValue(quote.lastTradePrice());
            extendedOutput.setValue(quote.extendedHoursPrice());
            bidOutput.setValue(quote.bidPrice());
            askOutput.setValue(quote.askPrice());
            previousCloseOutput.setValue(quote.previousClose());
            changeOutput.setValue(quote.changeFromPreviousClose());
            haltedOutput.setValue(quote.tradingHalted());
            activate(out);
        } catch (RuntimeException failure) {
            // Nothing downstream may run. A node that activated no flow-out fires EVERY one of them
            // (see BaseNode.activate), so a failure left to that default would tell whatever is
            // wired here that the thing worked.
            //
            // It has to be the LAST word rather than the first: the engine reads activateNone() as a
            // veto over the whole cascade, including activations made after it, so "say nothing
            // fires up front, opt back in on success" silently fires nothing at all.
            activateNone();
            throw failure;
        }
    }

    @Override
    public void configureInputs() {
        addInput(accountInput);
        addInput(symbolInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(symbolOutput);
        addOutput(priceOutput);
        addOutput(lastTradeOutput);
        addOutput(extendedOutput);
        addOutput(bidOutput);
        addOutput(askOutput);
        addOutput(previousCloseOutput);
        addOutput(changeOutput);
        addOutput(haltedOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        statusLabel = Status.label("Not run yet");
        return statusLabel;
    }

    @Override
    protected void onExecuted() {
        Throwable error = getLastError();
        Double price = priceOutput.getValue();
        Status.set(statusLabel, error != null
                ? "Failed - " + error.getMessage()
                : price == null
                        ? "No price reported"
                        : symbolOutput.getValue() + " " + price
                                + (Boolean.TRUE.equals(haltedOutput.getValue()) ? " (halted)" : ""));
    }
}
