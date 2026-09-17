package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.AccountSummary;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * What the account is worth and what it can spend — the numbers a person opens the app to see.
 * <p>
 * <b>Buying Power and Cash are not the same thing</b>, and a graph that decides whether it can
 * afford something should read Buying Power: it is what an order may actually spend, margin
 * included, while Cash is what is sitting there. <b>Equity</b> is cash plus everything held.
 * <p>
 * <b>Day Change</b> is equity against yesterday's close — positive is up. It is the number to
 * branch on for "tell me if the portfolio drops 2% today", with the deciding done by a branch node
 * downstream and the telling by whatever posts the message.
 * <p>
 * Reading this costs two calls to Robinhood, so put it behind a trigger with an interval rather
 * than in a tight loop; an account that asks too often gets rate-limited, and the node will say so.
 */
@Display.Name("Account Summary")
@Display.Description("Reads a Robinhood account's buying power, cash, equity and day change.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"robinhood", "account", "balance", "buying power", "cash", "equity", "portfolio",
        "value", "worth", "summary", "money", "finance"})
@Node.Type("robinhood.AccountSummaryNode")
public class AccountSummaryNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<RobinhoodSession> accountInput =
            new NodeVariable<>("Account", RobinhoodSession.class, true).required().transientValue()
                    .describedAs("Wire this from a Robinhood Account node's Account output, or a "
                            + "Robinhood Account Ref node pointing at one.");

    private final NodeVariable<String> accountNumberOutput =
            new NodeVariable<>("Account Number", String.class);
    private final NodeVariable<Double> buyingPowerOutput = new NodeVariable<>("Buying Power", Double.class)
            .describedAs("What an order may actually spend, margin included — distinct from Cash, "
                    + "which is only what is sitting there.");
    private final NodeVariable<Double> cashOutput = new NodeVariable<>("Cash", Double.class)
            .describedAs("Cash sitting in the account, no margin included — distinct from Buying Power, "
                    + "which is what an order may actually spend.");
    private final NodeVariable<Double> equityOutput = new NodeVariable<>("Equity", Double.class)
            .describedAs("Cash plus everything held, at current value.");
    private final NodeVariable<Double> marketValueOutput = new NodeVariable<>("Market Value", Double.class)
            .describedAs("The value of what is held, not counting cash — distinct from Equity, which "
                    + "includes it.");
    private final NodeVariable<Double> dayChangeOutput = new NodeVariable<>("Day Change", Double.class)
            .describedAs("Dollars, not a percent. Positive means the account is up today.");

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;

    /**
     * Reads the account and publishes it. See {@code GetQuoteNode.process} for why the failure path
     * says "nothing fires" rather than leaving it to the engine's default.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            RobinhoodSession session = Inputs.session(accountInput);

            ctx.checkCancelled();
            AccountSummary summary = session.accountSummary();

            accountNumberOutput.setValue(summary.accountNumber());
            buyingPowerOutput.setValue(summary.buyingPower());
            cashOutput.setValue(summary.cash());
            equityOutput.setValue(summary.equity());
            marketValueOutput.setValue(summary.marketValue());
            dayChangeOutput.setValue(summary.dayChange());
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
        addOutput(accountNumberOutput);
        addOutput(buyingPowerOutput);
        addOutput(cashOutput);
        addOutput(equityOutput);
        addOutput(marketValueOutput);
        addOutput(dayChangeOutput);
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
        Double equity = equityOutput.getValue();
        Double change = dayChangeOutput.getValue();
        Status.set(statusLabel, equity == null
                ? "Read, but Robinhood reported no equity"
                : String.format("Equity $%,.2f%s", equity,
                        change == null ? "" : String.format(" (%+,.2f today)", change)));
    }
}
