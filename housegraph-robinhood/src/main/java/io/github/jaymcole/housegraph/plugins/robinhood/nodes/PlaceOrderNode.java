package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.robinhood.Order;
import io.github.jaymcole.housegraph.plugins.robinhood.OrderRequest;
import io.github.jaymcole.housegraph.plugins.robinhood.OrderSide;
import io.github.jaymcole.housegraph.plugins.robinhood.OrderType;
import io.github.jaymcole.housegraph.plugins.robinhood.PreparedOrder;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodException;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.plugins.robinhood.TimeInForce;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

import java.math.BigDecimal;

/**
 * Places one order. <b>This is the node that spends money.</b>
 *
 * <h2>Dry Run is on to begin with, on purpose</h2>
 * A freshly dropped Place Order node works out the whole order — the instrument, the share count,
 * what it would cost — reports it on <b>Summary</b>, fires <b>Not Placed</b>, and sends nothing.
 * <b>Turn Dry Run off when the graph around it is right.</b>
 * <p>
 * The reason for that default is what this node is wired into. A flow port fires when something
 * upstream says so, and the mistakes that are cheap everywhere else in HouseGraph — a trigger
 * wired to the wrong port, a loop that runs once per item instead of once, a graph left running
 * after a test — are not cheap here. A node that shipped ready to trade would make the first run of
 * a half-built graph the expensive one. This way the first run tells you what it <em>would</em>
 * have done.
 *
 * <h2>Say the size once: Quantity or Amount ($)</h2>
 * <b>Quantity</b> is in shares and may be fractional. <b>Amount ($)</b> is in dollars and is turned
 * into a share count at the current price, rounding down, so "$100 of AAPL" never spends $100.02.
 * Fill in one or the other; filling in both fails the node rather than guessing which was meant.
 * <p>
 * Amount ($) is for market orders. A limit order priced off a quote it is specifically not taking
 * would be a share count derived from a fiction, so that combination fails too, naming the fix.
 *
 * <h2>Order Type</h2>
 * <ul>
 *   <li><b>market</b> — fill now, at whatever the market is. Robinhood collars market orders
 *       (they will not trade far past the current price), which this node sets from a quote taken
 *       moments before.</li>
 *   <li><b>limit</b> — fill at <b>Limit Price</b> or better, or don't fill.</li>
 *   <li><b>stop loss</b> — nothing happens until the price reaches <b>Stop Price</b>, then it
 *       becomes a market order.</li>
 *   <li><b>stop limit</b> — the same, but it becomes a limit order at <b>Limit Price</b>.</li>
 * </ul>
 * <b>Time In Force</b> is {@code gtc} (good till cancelled), {@code gfd} (cancelled at the close),
 * {@code ioc} or {@code opg}.
 *
 * <h2>Max Order Value ($)</h2>
 * A ceiling for unattended graphs: an order estimated to be worth more than this fails the node
 * instead of going out. That is a failure rather than a quiet skip on purpose — a graph that
 * believes it has bought something and hasn't is worse off than one that stops and says why. Leave
 * it empty for no ceiling. With a ceiling set, an order whose value cannot be estimated (Robinhood
 * reported no price) also fails, because an unchecked cap is not a cap.
 *
 * <h2>Placed is not filled</h2>
 * <b>Placed</b> fires when Robinhood has <em>accepted</em> the order, which is not the same as it
 * having traded. An order can sit queued for hours, fill in pieces, or be rejected a moment later —
 * and Robinhood reports a rejection in the order's own state rather than by refusing the request.
 * <b>Wire Order ID into an Order Status node</b>, driven by a repeating trigger, to find out what
 * actually happened; that is why checking is a separate node rather than a wait inside this one.
 * <p>
 * <b>Nothing is ever sent twice.</b> Each prepared order carries Robinhood's own idempotency key,
 * so a re-sent request is the same order rather than a second one — but this node does not retry on
 * its own, because a timed-out order may well have been received.
 */
@Display.Name("Place Order")
@Display.Description("Places a stock or ETF order on Robinhood - buy or sell, market, limit or stop.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"robinhood", "order", "buy", "sell", "trade", "trading", "market", "limit", "stop",
        "shares", "stock", "invest", "execute", "place"})
