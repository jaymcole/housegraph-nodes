package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodCredentials;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The Robinhood login every other node in this library works through. Fill in the credentials,
 * press <b>Connect</b> (or wire something into the Connect port), and hand the <b>Account</b>
 * output to a Get Quote, Place Order or Account Summary node.
 *
 * <h2>Read this before wiring it to anything that trades</h2>
 * <b>Robinhood publishes no API for this.</b> Everything this library does, it does through the
 * private interface Robinhood's own apps use — the same one the open-source Python clients have
 * tracked for years. Three consequences, none of them hypothetical:
 * <ul>
 *   <li><b>It can stop working overnight</b>, with no warning and no deprecation period, because
 *       nothing here is a contract Robinhood has made.</li>
 *   <li><b>It is very likely against Robinhood's terms of service.</b> Whether that matters — and
 *       whether an account can be restricted for it — is between the account holder and their
 *       broker, and installing this library is taking that on.</li>
 *   <li><b>A bug here costs real money.</b> A graph that places market orders in a loop does
 *       exactly that. Which is why Place Order ships with <b>Dry Run switched on</b>, and why a
 *       Max Order Value ($) exists on it.</li>
 * </ul>
 * The reasoning, and what to check when it does break, is in
 * {@code docs/design/robinhood-unofficial-api.md}.
 *
 * <h2>Two-factor authentication</h2>
 * Robinhood will want a second factor, and which one decides whether an unattended graph can log
 * in at all:
 * <ul>
 *   <li><b>An authenticator app.</b> Put the base32 seed Robinhood showed when you set it up into
 *       <b>MFA Secret</b> and this node generates the codes itself. This is the only setup a graph
 *       can reconnect under with nobody watching.</li>
 *   <li><b>Approval in the Robinhood app.</b> Leave MFA Secret empty and the node starts the "is
 *       this you?" prompt and waits up to <b>Approval Timeout (s)</b> for the tap.</li>
 *   <li><b>A code by SMS or email.</b> A graph cannot read a text message, so this fails with a
 *       message saying to switch to an authenticator app.</li>
 * </ul>
 * <b>Holding the MFA seed next to the password collapses two factors into one</b>, and this node
 * does not pretend otherwise. It is worth it for a machine that trades on its own; it is not worth
 * it for one a person drives by hand, and MFA Secret is optional for exactly that reason.
 *
 * <h2>Wire the credentials from Secret Loader nodes</h2>
 * <b>Username, Password and MFA Secret are meant to come from HouseGraph's built-in Secret Loader
 * node</b> — one per field, each pointing at a key in the encrypted secret store. The Secret Loader
 * saves the <em>key</em> and resolves the value fresh on every run, so a reloaded graph connects
 * with nothing typed in and nothing sensitive in the file.
 * <p>
 * Typing straight into the fields works too, and is the quicker way to try something out. The
 * difference is what survives: <b>all three are secret inputs, so HouseGraph never writes their
 * values to a save file</b> — a typed password is gone when the graph is reloaded, where a wired
 * one comes back. Nothing in this library writes a credential or a token to disk either. The only
 * thing that persists a credential anywhere is the host's secret store, which is encrypted at rest.
 * <p>
 * This node does <b>not</b> reconnect by itself on load, unlike the Discord Bot and Local LLM
 * Server nodes it otherwise resembles. With Secret Loaders wired it could — the credentials would
 * be there — which makes this a choice rather than a limitation: a brokerage session
 * re-establishing itself the moment a file is opened is not a thing to do quietly. Wire a startup
 * trigger into <b>Connect</b> for a graph that should log itself in.
 *
 * <h2>The ports</h2>
 * <b>Connect</b> logs in; connecting an already-connected account logs in again, which is what a
 * changed password needs. <b>Disconnect</b> hands the token back and forgets it. <b>Connected</b>
 * fires after a successful login and <b>Disconnected</b> after a disconnect, so exactly one of the
 * two reports what happened. A failed login fires neither — it fails the node, and the reason lands
 * on the status line and in the log.
 * <p>
 * <b>Account Name</b> publishes the session under that name, which is what
 * {@link RobinhoodAccountRefNode} looks up. Two accounts in one graph means two names.
 *
 * <h2>This node owns a connection, which is why it has Start/Stop at all</h2>
 * The library's other nodes are plain actions with a flow-in. This one holds a login lifecycle, so
 * Connect/Disconnect and the state behind them belong to it — the named exception in
 * {@code docs/shared/node-library-rules.md}, the same one the Discord Bot and Database nodes are.
 * <b>Nothing here is on a timer</b>: when to quote or trade is a trigger's business, wired into the
 * action nodes.
 */
