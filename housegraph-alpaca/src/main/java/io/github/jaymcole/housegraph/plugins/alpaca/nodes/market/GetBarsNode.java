package io.github.jaymcole.housegraph.plugins.alpaca.nodes.market;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaApi;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaException;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.Bar;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The last N candles for one symbol: open, high, low, close and volume over each slice of time.
 * <p>
 * <b>This is the node a strategy is built on.</b> A price on its own cannot say whether something is
 * going up; every moving average, every breakout, every "is this unusually busy" needs history, and
 * this is where a graph gets it. The <b>Closes</b> output exists for exactly that: it is a plain
 * list of numbers, which is what the collections library's List Statistics, Slice and Sort nodes
 * take.
 *
 * <h2>The ports</h2>
 * <b>Timeframe</b> is Alpaca's own spelling — {@code 1Min}, {@code 5Min}, {@code 15Min},
 * {@code 1Hour}, {@code 1Day}, {@code 1Week}, {@code 1Month}. <b>Limit</b> is how many bars, newest
 * last.
 * <p>
 * <b>Bars</b> is a list of maps, one per candle, keyed {@code timestamp}, {@code open},
 * {@code high}, {@code low}, {@code close}, {@code volume}, {@code trade_count}, {@code vwap} —
 * the shape a For Each node walks and a Map Get reads a field out of. <b>Closes</b> is the same list
 * reduced to closing prices, in the same order.
 * <p>
 * <b>Everything comes back oldest first.</b> That is the order an average or a trend wants, and it
 * is worth saying because it is not the order Alpaca is asked in: this node asks for the newest N
 * and turns them round, which is what lets "the last 50 daily bars" be a question with no dates in
 * it and no knowledge of which days the market was open.
 *
 * <h2>Two things that surprise people</h2>
 * <b>Prices are adjusted for splits and dividends.</b> A 4-for-1 split would otherwise put a cliff
 * in the middle of any average taken across it. The numbers therefore may not match what the symbol
 * actually traded at on the day.
 * <p>
 * <b>The bars are the feed's, not the market's.</b> On the default {@code iex} feed, volume is IEX's
 * share of trading rather than the market's, and a quiet symbol can have gaps. See Get Quote for
 * what the Feed input does about that.
 */
@Display.Name("Get Bars")
@Display.Description("Reads the last N OHLC bars for one stock or ETF, oldest first.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "bars", "candles", "ohlc", "history", "historical", "chart", "price",
        "average", "indicator", "strategy", "backtest", "market"})
@Node.Type("alpaca.GetBarsNode")
public class GetBarsNode extends BaseNode implements NodeContentProvider {

    /**
     * The type a list port declares. A data port's type is a bare {@link Class}, so a list port is
     * {@code List.class} with its element type erased; laundering it through {@code Class<?>} once
     * here is the same move {@code housegraph-collections} makes.
     */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    /** Enough for a 200-period average with room to spare, without being a large response. */
    static final int DEFAULT_LIMIT = 100;

    /** Alpaca's daily bar, the one most strategies are written against. */
    static final String DEFAULT_TIMEFRAME = "1Day";

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();
    private final NodeVariable<String> symbolInput =
            new NodeVariable<>("Symbol", String.class, true).required();
    private final NodeVariable<String> timeframeInput = withDefault(
            new NodeVariable<>("Timeframe", String.class, true), DEFAULT_TIMEFRAME)
                    .describedAs("Alpaca's own spelling: 1Min, 5Min, 15Min, 1Hour, 1Day, 1Week or "
                            + "1Month.");
    private final NodeVariable<Integer> limitInput =
            withDefault(new NodeVariable<>("Limit", Integer.class, true), DEFAULT_LIMIT)
                    .describedAs("How many bars to return, not a limit price - unrelated to \"Limit\" "
                            + "meaning a limit order elsewhere in this library.");
    private final NodeVariable<String> feedInput = withDefault(
            new NodeVariable<>("Feed", String.class, true), AlpacaApi.DEFAULT_FEED)
                    .describedAs("iex is what every account has; sip needs a market-data subscription "
                            + "and fails the node without one. Picking the wrong one here means "
                            + "misleading history, not an obvious error.");

    private final NodeVariable<List<?>> barsOutput = new NodeVariable<>("Bars", LIST)
            .describedAs("A list of maps, one per candle, keyed timestamp/open/high/low/close/volume/"
                    + "trade_count/vwap, oldest first.");
    private final NodeVariable<List<?>> closesOutput = new NodeVariable<>("Closes", LIST)
            .describedAs("Closing prices only, in the same oldest-first order as Bars.");
    private final NodeVariable<Integer> countOutput = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Double> latestCloseOutput =
            new NodeVariable<>("Latest Close", Double.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the bars and publishes them. See {@code GetQuoteNode.process} for why the failure path
     * says "nothing fires" rather than leaving it to the engine's default.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            AlpacaSession session = Inputs.session(accountInput);
            String symbol = Inputs.symbol(symbolInput);
            String timeframe = Inputs.textOr(timeframeInput, DEFAULT_TIMEFRAME);
            int limit = Inputs.countOr(limitInput, DEFAULT_LIMIT);
            String feed = Inputs.textOr(feedInput, AlpacaApi.DEFAULT_FEED);

            ctx.checkCancelled();
            List<Bar> bars = session.bars(symbol, timeframe, limit, feed);
            if (bars.isEmpty()) {
                // Not a silent empty list. A strategy that averaged nothing would carry on with a
                // null and go wrong somewhere further downstream, where the reason is much harder
                // to find than here.
                throw new AlpacaException("Alpaca's " + feed + " feed returned no " + timeframe
                        + " bars for " + symbol + ". Check the ticker and the timeframe spelling "
                        + "(1Min, 15Min, 1Hour, 1Day, 1Week, 1Month).");
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Double> closes = new ArrayList<>();
            for (Bar bar : bars) {
                rows.add(bar.asMap());
                if (bar.close() != null) {
                    closes.add(bar.close());
                }
            }

            barsOutput.setValue(List.copyOf(rows));
            closesOutput.setValue(List.copyOf(closes));
            countOutput.setValue(rows.size());
            latestCloseOutput.setValue(bars.get(bars.size() - 1).close());
            activate(out);
        } catch (RuntimeException failure) {
            // See GetQuoteNode.process: nothing fires on a failure, and saying so has to come last.
            activateNone();
            throw failure;
        }
    }

    @Override
    public void configureInputs() {
        addInput(accountInput);
        addInput(symbolInput);
        addInput(timeframeInput);
        addInput(limitInput);
        addInput(feedInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(barsOutput);
        addOutput(closesOutput);
        addOutput(countOutput);
        addOutput(latestCloseOutput);
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
        Integer count = countOutput.getValue();
        Double latest = latestCloseOutput.getValue();
        Status.set(statusLabel, count == null
                ? "Not run yet"
                : count + " bars" + (latest == null ? "" : ", last close " + latest));
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
