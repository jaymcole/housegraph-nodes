package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * A second place to plug into the account you already have. It looks up an {@link AlpacaAccountNode}'s
 * session by name and hands it out on its own <b>Account</b> output, so a cluster of Alpaca nodes on
 * the far side of the canvas can wire to something next to them instead of dragging a wire back
 * across the graph.
 * <p>
 * <b>This is the node to reach for when one Alpaca Account node's wiring gets messy.</b> A second
 * Alpaca Account node holding the same key pair looks like the answer and isn't: it is a second
 * place the keys are configured, a second thing to update when they are rotated, and a second
 * status line that can disagree with the first about whether the account is connected. This node
 * adds none of that. It owns nothing, connects nothing, and holds nothing open; it is a label
 * pointing at the one account.
 * <p>
 * <b>Account Name</b> matches the Account Name of the Alpaca Account node to point at, which
 * publishes itself under that name when it joins the graph. Resolution is by name and repeated on
 * every read, so load order doesn't matter and renaming either end takes effect immediately.
 * <p>
 * <b>Is Paper</b> is worth putting on a display somewhere in a graph that has both a paper account
 * and a live one: this node's whole job is to be wired to from a distance, and "which account is
 * this actually?" is the question distance makes hard to answer.
 */
@Display.Name("Alpaca Account Ref")
@Display.Description("Points at an Alpaca Account node by name, without a second copy of the keys.")
@Node.Kind(NodeKind.RESOURCE)
@Node.Keywords({"alpaca", "account", "reference", "ref", "lookup", "session", "broker", "shared"})
@Node.Type("alpaca.AlpacaAccountRefNode")
public class AlpacaAccountRefNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> nameInput = withDefault(
            new NodeVariable<>("Account Name", String.class, true), AlpacaAccountNode.DEFAULT_NAME)
                    .describedAs("Must match the Account Name of an Alpaca Account node elsewhere in "
                            + "this graph.");

    /**
     * Transient, like the account node's own Account output: a live connection is not something a
     * save file can carry. It is re-resolved rather than restored.
     */
    private final NodeVariable<AlpacaSession> accountOutput =
            new NodeVariable<>("Account", AlpacaSession.class).transientValue()
                    .describedAs("Null when Account Name resolves to nothing - this node's one, "
                            + "silent failure mode.");
    private final NodeVariable<Boolean> paperOutput = new NodeVariable<>("Is Paper", Boolean.class);

    private Label statusLabel;

    /** Publishes whatever Account Name currently points at; null if nothing answers to it. */
    @Override
    public void process(ProcessContext ctx) {
        AlpacaSession session = resolve();
        accountOutput.setValue(session);
        paperOutput.setValue(session == null ? null : session.isPaper());
    }

    /**
     * The session published under this node's Account Name right now, or null if no Alpaca Account
     * node in this graph carries that name. Resolved fresh on every call: the name is an ordinary
     * input, and the node it points at may be added, renamed or removed at any point.
     *
     * @return the session, or null
     */
    AlpacaSession resolve() {
        return ResourceRegistry.shared().find(currentName(), AlpacaSession.class).orElse(null);
    }

    @Override
    protected void onActivated() {
        // A convenience only. Whether this finds anything depends on where the account node sits in
        // the load's node order, which is why nothing depends on it: every later read runs process()
        // again.
        accountOutput.setValue(resolve());
    }

    @Override
    public void configureInputs() {
        addInput(nameInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(accountOutput);
        addOutput(paperOutput);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        statusLabel = Status.label("");
        refreshStatus();
        return statusLabel;
    }

    /**
     * Says what the name currently resolves to. Worth showing: a name with no account behind it is
     * this node's one failure mode, it is silent everywhere else, and a typo is easy to make.
     */
    @Override
    protected void onExecuted() {
        refreshStatus();
    }

    private void refreshStatus() {
        AlpacaSession session = resolve();
        Status.set(statusLabel, session == null
                ? "No account named \"" + currentName() + "\""
                : session.statusText());
    }

    private String currentName() {
        String name = nameInput.getValue();
        return (name == null || name.isBlank()) ? AlpacaAccountNode.DEFAULT_NAME : name.trim();
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
