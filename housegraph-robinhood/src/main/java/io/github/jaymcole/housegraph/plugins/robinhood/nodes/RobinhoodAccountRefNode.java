package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;

/**
 * A second place to plug into the account you already have — without a second login. It looks up a
 * {@link RobinhoodAccountNode}'s session by name and hands it out on its own <b>Account</b> output,
 * so a cluster of Robinhood nodes on the far side of the canvas can wire to something next to them
 * instead of dragging a wire back across the graph.
 * <p>
 * <b>This is the node to reach for when one Robinhood Account node's wiring gets messy.</b> A second
 * Robinhood Account node on the same credentials looks like the answer and isn't: it is a second
 * password grant, a second device approval, and two sessions taking turns to invalidate each
 * other's tokens. This node adds no login at all. It owns nothing, starts nothing, and holds
 * nothing open; it is a label pointing at the one account.
 * <p>
 * <b>Account Name</b> matches the Account Name of the Robinhood Account node to point at, which
 * publishes itself under that name when it joins the graph. Resolution is by name and repeated on
 * every read, so load order doesn't matter and renaming either end takes effect immediately.
 */
@Display.Name("Robinhood Account Ref")
@Display.Description("Points at a Robinhood Account node by name, without opening a second login.")
@Node.Kind(NodeKind.RESOURCE)
@Node.Keywords({"robinhood", "account", "reference", "ref", "lookup", "session", "broker", "shared"})
@Node.Type("robinhood.RobinhoodAccountRefNode")
public class RobinhoodAccountRefNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> nameInput = withDefault(
            new NodeVariable<>("Account Name", String.class, true), RobinhoodAccountNode.DEFAULT_NAME);

    /**
     * Transient, like the account node's own Account output: a live login is not something a save
     * file can carry. It is re-resolved rather than restored.
     */
    private final NodeVariable<RobinhoodSession> accountOutput =
            new NodeVariable<>("Account", RobinhoodSession.class).transientValue();

    private Label statusLabel;

    /** Publishes whatever Account Name currently points at; null if nothing answers to it. */
    @Override
    public void process(ProcessContext ctx) {
        accountOutput.setValue(resolve());
    }

    /**
     * The session published under this node's Account Name right now, or null if no Robinhood
     * Account node in this graph carries that name. Resolved fresh on every call: the name is an
     * ordinary input, and the node it points at may be added, renamed or removed at any point.
     *
     * @return the session, or null
     */
    RobinhoodSession resolve() {
        return ResourceRegistry.shared().find(currentName(), RobinhoodSession.class).orElse(null);
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
        RobinhoodSession session = resolve();
        Status.set(statusLabel, session == null
                ? "No account named \"" + currentName() + "\""
                : session.statusText());
    }

    private String currentName() {
        String name = nameInput.getValue();
        return (name == null || name.isBlank()) ? RobinhoodAccountNode.DEFAULT_NAME : name.trim();
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
