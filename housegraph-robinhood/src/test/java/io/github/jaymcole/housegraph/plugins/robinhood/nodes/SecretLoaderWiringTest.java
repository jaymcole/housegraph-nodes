package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.robinhood.StubRobinhood;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logging in with the credentials coming from Secret Loader nodes rather than typed into the
 * account node — the way this library is meant to be wired.
 *
 * <h2>Why a stand-in rather than the real node</h2>
 * HouseGraph's <b>Secret Loader</b> lives in the application, not in {@code housegraph-api}, so a
 * node library cannot compile against it. What it is, from this node's side, is a data node with no
 * flow ports and one secret String output that resolves at {@code process()} time — which is
 * exactly what {@link SecretLoader} below is. The thing under test is this node's side of that
 * contract: that a credential arriving along an edge is resolved before the login, and that having
 * arrived that way it still never reaches a save file.
 */
class SecretLoaderWiringTest {

    /** Stands in for HouseGraph's built-in Secret Loader: no inputs, no flow, one secret output. */
    private static final class SecretLoader extends BaseNode {
        private final NodeVariable<String> value =
                new NodeVariable<>("Value", String.class).markSecret();
        private final String resolved;
        private int resolutions;

        private SecretLoader(String resolved) {
            this.resolved = resolved;
        }

        @Override
        public void process(ProcessContext ctx) {
            // The real node reads the encrypted store here, on every run - which is what makes a
            // rotated secret take effect on the next trigger rather than on the next restart.
            resolutions++;
            value.setValue(resolved);
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(value);
        }
    }

    private static final class Trigger extends BaseNode {
        private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(out);
        }
    }

    private StubRobinhood robinhood;
    private NodeGraph graph;
    private Trigger trigger;
    private RobinhoodAccountNode account;
    private SecretLoader username;
    private SecretLoader password;
    private SecretLoader mfaSeed;
    private String name;

    @BeforeEach
    void setUp() throws IOException {
        robinhood = StubRobinhood.open();
        robinhood.on("GET", "/accounts/", 200, StubRobinhood.accounts("123456789"));

        name = "robinhood-test-" + UUID.randomUUID();
        graph = new NodeGraph();
        trigger = new Trigger();
        account = new RobinhoodAccountNode(robinhood.session());
        username = new SecretLoader("trader@example.com");
        password = new SecretLoader("hunter2");
        mfaSeed = new SecretLoader("GEZDGNBVGY3TQOJQ");
        for (BaseNode node : java.util.List.of(trigger, account, username, password, mfaSeed)) {
            graph.addNode(node);
        }
        Nodes.set(account, "Account Name", name);

        graph.registerEdge(new Edge(username, username.value, account, input("Username")));
        graph.registerEdge(new Edge(password, password.value, account, input("Password")));
        graph.registerEdge(new Edge(mfaSeed, mfaSeed.value, account, input("MFA Secret")));
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, account, connectPort()));
    }

    @AfterEach
    void tearDown() {
        ResourceRegistry.shared().unregister(name);
        robinhood.close();
    }

    private NodeVariable<?> input(String portName) {
        return account.getInputs().stream()
                .filter(variable -> variable.name.equals(portName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no input named " + portName));
    }

    private FlowPort connectPort() {
        return account.getFlowInputs().stream()
                .filter(port -> port.name.equals("Connect"))
                .findFirst()
                .orElseThrow();
    }

    private void connect() {
        graph.execute(trigger);
        graph.awaitIdle();
    }

    @Test
    void logsInWithCredentialsResolvedFromTheStoreAtRunTime() {
        robinhood.on("POST", "/oauth2/token/", 200,
                StubRobinhood.tokens("access-1", "refresh-1", 86400));

        connect();

        assertNull(account.getLastError(), String.valueOf(account.getLastError()));
        assertTrue(account.session().isConnected());
        assertEquals("trader@example.com",
                robinhood.lastCallTo("POST", "/oauth2/token/").json().getString("username"));
    }

    @Test
    void theSeedFromTheStoreIsWhatAnswersTheTwoFactorChallenge() {
        // The reason MFA Secret is wired like the other two rather than being a typed-only field:
        // an unattended graph has to be able to produce codes after a restart.
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.mfaRequired());
        robinhood.on("POST", "/oauth2/token/", 200,
                StubRobinhood.tokens("access-1", "refresh-1", 86400));

        connect();

        assertTrue(account.session().isConnected());
        JSONObject retry = robinhood.lastCallTo("POST", "/oauth2/token/").json();
        assertEquals(6, retry.getString("mfa_code").length());
    }

    @Test
    void everyConnectResolvesTheSecretsAfresh() {
        // A rotated password has to take effect on the next Connect, not the next restart - which
        // it only does if the edge is pulled each time rather than read once and kept.
        robinhood.on("POST", "/oauth2/token/", 200,
                StubRobinhood.tokens("access-1", "refresh-1", 86400));

        connect();
        connect();

        assertEquals(2, password.resolutions);
    }

    @Test
    void awiredCredentialSatisfiesTheRequiredInputWithNothingTypedIn() {
        // The node has to be runnable with its credential fields visibly empty, or every
        // secrets-wired graph would sit there flagged as misconfigured.
        assertNull(Nodes.<String>get(username, "Value"), "nothing resolved before the first run");
        assertFalse(account.isMisconfigured(),
                "unsatisfied: " + account.getUnsatisfiedRequiredInputs().stream()
                        .map(variable -> variable.name).toList());
    }

    @Test
    void aCredentialThatArrivedByEdgeIsStillNeverWrittenToASaveFile() {
        // The trap this pins down: isPersistentValue() is a flag set at construction, so an
        // unmarked port would happily write the value a Secret Loader had just resolved into it.
        robinhood.on("POST", "/oauth2/token/", 200,
                StubRobinhood.tokens("access-1", "refresh-1", 86400));
        connect();

        assertEquals("trader@example.com", input("Username").getValue(),
                "the resolved value should be sitting on the port");
        assertFalse(input("Username").isPersistentValue());
        assertFalse(input("Password").isPersistentValue());
        assertFalse(input("MFA Secret").isPersistentValue());
    }
}
