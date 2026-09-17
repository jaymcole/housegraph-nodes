package io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders;

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
import io.github.jaymcole.housegraph.plugins.alpaca.OrderRequest;
import io.github.jaymcole.housegraph.plugins.alpaca.OrderSide;
import io.github.jaymcole.housegraph.plugins.alpaca.OrderType;
import io.github.jaymcole.housegraph.plugins.alpaca.PreparedOrder;
import io.github.jaymcole.housegraph.plugins.alpaca.TimeInForce;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

import java.math.BigDecimal;

/**
 * Places one order. <b>This is the node that spends money</b> — unless the account it is wired to is
 * the paper one, where it spends nothing and behaves the same.
 *
 * <h2>Dry Run is on to begin with, on purpose</h2>
 * A freshly dropped Place Order node works the whole order out — the size, the price it would go in
 * at, what it would cost — reports it on <b>Summary</b>, fires <b>Not Placed</b>, and sends nothing.
 * <b>Turn Dry Run off when the graph around it is right.</b>
 * <p>
 * On a paper account, turning it off is the point: that is how a strategy gets exercised end to end
 * against real prices with nothing at stake. On a live account it is the moment the graph starts
 * spending, and it should be a moment somebody chose.
 * <p>
 * The reason for that default is what this node is wired into. A flow port fires when something
 * upstream says so, and the mistakes that are cheap everywhere else in HouseGraph — a trigger wired
 * to the wrong port, a loop that runs once per item instead of once, a graph left running after a
 * test — are not cheap here. A node that shipped ready to trade would make the first run of a
 * half-built graph the expensive one. This way the first run tells you what it <em>would</em> have
 * done.
 *
 * <h2>Say the size once: Quantity or Amount ($)</h2>
 * <b>Quantity</b> is in shares and may be fractional. <b>Amount ($)</b> is in dollars, and Alpaca
 * works the share count out itself at execution — so "$100 of AAPL" really is $100 of AAPL, not a
 * share count divided out beforehand and rounded. Fill in one or the other; filling in both fails
 * the node rather than guessing which was meant.
 * <p>
 * Alpaca accepts a dollar amount only on a <b>market order good for the day</b>, which is what the
 * defaults already are. Any other combination fails the node before anything is sent, naming the
 * fix.
 *
 * <h2>Order Type</h2>
 * <ul>
 *   <li><b>market</b> — fill now, at whatever the market is.</li>
 *   <li><b>limit</b> — fill at <b>Limit Price</b> or better, or don't fill.</li>
 *   <li><b>stop</b> — nothing happens until the price reaches <b>Stop Price</b>, then it becomes a
 *       market order.</li>
 *   <li><b>stop limit</b> — the same, but it becomes a limit order at <b>Limit Price</b>.</li>
 *   <li><b>trailing stop</b> — a stop that follows the price, staying <b>Trail Price</b> dollars or
 *       <b>Trail Percent</b> percent behind the best level seen. Alpaca maintains the trail itself,
 *       which is why this is one order rather than a graph that keeps replacing a stop.</li>
 * </ul>
 * <b>Time In Force</b> is {@code day} (the default — cancelled at the close), {@code gtc} (good till
 * cancelled), {@code opg}, {@code cls}, {@code ioc} or {@code fok}.
 * <p>
 * <b>Extended Hours</b> only works on a limit order good for the day, which is Alpaca's rule rather
 * than this library's: there is no continuous auction outside regular hours for a market order to
 * fill against.
 *
 * <h2>Max Order Value ($)</h2>
 * A ceiling for unattended graphs: an order estimated to be worth more than this fails the node
 * instead of going out. That is a failure rather than a quiet skip on purpose — a graph that
 * believes it has bought something and hasn't is worse off than one that stops and says why. Leave
 * it empty for no ceiling. With a ceiling set, an order whose value cannot be estimated (the feed
 * reported no price) also fails, because an unchecked cap is not a cap.
 *
 * <h2>Placed is not filled</h2>
 * <b>Placed</b> fires when Alpaca has <em>accepted</em> the order, which is not the same as it
 * having traded. An order can sit queued until the next open, fill in pieces, or be rejected by the
 * exchange a moment later — and Alpaca reports that second kind of rejection in the order's own
 * status rather than by refusing the request. <b>Wire Order ID into an Order Status node</b>, driven
 * by a repeating trigger, to find out what actually happened; that is why checking is a separate
 * node rather than a wait inside this one.
 * <p>
 * <b>Nothing is ever sent twice.</b> Each prepared order carries a unique {@code client_order_id},
 * which Alpaca refuses to reuse — so a re-sent request cannot become a second trade. This node does
 * not retry on its own, because a timed-out order may well have been received.
 */
