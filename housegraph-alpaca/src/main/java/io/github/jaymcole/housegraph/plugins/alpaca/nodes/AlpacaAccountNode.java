package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaCredentials;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The Alpaca account every other node in this library works through. Paste in an API key pair,
 * press <b>Connect</b> (or wire something into the Connect port), and hand the <b>Account</b> output
 * to a Get Quote, Place Order or Account Summary node.
 *
 * <h2>Paper Trading is on, and that is the safety story</h2>
 * A freshly dropped node is pointed at Alpaca's <b>paper</b> account: real prices, real market
 * mechanics, fake money. Turning it off points the same node at the live brokerage account, where
 * orders spend real money.
 * <p>
 * <b>The two accounts have different API keys, which is what makes this more than a checkbox.</b>
 * Alpaca issues one key pair for paper and another for live, and each only works against its own
 * host. So a graph built and tested with paper keys cannot start trading real money because
 * somebody flipped this switch — it stops connecting instead, and says so. Going live is a
 * deliberate act: new keys, from a different page of Alpaca's dashboard, pasted in on purpose.
 * <p>
 * The corollary is worth stating too: <b>a live graph is one input away from spending real money,
 * and nothing in this library can tell whether that graph is finished.</b> Place Order's Dry Run
 * and Max Order Value ($) are the second line of defence, and they are on that node rather than
 * this one because they are about a particular order rather than about the account.
 *
 * <h2>Wire the keys from Secret Loader nodes</h2>
 * <b>API Key ID and API Secret Key are meant to come from HouseGraph's built-in Secret Loader
 * node</b> — one per field, each pointing at a key in the encrypted secret store. The Secret Loader
 * saves the <em>key name</em> and resolves the value fresh on every run, so a reloaded graph
 * connects with nothing typed in and nothing sensitive in the file.
 * <p>
 * Typing straight into the fields works too, and is the quicker way to try something out. The
 * difference is what survives: <b>both are secret inputs, so HouseGraph never writes their values
 * to a save file</b> — typed keys are gone when the graph is reloaded, where wired ones come back.
 * Nothing in this library writes a key to disk or repeats one in a log line. The only thing that
 * persists a credential anywhere is the host's secret store, which is encrypted at rest.
 * <p>
 * This node does <b>not</b> reconnect by itself on load, unlike the Discord Bot and Local LLM Server
 * nodes it otherwise resembles. With Secret Loaders wired it could — which makes this a choice
 * rather than a limitation, and the same one the Robinhood library makes: a brokerage session
 * re-establishing itself the moment a file is opened is not a thing to do quietly. Wire a startup
 * trigger into <b>Connect</b> for a graph that should connect itself.
 *
 * <h2>What Connect actually does</h2>
 * Alpaca authenticates every request with the key pair, so there is no login and no token — which
 * means Connect could have done nothing at all until the first real call. It reads
 * {@code /v2/account} instead, and that one call is what turns a mistyped key into a red status
 * line <em>now</em> rather than into a failed trade at 09:30 tomorrow. It also fills in <b>Account
 * Number</b> and tells the node whether the keys are for the account the Paper Trading switch says
 * they are.
 *
 * <h2>The ports</h2>
 * <b>Connect</b> checks the keys and holds them; connecting an already-connected account re-reads
 * them, which is what a rotated key needs. <b>Disconnect</b> forgets them. <b>Connected</b> fires
 * after a successful connect and <b>Disconnected</b> after a disconnect, so exactly one of the two
 * reports what happened. A failed connect fires neither — it fails the node, and the reason lands on
 * the status line and in the log.
 * <p>
 * <b>Account Name</b> publishes the session under that name, which is what
 * {@link AlpacaAccountRefNode} looks up. A paper account and a live account in one graph means two
 * names — and giving them names that say which is which is worth the ten seconds.
 *
 * <h2>This node owns the credentials, which is why it has Start/Stop at all</h2>
 * The library's other nodes are plain actions with a flow-in. This one holds a connection
 * lifecycle, so Connect/Disconnect and the state behind them belong to it — the named exception in
 * {@code docs/shared/node-library-rules.md}, the same one the Discord Bot and Database nodes are.
 * <b>Nothing here is on a timer</b>: when to quote or trade is a trigger's business, wired into the
 * action nodes.
 */
@Display.Name("Alpaca Account")
@Display.Description("Holds an Alpaca API key pair - paper or live - and the account every other Alpaca node uses.")
@Node.Kind(NodeKind.RESOURCE)
@Node.Keywords({"alpaca", "broker", "brokerage", "trading", "stocks", "shares", "account", "paper",
        "connect", "session", "portfolio", "investing", "finance", "api key"})
@Node.Type("alpaca.AlpacaAccountNode")
public class AlpacaAccountNode extends BaseNode implements NodeContentProvider {

    static final String DEFAULT_NAME = "alpaca";

    /**
     * This node's session for its whole life, like the Discord Bot node's handle. Nodes downstream
     * capture it when the wire appears, so swapping it at Connect would leave them holding one that
     * never connects.
     */
    private final AlpacaSession session;

    /** The name the session is currently published under, or null if it isn't. */
    private String registeredName;

