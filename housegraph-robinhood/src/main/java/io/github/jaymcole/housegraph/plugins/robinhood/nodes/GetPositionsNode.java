package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.Position;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything the account holds, as a list the collections library can work on.
 * <p>
 * <b>Positions</b> is a list of maps — one per holding, keyed {@code symbol}, {@code quantity},
 * {@code average_buy_price}, {@code price}, {@code market_value}, {@code cost_basis},
 * {@code unrealised_gain}. That shape is deliberate: a For Each node walks it, Map Get pulls a
 * field out, and Filter by Number keeps the ones that are down 5% — none of which this library has
 * to invent ports for. A field Robinhood did not report is left out of its map rather than set to
 * null, so Map Contains tells the truth about it.
 * <p>
 * <b>Symbols</b> is the same list reduced to tickers, which is what most graphs actually want to
 * loop over.
 * <p>
 * <b>Include Prices</b> costs one extra call for the whole portfolio, not one per holding, and it is
 * what fills in {@code price}, {@code market_value} and {@code unrealised_gain}. Turn it off for a
 * graph that only cares what it owns, not what it is worth.
 * <p>
 * Holdings sold down to nothing are not listed. A holding whose instrument Robinhood will not
 * identify is dropped rather than listed as a row of blanks.
 */
@Display.Name("Get Positions")
@Display.Description("Lists what a Robinhood account holds, with quantities, cost and current value.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"robinhood", "positions", "holdings", "portfolio", "stocks", "shares", "owned",
        "list", "value", "gain", "loss", "finance"})
@Node.Type("robinhood.GetPositionsNode")
public class GetPositionsNode extends BaseNode implements NodeContentProvider {

    /**
     * The type a list port declares. A data port's type is a bare {@link Class}, so a list port is
     * {@code List.class} with its element type erased; laundering it through {@code Class<?>} once
     * here is the same move {@code housegraph-collections} makes.
     */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    private final NodeVariable<RobinhoodSession> accountInput =
            new NodeVariable<>("Account", RobinhoodSession.class, true).required().transientValue()
                    .describedAs("Wire this from a Robinhood Account node's Account output, or a "
                            + "Robinhood Account Ref node pointing at one.");
    private final NodeVariable<Boolean> includePricesInput =
            withDefault(new NodeVariable<>("Include Prices", Boolean.class, true), Boolean.TRUE)
                    .describedAs("Costs one extra call for the whole portfolio. On, it fills in price, "
                            + "market_value and unrealised_gain in Positions; off, those are left out.");

    private final NodeVariable<List<?>> positionsOutput = new NodeVariable<>("Positions", LIST)
            .describedAs("One map per holding, keyed symbol, quantity, average_buy_price, price, "
                    + "market_value, cost_basis and unrealised_gain.");
    private final NodeVariable<List<?>> symbolsOutput = new NodeVariable<>("Symbols", LIST)
            .describedAs("Positions reduced to plain ticker strings — the list to feed a For Each loop.");
    private final NodeVariable<Integer> countOutput = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Double> marketValueOutput = new NodeVariable<>("Market Value", Double.class)
            .describedAs("Null, not zero, when Include Prices was off or nothing could be priced — a "
                    + "portfolio worth nothing and a portfolio nobody priced must not look the same.");

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
            RobinhoodSession session = Inputs.session(accountInput);
            boolean withPrices = !Boolean.FALSE.equals(includePricesInput.getValue());

            ctx.checkCancelled();
            List<Position> positions = session.positions(withPrices);

            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> symbols = new ArrayList<>();
            double total = 0;
            boolean anyValue = false;
            for (Position position : positions) {
                rows.add(position.asMap());
                symbols.add(position.symbol());
                Double value = position.marketValue();
                if (value != null) {
                    total += value;
                    anyValue = true;
                }
            }

            positionsOutput.setValue(List.copyOf(rows));
            symbolsOutput.setValue(List.copyOf(symbols));
            countOutput.setValue(rows.size());
            // Null rather than zero when nothing could be priced: "the portfolio is worth nothing" and
            // "prices weren't fetched" are different answers, and a graph that alerts on a value
            // dropping must not see the second as the first.
            marketValueOutput.setValue(anyValue ? total : null);
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
        addInput(includePricesInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(positionsOutput);
        addOutput(symbolsOutput);
        addOutput(countOutput);
        addOutput(marketValueOutput);
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

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