@Display.Name("Place Order")
@Display.Description("Places a stock or ETF order on Alpaca - buy or sell, market, limit, stop or trailing.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "order", "buy", "sell", "trade", "trading", "market", "limit", "stop",
        "trailing", "shares", "stock", "invest", "execute", "place"})
@Node.Type("alpaca.PlaceOrderNode")
public class PlaceOrderNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(PlaceOrderNode.class);

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();
    private final NodeVariable<String> symbolInput =
            new NodeVariable<>("Symbol", String.class, true).required();
    private final NodeVariable<String> sideInput = withDefault(
            new NodeVariable<>("Side", String.class, true).required(), OrderSide.BUY.wireValue())
                    .describedAs("Valid values are buy or sell.");
    private final NodeVariable<Double> quantityInput = new NodeVariable<>("Quantity", Double.class, true)
            .describedAs("Shares to trade, which may be fractional. Mutually exclusive with Amount "
                    + "($) - filling in both fails the node rather than guessing which was meant.");
    private final NodeVariable<Double> amountInput = new NodeVariable<>("Amount ($)", Double.class, true)
            .describedAs("Dollars to spend, worked out to a share count by Alpaca at execution. "
                    + "Mutually exclusive with Quantity, and only accepted on a market order good for "
                    + "the day.");
    private final NodeVariable<String> orderTypeInput = withDefault(
            new NodeVariable<>("Order Type", String.class, true), OrderType.MARKET.label())
                    .describedAs("One of market, limit, stop, stop_limit or trailing_stop. market "
                            + "needs nothing else; limit needs Limit Price; stop needs Stop Price; "
                            + "stop_limit needs both; trailing_stop needs Trail Price or Trail "
                            + "Percent.");
    private final NodeVariable<Double> limitPriceInput =
            new NodeVariable<>("Limit Price", Double.class, true)
                    .describedAs("Used by limit and stop_limit orders: the order fills at this price "
                            + "or better, or not at all.");
    private final NodeVariable<Double> stopPriceInput =
            new NodeVariable<>("Stop Price", Double.class, true)
                    .describedAs("Used by stop and stop_limit orders: the trigger price at which the "
                            + "order activates.");
    private final NodeVariable<Double> trailPriceInput =
            new NodeVariable<>("Trail Price", Double.class, true)
                    .describedAs("trailing_stop orders only: the dollar distance the stop follows "
                            + "behind the best price seen. An alternative to Trail Percent - set one "
                            + "or the other.");
    private final NodeVariable<Double> trailPercentInput =
            new NodeVariable<>("Trail Percent", Double.class, true)
                    .describedAs("trailing_stop orders only: the trail as a percent from 0 to 100, "
                            + "not a fraction. An alternative to Trail Price - set one or the other.");
    private final NodeVariable<String> timeInForceInput = withDefault(
            new NodeVariable<>("Time In Force", String.class, true), TimeInForce.DAY.wireValue())
                    .describedAs("How long the order stays working: day (cancelled at the close, the "
                            + "default), gtc (good till cancelled), opg (at the open), cls (at the "
                            + "close), ioc (immediate or cancel) or fok (fill or kill).");
    private final NodeVariable<Boolean> extendedHoursInput =
            withDefault(new NodeVariable<>("Extended Hours", Boolean.class, true), Boolean.FALSE)
                    .describedAs("Only takes effect on a limit order good for the day - silently "
                            + "ignored on any other Order Type or Time In Force.");
    private final NodeVariable<Double> maxOrderValueInput =
            new NodeVariable<>("Max Order Value ($)", Double.class, true)
                    .describedAs("A ceiling on this order's estimated value. Blank means no ceiling. "
                            + "An order estimated to be worth more than this fails the node rather "
                            + "than being let through.");
    private final NodeVariable<Boolean> dryRunInput =
            withDefault(new NodeVariable<>("Dry Run", Boolean.class, true), Boolean.TRUE)
                    .describedAs("Defaults to true: nothing is sent to Alpaca until this is explicitly "
                            + "turned off, on either a paper or a live account. While true, the node "
                            + "works out and reports what it would have done and fires Not Placed "
                            + "instead of trading.");

    private final NodeVariable<String> orderIdOutput = new NodeVariable<>("Order ID", String.class)
            .describedAs("Null during a dry run, or before an order has actually been placed.");
    private final NodeVariable<String> stateOutput = new NodeVariable<>("State", String.class)
            .describedAs("The order's raw status once placed. During a dry run this reads the literal "
                    + "string \"dry run\" instead.");
    private final NodeVariable<String> symbolOutput = new NodeVariable<>("Symbol", String.class);
    private final NodeVariable<Double> estimatedValueOutput =
            new NodeVariable<>("Estimated Value", Double.class)
                    .describedAs("The dollar basis this order was checked against Max Order Value ($) "
                            + "with. Populated even on a dry run.");
    private final NodeVariable<Boolean> wasPlacedOutput = new NodeVariable<>("Was Placed", Boolean.class);
    private final NodeVariable<Boolean> isPaperOutput = new NodeVariable<>("Is Paper", Boolean.class);
    private final NodeVariable<String> summaryOutput = new NodeVariable<>("Summary", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort placed = new FlowPort("Placed", FlowPort.Direction.OUT);
    private final FlowPort notPlaced = new FlowPort("Not Placed", FlowPort.Direction.OUT);

    private Label statusLabel;

    public PlaceOrderNode() {
        // One order at a time from one node. Two runs of the same Place Order node overlapping is
        // the shape of "the trigger fired twice and bought twice"; the engine queues the second run
        // on this node's permit instead.
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
            AlpacaSession session = Inputs.session(accountInput);
            OrderRequest request = authoredOrder();
            isPaperOutput.setValue(session.isPaper());

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
            stateOutput.setValue(order.displayState());
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
                Inputs.decimal(trailPriceInput),
                Inputs.decimal(trailPercentInput),
                TimeInForce.parse(timeInForceInput.getValue()),
                Boolean.TRUE.equals(extendedHoursInput.getValue())).validate();
    }

    /**
     * Publishes what the order came to, before it is sent — so a dry run, a refusal by the ceiling
     * and a real placement all leave the same numbers behind to look at.
     */
    private void publishPreparation(PreparedOrder prepared) {
        symbolOutput.setValue(prepared.request().symbol());
        estimatedValueOutput.setValue(prepared.estimatedValue());
        orderIdOutput.setValue(null);
        wasPlacedOutput.setValue(false);
    }

    /**
     * Fails the node if the order is worth more than Max Order Value ($), or if that cannot be
     * established.
     *
     * @throws AlpacaException when the order is over the ceiling, or cannot be valued
     */
    private void checkCeiling(PreparedOrder prepared) {
        BigDecimal ceiling = Inputs.decimal(maxOrderValueInput);
        if (ceiling == null) {
            return;
        }
        if (ceiling.signum() <= 0) {
            throw new AlpacaException("Max Order Value ($) must be greater than zero - got "
                    + ceiling.toPlainString() + ". Leave it empty for no ceiling.");
        }
        Double value = prepared.estimatedValue();
        if (value == null) {
            throw new AlpacaException("Max Order Value ($) is set, but no price could be read for "
                    + prepared.request().symbol() + ", so this order cannot be valued and the "
                    + "ceiling cannot be checked. Nothing was placed.");
        }
        if (BigDecimal.valueOf(value).compareTo(ceiling) > 0) {
            throw new AlpacaException(String.format(
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
        addInput(trailPriceInput);
        addInput(trailPercentInput);
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
        addOutput(estimatedValueOutput);
        addOutput(wasPlacedOutput);
        addOutput(isPaperOutput);
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
        if (summary == null) {
            Status.set(statusLabel, "Not run yet");
            return;
        }
        boolean live = Boolean.FALSE.equals(isPaperOutput.getValue())
                && Boolean.TRUE.equals(wasPlacedOutput.getValue());
        Status.set(statusLabel, live ? "LIVE: " + summary : summary);
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