@Display.Name("Robinhood Account")
@Display.Description("Logs in to Robinhood and holds the session every other Robinhood node uses.")
@Node.Kind(NodeKind.RESOURCE)
@Node.Keywords({"robinhood", "broker", "brokerage", "trading", "stocks", "shares", "account",
        "login", "connect", "session", "portfolio", "investing", "finance"})
@Node.Type("robinhood.RobinhoodAccountNode")
public class RobinhoodAccountNode extends BaseNode implements NodeContentProvider {

    static final String DEFAULT_NAME = "robinhood";
    static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 120;

    /**
     * This node's session for its whole life, like the Discord Bot node's handle. Nodes downstream
     * capture it when the wire appears, so swapping it at Connect would leave them holding one that
     * never logs in.
     */
    private final RobinhoodSession session;

    /** The name the session is currently published under, or null if it isn't. */
    private String registeredName;

    private final NodeVariable<String> nameInput =
            withDefault(new NodeVariable<>("Account Name", String.class, true), DEFAULT_NAME);
    /**
     * Secret, although a username is not much of one, because of where the value would otherwise
     * end up. A save file records a manually-editable input's <em>current</em> value, and it cannot
     * tell one somebody typed from one an edge resolved a moment ago — {@code isPersistentValue} is
     * a flag set at construction, not a fact about where the value came from. So leaving this port
     * unmarked would write the username a Secret Loader had just fetched straight into the graph
     * file, which is the one thing fetching it from the store was meant to avoid.
     */
    private final NodeVariable<String> usernameInput =
            new NodeVariable<>("Username", String.class, true).required().markSecret();
    private final NodeVariable<String> passwordInput =
            new NodeVariable<>("Password", String.class, true).required().markSecret();
    private final NodeVariable<String> mfaSecretInput =
            new NodeVariable<>("MFA Secret", String.class, true).markSecret();
    private final NodeVariable<Integer> approvalTimeoutInput =
            withDefault(new NodeVariable<>("Approval Timeout (s)", Integer.class, true),
                    DEFAULT_APPROVAL_TIMEOUT_SECONDS);

    private final NodeVariable<RobinhoodSession> accountOutput =
            new NodeVariable<>("Account", RobinhoodSession.class).transientValue();
    private final NodeVariable<String> accountNumberOutput =
            new NodeVariable<>("Account Number", String.class);
    private final NodeVariable<Boolean> connectedOutput =
            new NodeVariable<>("Is Connected", Boolean.class);

    private final FlowPort connectIn = new FlowPort("Connect", FlowPort.Direction.IN);
    private final FlowPort disconnectIn = new FlowPort("Disconnect", FlowPort.Direction.IN);
    private final FlowPort connectedOut = new FlowPort("Connected", FlowPort.Direction.OUT);
    private final FlowPort disconnectedOut = new FlowPort("Disconnected", FlowPort.Direction.OUT);

    private Button connectButton;
    private Button disconnectButton;
    private Label statusLabel;

    public RobinhoodAccountNode() {
        this(new RobinhoodSession());
    }

    /**
     * The seam the tests use to put a stub in Robinhood's place. Package-private: the host builds
     * nodes through the no-arg constructor, and which server a brokerage node talks to is not a
     * thing a graph should be able to get wrong.
     *
     * @param session the session this node owns for its whole life
     */
    RobinhoodAccountNode(RobinhoodSession session) {
        this.session = session;
        // Seeded here rather than at the field, which runs before this constructor does. Downstream
        // nodes read the handle off this output the moment a wire appears, so it cannot start null.
        accountOutput.setValue(session);
        // Logging in twice at once would have two password grants racing for one device approval.
        // The engine queues the second run on this node's permit instead.
        setMaxConcurrency(1);
    }

    /**
     * Connects or disconnects, depending on which flow-in brought control here. A
     * {@link #beginProcessing()} pull — the Connect button, or a downstream node resolving the
     * Account output — takes the connect branch, so pulling the session does not require the graph
     * to have been wired for it.
     * <p>
     * A failure throws, and the port that would have announced success is never activated, so
     * nothing downstream is told about a login that did not happen.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            // Re-assert the handle first: a load writes the saved null over this transient output, and
            // every downstream pull would read null forever otherwise. Same fix, same reason, as the
            // Discord Bot node's.
            accountOutput.setValue(session);
            publishByName();

            if (ctx.wasTriggeredVia(disconnectIn)) {
                session.disconnect();
                publish();
                activate(disconnectedOut);
                return;
            }

            // The last cheap moment to notice a cancelled run: what follows is a login that may sit
            // waiting on a phone for two minutes.
            ctx.checkCancelled();
            session.connect(credentials(), approvalTimeout());
            publish();
            activate(connectedOut);
        } catch (RuntimeException failure) {
            // See GetQuoteNode.process: nothing fires on a failure, and saying so has to come last.
            activateNone();
            throw failure;
        }
    }

    /** The credentials as the ports currently read. Built per run, never held. */
    private RobinhoodCredentials credentials() {
        return new RobinhoodCredentials(
                usernameInput.getValue(),
                passwordInput.getValue(),
                mfaSecretInput.getValue());
    }