@Node.Type("robinhood.PlaceOrderNode")
public class PlaceOrderNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(PlaceOrderNode.class);

    private final NodeVariable<RobinhoodSession> accountInput =
            new NodeVariable<>("Account", RobinhoodSession.class, true).required().transientValue()
                    .describedAs("Wire this from a Robinhood Account node's Account output, or a "
                            + "Robinhood Account Ref node pointing at one.");
    private final NodeVariable<String> symbolInput =
            new NodeVariable<>("Symbol", String.class, true).required();
    private final NodeVariable<String> sideInput = withDefault(
            new NodeVariable<>("Side", String.class, true).required(), OrderSide.BUY.wireValue())
                    .describedAs("\"buy\" or \"sell\". Defaults to buy.");
    private final NodeVariable<Double> quantityInput = new NodeVariable<>("Quantity", Double.class, true)
            .describedAs("Shares to order, which may be fractional. Mutually exclusive with Amount ($) — "
                    + "fill in one or the other, not both.");
    private final NodeVariable<Double> amountInput = new NodeVariable<>("Amount ($)", Double.class, true)
            .describedAs("Dollars to spend, converted to a share count at the current price and rounded "
                    + "down so this never spends more than asked. Market orders only. Mutually exclusive "
                    + "with Quantity.");
    private final NodeVariable<String> orderTypeInput = withDefault(
            new NodeVariable<>("Order Type", String.class, true), OrderType.MARKET.label())
                    .describedAs("market, limit, stop loss or stop limit. market fills now at the going "
                            + "price; limit fills at Limit Price or better; stop loss becomes a market "
                            + "order once Stop Price is reached; stop limit becomes a limit order at "
                            + "Limit Price once Stop Price is reached.");
    private final NodeVariable<Double> limitPriceInput =
            new NodeVariable<>("Limit Price", Double.class, true)
                    .describedAs("Dollars per share. Required for limit and stop-limit orders — the "
                            + "order fills at this price or better, or not at all.");
    private final NodeVariable<Double> stopPriceInput = new NodeVariable<>("Stop Price", Double.class, true)
            .describedAs("Dollars per share at which a stop-loss or stop-limit order triggers.");
    private final NodeVariable<String> timeInForceInput = withDefault(
            new NodeVariable<>("Time In Force", String.class, true), TimeInForce.GTC.wireValue())
                    .describedAs("How long the order stays working: gtc (good till cancelled), gfd "
                            + "(cancelled at the close), ioc (immediate or cancel) or opg (at the "
                            + "opening auction).");
    private final NodeVariable<Boolean> extendedHoursInput =
            withDefault(new NodeVariable<>("Extended Hours", Boolean.class, true), Boolean.FALSE)
                    .describedAs("Off by default. On, makes the order eligible to fill during extended "
                            + "trading hours.");
    private final NodeVariable<Double> maxOrderValueInput =
            new NodeVariable<>("Max Order Value ($)", Double.class, true)
                    .describedAs("A ceiling in dollars. Blank means no ceiling. An order estimated to be "
                            + "worth more than this FAILS the node rather than being silently skipped.");
    private final NodeVariable<Boolean> dryRunInput =
            withDefault(new NodeVariable<>("Dry Run", Boolean.class, true), Boolean.TRUE)
                    .describedAs("On by default. While on, nothing is sent to Robinhood — the order is "
                            + "worked out and reported, but not placed. Turn it off deliberately once "
                            + "the graph around it is right.");

    private final NodeVariable<String> orderIdOutput = new NodeVariable<>("Order ID", String.class)
            .describedAs("Null during a dry run, and null before the order has been placed.");
    private final NodeVariable<String> stateOutput = new NodeVariable<>("State", String.class)
            .describedAs("Robinhood's order state after placement — or literally \"dry run\" when Dry "
                    + "Run is on, rather than a real Robinhood state.");
    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<Double> orderedQuantityOutput =
            new NodeVariable<>("Ordered Quantity", Double.class)
                    .describedAs("The resolved share count actually used — after converting Amount ($) "
                            + "to shares, if that's what was given.");
    private final NodeVariable<Double> estimatedValueOutput =
            new NodeVariable<>("Estimated Value", Double.class)
                    .describedAs("Computed before submission, to check against Max Order Value ($). May "
                            + "differ from what the order actually fills for.");
    private final NodeVariable<Boolean> wasPlacedOutput = new NodeVariable<>("Was Placed", Boolean.class)
            .describedAs("False for both a dry run and a failure — true only once the order was "
                    + "actually submitted to Robinhood.");
    private final NodeVariable<String> summaryOutput = new NodeVariable<>("Summary", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort placed = new FlowPort("Placed", FlowPort.Direction.OUT);
    private final FlowPort notPlaced = new FlowPort("Not Placed", FlowPort.Direction.OUT);

    private Label statusLabel;

    public PlaceOrderNode() {
        // One order at a time from one node. Two runs of the same Place Order node overlapping is
        // the shape of "the trigger fired twice and bought twice"; the engine queues the second on
        // this node's permit instead.
        setMaxConcurrency(1);
    }

    /**
     * Works the order out, checks it against the ceiling, and — unless this is a dry run — sends it.
     * <p>
     * The order of those steps is the whole design. Everything that can refuse the order happens
     * before anything is spent, and the single call that spends is the last thing in the method,
     * with a cancellation check immediately before it.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            RobinhoodSession session = Inputs.session(accountInput);
            OrderRequest request = authoredOrder();

            ctx.checkCancelled();
            PreparedOrder prepared = session.prepareOrder(request);
            publishPreparation(prepared);
            checkCeiling(prepared);

            if (isDryRun()) {
                stateOutput.setValue("dry run");
                wasPlacedOutput.setValue(false);
                summaryOutput.setValue("DRY RUN - would have " + prepared.describe()
                        + ". Turn Dry Run off to place it.");
                log.info("Dry run: would have {}", prepared.describe());
                activate(notPlaced);
                return;
            }

            // The last moment a cancelled or superseded run can stop without having traded.
            ctx.checkCancelled();
            Order order = session.submit(prepared);

            orderIdOutput.setValue(order.id());
            stateOutput.setValue(order.rawState() == null
                    ? order.state().wireValue() : order.rawState());
            symbolOutput.setValue(order.symbol() == null ? request.symbol() : order.symbol());
            wasPlacedOutput.setValue(true);
            summaryOutput.setValue(order.describe());
            activate(placed);
        } catch (RuntimeException failure) {
            // See GetQuoteNode.process: nothing fires on a failure, and saying so has to come last.
            activateNone();
            throw failure;
        }
    }

    /** The order exactly as the ports read it, with every combination checked before anything runs. */
    private OrderRequest authoredOrder() {
        return new OrderRequest(
                Inputs.symbol(symbolInput),
                OrderSide.parse(sideInput.getValue()),
                OrderType.parse(orderTypeInput.getValue()),
                Inputs.decimal(quantityInput),
                Inputs.decimal(amountInput),
                Inputs.decimal(limitPriceInput),
                Inputs.decimal(stopPriceInput),
                TimeInForce.parse(timeInForceInput.getValue()),
                Boolean.TRUE.equals(extendedHoursInput.getValue())).validate();
    }

    /**
     * Publishes what the order came to, before it is sent — so a dry run, a refusal by the ceiling
     * and a real placement all leave the same three numbers behind to look at.
     */
    private void publishPreparation(PreparedOrder prepared) {
        symbolOutput.setValue(prepared.request().symbol());
        orderedQuantityOutput.setValue(prepared.quantity().doubleValue());
        estimatedValueOutput.setValue(prepared.estimatedValue());
        orderIdOutput.setValue(null);
        wasPlacedOutput.setValue(false);
    }

    /**
     * Fails the node if the order is worth more than Max Order Value ($), or if that cannot be
     * established.
     *
     * @throws RobinhoodException when the order is over the ceiling, or cannot be valued
     */
    private void checkCeiling(PreparedOrder prepared) {
        BigDecimal ceiling = Inputs.decimal(maxOrderValueInput);
        if (ceiling == null) {
            return;
        }
        if (ceiling.signum() <= 0) {
            throw new RobinhoodException("Max Order Value ($) must be greater than zero - got "
                    + ceiling.toPlainString() + ". Leave it empty for no ceiling.");
        }
        Double value = prepared.estimatedValue();
        if (value == null) {
            throw new RobinhoodException("Max Order Value ($) is set, but Robinhood reported no price "
                    + "for " + prepared.request().symbol() + ", so this order cannot be valued and "
                    + "the ceiling cannot be checked. Nothing was placed.");
        }
        if (BigDecimal.valueOf(value).compareTo(ceiling) > 0) {
            throw new RobinhoodException(String.format(
                    "This order is worth about $%,.2f, over the Max Order Value ($) of %s. "
                            + "Nothing was placed.", value, ceiling.toPlainString()));
        }
    }

    private boolean isDryRun() {
        // Anything other than an explicit false is a dry run: an unset Boolean port reads null, and
        // null must not mean "trade".
        return !Boolean.FALSE.equals(dryRunInput.getValue());
    }

    @Override
    public void configureInputs() {
        addInput(accountInput);
        addInput(symbolInput);
        addInput(sideInput);
        addInput(quantityInput);
        addInput(amountInput);
        addInput(orderTypeInput);
        addInput(limitPriceInput);
        addInput(stopPriceInput);
        addInput(timeInForceInput);
        addInput(extendedHoursInput);
        addInput(maxOrderValueInput);
        addInput(dryRunInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(orderIdOutput);
        addOutput(stateOutput);
        addOutput(symbolOutput);
        addOutput(orderedQuantityOutput);
        addOutput(estimatedValueOutput);
        addOutput(wasPlacedOutput);
        addOutput(summaryOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(placed);
        addFlowOutput(notPlaced);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        statusLabel = Status.label(isDryRun() ? "Dry Run is on - nothing will be placed" : "Not run yet");
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