    private final NodeVariable<String> nameInput =
            withDefault(new NodeVariable<>("Account Name", String.class, true), DEFAULT_NAME)
                    .describedAs("The name AlpacaAccountRefNode elsewhere in the graph matches by.");
    /**
     * Secret, although a key id is not much of one, because of where the value would otherwise end
     * up. A save file records a manually-editable input's <em>current</em> value, and it cannot tell
     * one somebody typed from one an edge resolved a moment ago — {@code isPersistentValue} is a
     * flag set at construction, not a fact about where the value came from. So leaving this port
     * unmarked would write the key a Secret Loader had just fetched straight into the graph file,
     * which is the one thing fetching it from the store was meant to avoid.
     */
    private final NodeVariable<String> apiKeyInput =
            new NodeVariable<>("API Key ID", String.class, true).required().markSecret()
                    .describedAs("Wire this from a Secret Loader node. Paper and live accounts use "
                            + "different key pairs, and a key issued for one does not work against "
                            + "the other.");
    private final NodeVariable<String> secretKeyInput =
            new NodeVariable<>("API Secret Key", String.class, true).required().markSecret()
                    .describedAs("Wired the same way as API Key ID, and paired with it - keep both "
                            + "from the same paper-or-live key set.");
    private final NodeVariable<Boolean> paperInput =
            withDefault(new NodeVariable<>("Paper Trading", Boolean.class, true), Boolean.TRUE)
                    .describedAs("The real-money safety switch. True connects to Alpaca's paper "
                            + "account - fake money, real prices. False connects to the live "
                            + "brokerage account, where orders spend real money. API Key ID and API "
                            + "Secret Key must be the pair issued for whichever side this is set to.");

    private final NodeVariable<AlpacaSession> accountOutput =
            new NodeVariable<>("Account", AlpacaSession.class).transientValue();
    private final NodeVariable<String> accountNumberOutput =
            new NodeVariable<>("Account Number", String.class);
    private final NodeVariable<Boolean> connectedOutput =
            new NodeVariable<>("Is Connected", Boolean.class);
    private final NodeVariable<Boolean> paperOutput = new NodeVariable<>("Is Paper", Boolean.class);

    private final FlowPort connectIn = new FlowPort("Connect", FlowPort.Direction.IN);
    private final FlowPort disconnectIn = new FlowPort("Disconnect", FlowPort.Direction.IN);
    private final FlowPort connectedOut = new FlowPort("Connected", FlowPort.Direction.OUT);
    private final FlowPort disconnectedOut = new FlowPort("Disconnected", FlowPort.Direction.OUT);

    private Button connectButton;
    private Button disconnectButton;
    private Label statusLabel;

    public AlpacaAccountNode() {
        this(new AlpacaSession());
    }

    /**
     * The seam the tests use to put a stub in Alpaca's place. Package-private: the host builds nodes
     * through the no-arg constructor, and which server a brokerage node talks to is not a thing a
     * graph should be able to get wrong.
     *
     * @param session the session this node owns for its whole life
     */
    AlpacaAccountNode(AlpacaSession session) {
        this.session = session;
        // Seeded here rather than at the field, which runs before this constructor does. Downstream
        // nodes read the handle off this output the moment a wire appears, so it cannot start null.
        accountOutput.setValue(session);
        // Two connects at once would have two key checks racing to replace the same credentials.
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
     * nothing downstream is told about a connect that did not happen.
     */
    @Override
    public void process(ProcessContext ctx) {
        try {
            // Re-assert the handle first: a load writes the saved null over this transient output,
            // and every downstream pull would read null forever otherwise. Same fix, same reason, as
            // the Discord Bot node's.
            accountOutput.setValue(session);
            publishByName();

            if (ctx.wasTriggeredVia(disconnectIn)) {
                session.disconnect();
                publish();
                activate(disconnectedOut);
                return;
            }

            ctx.checkCancelled();
            session.connect(credentials());
            publish();
            activate(connectedOut);
        } catch (RuntimeException failure) {
            // See GetQuoteNode.process: nothing fires on a failure, and saying so has to come last.
            activateNone();
            throw failure;
        }
    }

    /** The credentials as the ports currently read. Built per run, never held. */
    private AlpacaCredentials credentials() {
        return new AlpacaCredentials(
                apiKeyInput.getValue(),
                secretKeyInput.getValue(),
                // Anything other than an explicit false is paper. An unset Boolean port reads null,
                // and null must not mean "real money".
                !Boolean.FALSE.equals(paperInput.getValue()));
    }

    /** Copies what the session knows onto the data outputs. */
    private void publish() {
        accountOutput.setValue(session);
        accountNumberOutput.setValue(session.accountNumber());
        connectedOutput.setValue(session.isConnected());
        paperOutput.setValue(session.isPaper());
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
        addInput(apiKeyInput);
        addInput(secretKeyInput);
        addInput(paperInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(accountOutput);
        addOutput(accountNumberOutput);
        addOutput(connectedOutput);
        addOutput(paperOutput);
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
        // Safe here, unlike the Robinhood node's: forgetting a key pair is a field assignment, not a
        // network call, so there is nothing that needs the unbounded half of teardown.
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
        connectButton.setOnAction(event -> connectInBackground());

        disconnectButton = new Button("Disconnect");
        disconnectButton.setMaxWidth(Double.MAX_VALUE);
        disconnectButton.setOnAction(event -> disconnectNow());

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
     * Runs {@link #process} off the FX thread — checking the keys is a round trip, and doing it
     * inline would freeze the canvas for the duration. A plain pull, so {@code triggeredVia()} reads
     * empty and process() takes its connect branch.
     */
    private void connectInBackground() {
        connectButton.setDisable(true);
        statusLabel.setText("Connecting…");
        Thread thread = new Thread(this::beginProcessing, "alpaca-connect-" + authoredName());
        thread.setDaemon(true);
        thread.start();
    }

    /** Disconnecting is a field assignment, so it happens inline; there is nothing to wait for. */
    private void disconnectNow() {
        session.disconnect();
        publish();
        refreshStatus();
    }

    /** Test seam: the session this node owns. */
    AlpacaSession session() {
        return session;
    }

    private static <T> NodeVariable<T> withDefault(NodeVariable<T> variable, T value) {
        variable.setValue(value);
        return variable;
    }
}