    private int approvalTimeout() {
        Integer seconds = approvalTimeoutInput.getValue();
        return seconds == null ? DEFAULT_APPROVAL_TIMEOUT_SECONDS : seconds;
    }

    /** Copies what the session knows onto the data outputs. */
    private void publish() {
        accountOutput.setValue(session);
        accountNumberOutput.setValue(session.accountNumber());
        connectedOutput.setValue(session.isConnected());
    }

    /** Re-publishes the session under the current Account Name, moving it if the name changed. */
    private void publishByName() {
        String name = authoredName();
        if (name.equals(registeredName)) {
            return;
        }
        withdrawByName();
        registeredName = name;
        ResourceRegistry.shared().register(name, session);
    }

    private void withdrawByName() {
        if (registeredName != null) {
            ResourceRegistry.shared().unregister(registeredName);
            registeredName = null;
        }
    }

    private String authoredName() {
        String typed = nameInput.getValue();
        return (typed == null || typed.isBlank()) ? DEFAULT_NAME : typed.trim();
    }

    @Override
    public void configureInputs() {
        addInput(nameInput);
        addInput(usernameInput);
        addInput(passwordInput);
        addInput(mfaSecretInput);
        addInput(approvalTimeoutInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(accountOutput);
        addOutput(accountNumberOutput);
        addOutput(connectedOutput);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(connectIn);
        addFlowInput(disconnectIn);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(connectedOut);
        addFlowOutput(disconnectedOut);
    }

    /** Publishes the session by name as soon as the node joins the graph, before anything runs. */
    @Override
    protected void onActivated() {
        accountOutput.setValue(session);
        publishByName();
    }

    /** The fast half of teardown: stop anything being able to look this session up. */
    @Override
    protected void onRemoved() {
        withdrawByName();
    }

    /**
     * The slow half: hand the token back to Robinhood. It is a network call, so it cannot sit in
     * {@link #onRemoved()}, which runs unbounded on the removing thread.
     */
    @Override
    protected void releaseResources() {
        session.disconnect();
    }

    @Override
    protected void onExecuted() {
        refreshStatus();
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        connectButton = new Button("Connect");
        connectButton.setMaxWidth(Double.MAX_VALUE);
        connectButton.setOnAction(event -> runInBackground("connect"));

        disconnectButton = new Button("Disconnect");
        disconnectButton.setMaxWidth(Double.MAX_VALUE);
        disconnectButton.setOnAction(event -> disconnectInBackground());

        statusLabel = Status.label(session.statusText());
        refreshStatus();
        return new VBox(4, new HBox(6, connectButton, disconnectButton), statusLabel);
    }

    /**
     * Says what the session is doing, or why it isn't. Idempotent and safe to call from any thread:
     * every path recomputes the same text from the session and {@link #getLastError()}, and a node
     * that was never drawn has no label to update.
     */
    private void refreshStatus() {
        if (statusLabel == null) {
            return;
        }
        Throwable error = getLastError();
        Status.set(statusLabel, error != null ? "Failed - " + error.getMessage() : session.statusText());
        boolean connected = session.isConnected();
        Runnable buttons = () -> {
            connectButton.setDisable(false);
            disconnectButton.setDisable(!connected);
        };
        if (Platform.isFxApplicationThread()) {
            buttons.run();
        } else {
            Platform.runLater(buttons);
        }
    }

    /**
     * Runs {@link #process} off the FX thread — a login takes a round trip at best and a two-minute
     * wait on a phone at worst, and doing it inline would freeze the canvas for the duration.
     * A plain pull, so {@code triggeredVia()} reads empty and process() takes its connect branch.
     */
    private void runInBackground(String what) {
        if (statusLabel != null) {
            connectButton.setDisable(true);
            statusLabel.setText("Connecting…");
        }
        Thread thread = new Thread(this::beginProcessing, "robinhood-" + what + "-" + authoredName());
        thread.setDaemon(true);
        thread.start();
    }

    /** Disconnects off the FX thread: revoking a token is a network call too. */
    private void disconnectInBackground() {
        disconnectButton.setDisable(true);
        statusLabel.setText("Disconnecting…");
        Thread thread = new Thread(() -> {
            session.disconnect();
            publish();
            refreshStatus();
        }, "robinhood-disconnect-" + authoredName());
        thread.setDaemon(true);
        thread.start();
    }

    /** Test seam: the session this node owns. */
    RobinhoodSession session() {
        return session;
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
