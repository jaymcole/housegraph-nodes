package io.github.jaymcole.housegraph.plugins.alpaca.nodes.portfolio;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.Position;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything the account holds, priced, as a list the collections library can work on.
 * <p>
 * <b>Positions</b> is a list of maps — one per holding, keyed {@code symbol}, {@code qty},
 * {@code side}, {@code avg_entry_price}, {@code current_price}, {@code market_value},
 * {@code cost_basis}, {@code unrealized_pl}, {@code unrealized_plpc}, {@code change_today}. That
 * shape is deliberate: a For Each node walks it, Map Get pulls a field out, and Filter by Number
 * keeps the ones that are down 5% — none of which this library has to invent ports for.
 * <p>
 * <b>The keys are Alpaca's own field names</b>, so a graph and Alpaca's documentation say the same
 * words. {@code unrealized_plpc} is a <em>fraction</em>: -0.05 is down 5%, not down 5.
 * <p>
 * <b>Symbols</b> is the same list reduced to tickers, which is what most graphs actually want to
 * loop over.
 * <p>
 * Alpaca prices the holdings itself, so there is nothing to turn on and no extra call to pay for —
 * one request returns the lot, valued. A holding sold down to nothing is not listed. A short
 * position is listed with a negative {@code qty}.
 */
@Display.Name("Get Positions")
@Display.Description("Lists what an Alpaca account holds, with quantities, cost and current value.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "positions", "holdings", "portfolio", "stocks", "shares", "owned",
        "list", "value", "gain", "loss", "finance"})
@Node.Type("alpaca.GetPositionsNode")
public class GetPositionsNode extends BaseNode implements NodeContentProvider {

    /**
     * The type a list port declares. A data port's type is a bare {@link Class}, so a list port is
     * {@code List.class} with its element type erased; laundering it through {@code Class<?>} once
     * here is the same move {@code housegraph-collections} makes.
     */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();

    private final NodeVariable<List<?>> positionsOutput = new NodeVariable<>("Positions", LIST)
            .describedAs("A list of maps, one per holding. unrealized_plpc is a fraction: -0.05 is "
                    + "down 5%, not down 5.");
    private final NodeVariable<List<?>> symbolsOutput = new NodeVariable<>("Symbols", LIST);
    private final NodeVariable<Integer> countOutput = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Double> marketValueOutput =
            new NodeVariable<>("Market Value", Double.class)
                    .describedAs("0 means a genuinely empty portfolio. Null means Alpaca didn't report "
                            + "a value for at least one holding.");
    private final NodeVariable<Double> unrealisedProfitOutput =
            new NodeVariable<>("Unrealised P/L", Double.class)
                    .describedAs("Summed across every holding. Same 0-versus-null distinction as "
                            + "Market Value.");

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the holdings and publishes them. See {@code GetQuoteNode.process} for why the failure
     * path says "nothing fires" rather than leaving it to the engine's default.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            AlpacaSession session = Inputs.session(accountInput);

            ctx.checkCancelled();
            List<Position> positions = session.positions();

            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> symbols = new ArrayList<>();
            double totalValue = 0;
            double totalProfit = 0;
            boolean anyValue = false;
            boolean anyProfit = false;
            for (Position position : positions) {
                rows.add(position.asMap());
                symbols.add(position.symbol());
                if (position.marketValue() != null) {
                    totalValue += position.marketValue();
                    anyValue = true;
                }
                if (position.unrealisedProfit() != null) {
                    totalProfit += position.unrealisedProfit();
                    anyProfit = true;
                }
            }

            positionsOutput.setValue(List.copyOf(rows));
            symbolsOutput.setValue(List.copyOf(symbols));
            countOutput.setValue(rows.size());
            // Zero rather than null for an empty portfolio: an account that holds nothing really is
            // worth nothing in holdings, and that is a different thing from Alpaca not having said -
            // which is the null case just below.
            marketValueOutput.setValue(anyValue || rows.isEmpty() ? totalValue : null);
            unrealisedProfitOutput.setValue(anyProfit || rows.isEmpty() ? totalProfit : null);
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
    }

    @Override
    public void configureOutputs() {
        addOutput(positionsOutput);
        addOutput(symbolsOutput);
        addOutput(countOutput);
        addOutput(marketValueOutput);
        addOutput(unrealisedProfitOutput);
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
        Double value = marketValueOutput.getValue();
        Status.set(statusLabel, count == null
                ? "Not run yet"
                : count + (count == 1 ? " holding" : " holdings")
                        + (value == null ? "" : String.format(", $%,.2f", value)));
    }
}
