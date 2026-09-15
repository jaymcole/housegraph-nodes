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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The account's recent orders, newest first, as a list the collections library can work on.
 * <p>
 * <b>Open Only</b> is on to begin with, because the common reason to ask is "what is still out
 * there?" — the graph that cancels everything still working at 15:55, or the one that refuses to
 * place a second order while the first is unfilled. Turn it off to see the day's finished orders
 * too.
 * <p>
 * <b>Orders</b> is a list of maps, one per order, keyed {@code id}, {@code symbol}, {@code side},
 * {@code status}, {@code qty}, {@code filled_qty}, {@code filled_avg_price}, {@code type},
 * {@code limit_price}, {@code stop_price}, {@code time_in_force}, {@code submitted_at} — Alpaca's
 * own field names, so a graph and Alpaca's documentation say the same words. A field Alpaca did not
 * report is left out of its map rather than set to null, so Map Contains tells the truth about it.
 * <p>
 * <b>Order IDs</b> is the same list reduced to ids, which is what a For Each node feeding a Cancel
 * Order or Order Status node wants.
 */
@Display.Name("Get Recent Orders")
@Display.Description("Lists an Alpaca account's recent orders, newest first.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "orders", "recent", "history", "open", "list", "pending", "working",
        "trades", "activity", "audit"})
@Node.Type("alpaca.GetRecentOrdersNode")
public class GetRecentOrdersNode extends BaseNode implements NodeContentProvider {

    /**
     * The type a list port declares. A data port's type is a bare {@link Class}, so a list port is
     * {@code List.class} with its element type erased; laundering it through {@code Class<?>} once
     * here is the same move {@code housegraph-collections} makes.
     */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    /** More than a day's orders for most graphs, and well under Alpaca's cap of 500. */
    static final int DEFAULT_LIMIT = 50;

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();
    private final NodeVariable<Integer> limitInput =
            withDefault(new NodeVariable<>("Limit", Integer.class, true), DEFAULT_LIMIT);
    private final NodeVariable<Boolean> openOnlyInput =
            withDefault(new NodeVariable<>("Open Only", Boolean.class, true), Boolean.TRUE);

    private final NodeVariable<List<?>> ordersOutput = new NodeVariable<>("Orders", LIST);
    private final NodeVariable<List<?>> orderIdsOutput = new NodeVariable<>("Order IDs", LIST);
    private final NodeVariable<Integer> countOutput = new NodeVariable<>("Count", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the orders and publishes them. See {@code GetQuoteNode.process} for why the failure path
     * says "nothing fires" rather than leaving it to the engine's default.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            AlpacaSession session = Inputs.session(accountInput);
            int limit = Inputs.countOr(limitInput, DEFAULT_LIMIT);
            boolean openOnly = !Boolean.FALSE.equals(openOnlyInput.getValue());

            ctx.checkCancelled();
            List<Order> orders = session.recentOrders(limit, openOnly);

            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (Order order : orders) {
                rows.add(order.asMap());
                if (order.id() != null) {
                    ids.add(order.id());
                }
            }

            ordersOutput.setValue(List.copyOf(rows));
            orderIdsOutput.setValue(List.copyOf(ids));
            countOutput.setValue(rows.size());
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
        addInput(limitInput);
        addInput(openOnlyInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(ordersOutput);
        addOutput(orderIdsOutput);
        addOutput(countOutput);
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
        if (count == null) {
            Status.set(statusLabel, "Not run yet");
            return;
        }
        boolean openOnly = !Boolean.FALSE.equals(openOnlyInput.getValue());
        Status.set(statusLabel, count + (openOnly ? " open" : " recent")
                + (count == 1 ? " order" : " orders"));
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
