package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.Order;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The account's most recent orders, newest first — everything placed, however it was placed, not
 * only what this graph did.
 * <p>
 * <b>Orders</b> is a list of maps, one per order, keyed {@code id}, {@code symbol}, {@code side},
 * {@code state}, {@code quantity}, {@code filled_quantity}, {@code average_price} and the rest — the
 * same shape Get Positions uses, so the collections library's For Each, Map Get and Filter nodes
 * work on it directly. <b>Open Order IDs</b> is the subset that hasn't finished, which is the list
 * a "cancel everything still working" graph wants to walk into a Cancel Order node.
 * <p>
 * <b>Limit</b> is how many to fetch, newest first, at most 50. Robinhood's order history pages, and
 * a graph that wanted the whole of it would be paging through years of it; this node reads the
 * first page, which is what "recent" means.
 */
@Display.Name("Get Recent Orders")
@Display.Description("Lists a Robinhood account's most recent orders, newest first.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"robinhood", "orders", "history", "recent", "list", "trades", "open", "pending",
        "filled", "account"})
@Node.Type("robinhood.GetRecentOrdersNode")
public class GetRecentOrdersNode extends BaseNode implements NodeContentProvider {

    /** See {@code GetPositionsNode.LIST} - a list port's type is {@code List.class}, element erased. */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    static final int DEFAULT_LIMIT = 10;

    private final NodeVariable<RobinhoodSession> accountInput =
            new NodeVariable<>("Account", RobinhoodSession.class, true).required().transientValue();
    private final NodeVariable<Integer> limitInput =
            withDefault(new NodeVariable<>("Limit", Integer.class, true), DEFAULT_LIMIT);

    private final NodeVariable<List<?>> ordersOutput = new NodeVariable<>("Orders", LIST);
    private final NodeVariable<List<?>> openOrderIdsOutput = new NodeVariable<>("Open Order IDs", LIST);
    private final NodeVariable<Integer> countOutput = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<String> latestOrderIdOutput =
            new NodeVariable<>("Latest Order ID", String.class);

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
            RobinhoodSession session = Inputs.session(accountInput);
            Integer limit = limitInput.getValue();

            ctx.checkCancelled();
            List<Order> orders = session.recentOrders(limit == null ? DEFAULT_LIMIT : limit);

            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> open = new ArrayList<>();
            for (Order order : orders) {
                rows.add(order.asMap());
                if (!order.state().isTerminal() && order.id() != null) {
                    open.add(order.id());
                }
            }

            ordersOutput.setValue(List.copyOf(rows));
            openOrderIdsOutput.setValue(List.copyOf(open));
            countOutput.setValue(rows.size());
            latestOrderIdOutput.setValue(orders.isEmpty() ? null : orders.get(0).id());
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
    }

    @Override
    public void configureOutputs() {
        addOutput(ordersOutput);
        addOutput(openOrderIdsOutput);
        addOutput(countOutput);
        addOutput(latestOrderIdOutput);
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
        List<?> open = openOrderIdsOutput.getValue();
        Status.set(statusLabel, count == null
                ? "Not run yet"
                : count + (count == 1 ? " order" : " orders")
                        + (open == null || open.isEmpty() ? "" : ", " + open.size() + " still open"));
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
