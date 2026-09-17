package io.github.jaymcole.housegraph.plugins.alpaca.nodes.market;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaApi;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.Quote;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * What one symbol is trading at right now.
 * <p>
 * <b>Price is the number to use.</b> It is the last trade when there has been one, the midpoint of
 * the bid and ask when there hasn't, and today's close as a last resort — which is what makes it
 * answer sensibly at 3am as well as at noon. Last Trade, Bid and Ask are there separately for a
 * graph that needs to tell them apart.
 *
 * <h2>Feed, and why the default is the quiet one</h2>
 * <b>Feed</b> is {@code iex} unless you change it, because that is the feed every Alpaca account
 * has. It is one exchange rather than the consolidated tape, so its last trade can lag the real one
 * slightly and its volume is a fraction of the market's — fine for "has this moved 3% today?",
 * misleading for "exactly what did it just trade at?".
 * <p>
 * Set it to {@code sip} if this account has a market-data subscription, and the numbers become the
 * consolidated ones. Setting it to {@code sip} <em>without</em> a subscription fails the node with a
 * message saying so, rather than quietly falling back — which is the right way round, because a
 * strategy silently reading a different feed than it thinks it is reading is worse than one that
 * stops.
 * <p>
 * <b>It reads; it never trades.</b> Wire it to a repeating trigger to watch a price, and wire what
 * it says into a branch node to decide something — the decision is the branch's job, and placing
 * anything is Place Order's.
 */
@Display.Name("Get Quote")
@Display.Description("Reads the current price, bid and ask for one stock or ETF.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "quote", "price", "stock", "share", "ticker", "symbol", "bid", "ask",
        "market", "value", "trading", "finance", "snapshot"})
@Node.Type("alpaca.GetQuoteNode")
public class GetQuoteNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();
    private final NodeVariable<String> symbolInput =
            new NodeVariable<>("Symbol", String.class, true).required();
    private final NodeVariable<String> feedInput = withDefault(
            new NodeVariable<>("Feed", String.class, true), AlpacaApi.DEFAULT_FEED)
                    .describedAs("Same iex/sip caveat as Get Bars: iex works on every account, sip "
                            + "needs a market-data subscription and fails the node without one.");

    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<Double> priceOutput = new NodeVariable<>("Price", Double.class)
            .describedAs("A synthesized best guess: the last trade when there has been one, else the "
                    + "bid/ask midpoint, else today's previous close. Not literally \"the\" price "
                    + "Alpaca reports - read Last Trade, Bid or Ask directly to be exact about which.");
    private final NodeVariable<Double> lastTradeOutput = new NodeVariable<>("Last Trade", Double.class);
    private final NodeVariable<Double> bidOutput = new NodeVariable<>("Bid", Double.class)
            .describedAs("The best price buyers are currently offering for the symbol.");
    private final NodeVariable<Double> askOutput = new NodeVariable<>("Ask", Double.class)
            .describedAs("The best price sellers currently want - the opposite side of Bid.");
    private final NodeVariable<Double> previousCloseOutput =
            new NodeVariable<>("Previous Close", Double.class);
    private final NodeVariable<Double> changeOutput = new NodeVariable<>("Change", Double.class)
            .describedAs("Dollar change from Previous Close, not a percent - see Change % for that.");
    private final NodeVariable<Double> changePercentOutput =
            new NodeVariable<>("Change %", Double.class)
                    .describedAs("A fraction, not a whole percent: 0.05 means up 5%, not up 0.05%.");
    private final NodeVariable<Double> dayHighOutput = new NodeVariable<>("Day High", Double.class);
    private final NodeVariable<Double> dayLowOutput = new NodeVariable<>("Day Low", Double.class);
    private final NodeVariable<Double> volumeOutput = new NodeVariable<>("Volume", Double.class)
            .describedAs("Feed-dependent: on the default iex feed this is IEX's own share of trading, "
                    + "a fraction of the real market's volume, not the whole day's.");

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the quote and publishes it.
     * <p>
     * {@link #activateNone()} on failure, and every node in this library does the same: a node that
     * has activated nothing when it throws leaves the engine to fall back on firing <em>every</em>
     * flow-out, which for this node would tell the branch downstream that a price arrived when none
     * did.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            AlpacaSession session = Inputs.session(accountInput);
            String symbol = Inputs.symbol(symbolInput);
            String feed = Inputs.textOr(feedInput, AlpacaApi.DEFAULT_FEED);

            ctx.checkCancelled();
            Quote quote = session.quote(symbol, feed);

            symbolOutput.setValue(quote.symbol());
            priceOutput.setValue(quote.price());
            lastTradeOutput.setValue(quote.lastTradePrice());
            bidOutput.setValue(quote.bidPrice());
            askOutput.setValue(quote.askPrice());
            previousCloseOutput.setValue(quote.previousClose());
            changeOutput.setValue(quote.changeFromPreviousClose());
            changePercentOutput.setValue(quote.changePercent());
            dayHighOutput.setValue(quote.dayHigh());
            dayLowOutput.setValue(quote.dayLow());
            volumeOutput.setValue(quote.dayVolume());
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
        addInput(feedInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(symbolOutput);
        addOutput(priceOutput);
        addOutput(lastTradeOutput);
        addOutput(bidOutput);
        addOutput(askOutput);
        addOutput(previousCloseOutput);
        addOutput(changeOutput);
        addOutput(changePercentOutput);
        addOutput(dayHighOutput);
        addOutput(dayLowOutput);
        addOutput(volumeOutput);
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
        if (error != null) {
            Status.set(statusLabel, "Failed - " + error.getMessage());
            return;
        }
        Double price = priceOutput.getValue();
        Double percent = changePercentOutput.getValue();
        Status.set(statusLabel, price == null
                ? "No price reported"
                : symbolOutput.getValue() + " " + price
                        + (percent == null ? "" : String.format(" (%+.2f%%)", percent * 100)));
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
