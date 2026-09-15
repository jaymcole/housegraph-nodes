package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.alpaca.StubAlpaca;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connecting with the keys coming from Secret Loader nodes rather than typed into the account node —
 * the way this library is meant to be wired.
 *
 * <h2>Why a stand-in rather than the real node</h2>
 * HouseGraph's <b>Secret Loader</b> lives in the application, not in {@code housegraph-api}, so a
 * node library cannot compile against it. What it is, from this node's side, is a data node with no
 * flow ports and one secret String output that resolves at {@code process()} time — which is exactly
 * what {@link SecretLoader} below is. The thing under test is this node's side of that contract:
 * that a key arriving along an edge is resolved before the connect, and that having arrived that way
 * it still never reaches a save file.
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
            // rotated key take effect on the next trigger rather than on the next restart.
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

    /** Stands in for whatever says "connect now": a startup trigger, a button, a schedule. */
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

    private StubAlpaca alpaca;
    private NodeGraph graph;
    private Trigger trigger;
    private AlpacaAccountNode account;
    private SecretLoader keyId;
    private SecretLoader secretKey;
    private String name;

    @BeforeEach
    void setUp() throws IOException {
        alpaca = StubAlpaca.openConnected();
        graph = new NodeGraph();
        trigger = new Trigger();
        account = new AlpacaAccountNode(alpaca.session());
        keyId = new SecretLoader("PKTESTKEYID");
        secretKey = new SecretLoader("test-secret-key");
        name = "alpaca-" + UUID.randomUUID();

        for (BaseNode node : java.util.List.of(trigger, account, keyId, secretKey)) {
            graph.addNode(node);
        }
        Nodes.set(account, "Account Name", name);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.out, account,
                account.getFlowInputs().get(0)));
        graph.registerEdge(new Edge(keyId, keyId.value, account, input("API Key ID")));
        graph.registerEdge(new Edge(secretKey, secretKey.value, account, input("API Secret Key")));
    }

    @AfterEach
    void tearDown() {
        account.session().disconnect();
        ResourceRegistry.shared().unregister(name);
        alpaca.close();
    }

    @Test
    void theKeysAreResolvedFromTheLoadersAndTheAccountConnects() {
        graph.execute(trigger);

        assertTrue(account.session().isConnected(), String.valueOf(account.getLastError()));
        assertEquals(1, keyId.resolutions);
        StubAlpaca.Call call = alpaca.lastCallTo("GET", "/v2/account");
        assertEquals("PKTESTKEYID", call.apiKey());
        assertEquals("test-secret-key", call.secretKey());
    }

    @Test
    void aKeyThatArrivedAlongAnEdgeStillNeverReachesASaveFile() {
        // The trap this pins down: isPersistentValue() is a flag set at construction, so an
        // unmarked input would write the value a Secret Loader had just fetched straight into the
        // graph file - which is the one thing fetching it from the store was meant to avoid.
        graph.execute(trigger);

        assertEquals("PKTESTKEYID", input("API Key ID").getValue(), "the value did arrive");
        assertFalse(input("API Key ID").isPersistentValue());
        assertFalse(input("API Secret Key").isPersistentValue());
    }

    @Test
    void theKeysAreReResolvedOnEveryConnectSoARotatedKeyTakesEffect() {
        graph.execute(trigger);
        graph.execute(trigger);

        assertEquals(2, keyId.resolutions);
        assertEquals(2, secretKey.resolutions);
    }

    @SuppressWarnings("unchecked")
    private NodeVariable<String> input(String portName) {
        return (NodeVariable<String>) account.getInputs().stream()
                .filter(variable -> variable.name.equals(portName))
                .findFirst()
                .orElseThrow();
    }
}
