package io.github.jaymcole.housegraph.plugins.alpaca.nodes.portfolio;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AccountSummary;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Inputs;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.Status;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * What the account is worth and what it can spend.
 * <p>
 * <b>Equity</b> is cash plus everything held; <b>Buying Power</b> is what an order can actually
 * spend right now, which is a larger number on a margin account and a smaller one while a deposit
 * is still settling. <b>Day Change</b> is equity against the previous close.
 *
 * <h2>Can Trade is the one to branch on</h2>
 * An account can be live, funded, and still refuse every order — a transfer under review, a
 * restriction after a day-trading breach, an account its holder suspended. <b>Can Trade</b> says so
 * before an order is written rather than after Alpaca has refused one, which is a much better place
 * for a graph to find out. Wire it into a branch in front of anything that places orders, next to a
 * Market Clock check.
 * <p>
 * <b>It reads; it never trades.</b> Wire it to a repeating trigger to watch a balance — an alert
 * when equity drops through a number is this node, a comparison, and a Discord message.
 */
@Display.Name("Account Summary")
@Display.Description("Reads an Alpaca account's equity, cash and buying power.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"alpaca", "account", "balance", "equity", "cash", "buying power", "portfolio",
        "value", "summary", "money", "finance"})
@Node.Type("alpaca.AccountSummaryNode")
public class AccountSummaryNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<AlpacaSession> accountInput =
            new NodeVariable<>("Account", AlpacaSession.class, true).required().transientValue();

    private final NodeVariable<String> accountNumberOutput =
            new NodeVariable<>("Account Number", String.class);
    private final NodeVariable<Double> equityOutput = new NodeVariable<>("Equity", Double.class)
            .describedAs("Cash plus everything held, at current market value.");
    private final NodeVariable<Double> cashOutput = new NodeVariable<>("Cash", Double.class);
    private final NodeVariable<Double> buyingPowerOutput =
            new NodeVariable<>("Buying Power", Double.class)
                    .describedAs("What an order can actually spend right now - larger than Cash on a "
                            + "margin account, smaller than it while a deposit is still settling.");
    private final NodeVariable<Double> marketValueOutput =
            new NodeVariable<>("Market Value", Double.class)
                    .describedAs("The value of what's held only, excluding cash - distinct from "
                            + "Equity, which includes it.");
    private final NodeVariable<Double> dayChangeOutput = new NodeVariable<>("Day Change", Double.class)
            .describedAs("Dollar change in equity versus its previous close.");
    private final NodeVariable<Double> dayChangePercentOutput =
            new NodeVariable<>("Day Change %", Double.class)
                    .describedAs("A fraction, not a whole percent - the same ambiguity as Get Quote's "
                            + "Change %: 0.012 means up 1.2%.");
    private final NodeVariable<String> statusOutput = new NodeVariable<>("Status", String.class)
            .describedAs("Alpaca's raw account-status string, distinct from Can Trade: an account can "
                    + "report ACTIVE here and still refuse an order.");
    private final NodeVariable<Boolean> canTradeOutput = new NodeVariable<>("Can Trade", Boolean.class);
    private final NodeVariable<Boolean> isPaperOutput = new NodeVariable<>("Is Paper", Boolean.class);

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
            AlpacaSession session = Inputs.session(accountInput);

            ctx.checkCancelled();
            AccountSummary summary = session.accountSummary();

            accountNumberOutput.setValue(summary.accountNumber());
            equityOutput.setValue(summary.equity());
            cashOutput.setValue(summary.cash());
            buyingPowerOutput.setValue(summary.buyingPower());
            marketValueOutput.setValue(summary.longMarketValue());
            dayChangeOutput.setValue(summary.dayChange());
            dayChangePercentOutput.setValue(summary.dayChangePercent());
            statusOutput.setValue(summary.status());
            canTradeOutput.setValue(summary.canTrade());
            isPaperOutput.setValue(session.isPaper());
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
        addOutput(equityOutput);
        addOutput(cashOutput);
        addOutput(buyingPowerOutput);
        addOutput(marketValueOutput);
        addOutput(dayChangeOutput);
        addOutput(dayChangePercentOutput);
        addOutput(statusOutput);
        addOutput(canTradeOutput);
        addOutput(isPaperOutput);
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
        if (equity == null) {
            Status.set(statusLabel, "Not run yet");
            return;
        }
        Double change = dayChangeOutput.getValue();
        Status.set(statusLabel, String.format("$%,.2f", equity)
                + (change == null ? "" : String.format(" (%+,.2f today)", change))
                + (Boolean.FALSE.equals(canTradeOutput.getValue()) ? " - trading blocked" : ""));
    }
}
