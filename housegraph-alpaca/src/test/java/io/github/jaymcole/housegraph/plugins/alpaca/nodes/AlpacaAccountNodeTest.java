package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaException;
import io.github.jaymcole.housegraph.plugins.alpaca.AlpacaSession;
import io.github.jaymcole.housegraph.plugins.alpaca.StubAlpaca;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The node that holds the keys: what it publishes, what it refuses, and what it never writes down. */
class AlpacaAccountNodeTest {

    private StubAlpaca alpaca;
    private AlpacaAccountNode node;
    private String name;

    @BeforeEach
    void setUp() throws IOException {
        alpaca = StubAlpaca.openConnected();
        node = new AlpacaAccountNode(alpaca.session());
        // A name of its own per test: the registry is shared process-wide, like the real one.
        name = "alpaca-" + UUID.randomUUID();
        Nodes.set(node, "Account Name", name);
        Nodes.set(node, "API Key ID", "PKTESTKEYID");
        Nodes.set(node, "API Secret Key", "test-secret-key");
    }

    @AfterEach
    void tearDown() {
        node.session().disconnect();
        ResourceRegistry.shared().unregister(name);
        alpaca.close();
    }

    @Test
    void connectingChecksTheKeysAndFillsInTheAccount() {
        Nodes.run(node);

        assertEquals(true, Nodes.<Boolean>get(node, "Is Connected"));
        assertEquals("PA123456789", Nodes.<String>get(node, "Account Number"));
        assertEquals(1, alpaca.callsTo("GET", "/v2/account").size());
    }

    @Test
    void paperTradingIsOnBeforeAnybodyTouchesAnything() {
        // The default that makes a freshly dropped node safe. A live account is a deliberate act -
        // and, because Alpaca issues separate keys, one that needs different keys too.
        assertEquals(Boolean.TRUE, inputValue("Paper Trading"));

        Nodes.run(node);

        assertEquals(true, Nodes.<Boolean>get(node, "Is Paper"));
        assertTrue(node.session().statusText().contains("paper"), node.session().statusText());
    }

    @Test
    void anUnsetPaperPortIsStillPaper() {
        // A Boolean port reads null when nothing has been set, and null must not mean "real money".
        Nodes.set(node, "Paper Trading", null);

        Nodes.run(node);

        assertEquals(true, Nodes.<Boolean>get(node, "Is Paper"));
    }

    @Test
    void theKeysAreSecretInputsSoASaveFileNeverCarriesThem() {
        // Not because a key id is much of a secret, but because a save file records a manually
        // editable input's current value and cannot tell a typed one from one an edge just
        // resolved - which would write a Secret Loader's value straight into the graph file.
        assertTrue(input("API Key ID").isSecret(), "API Key ID is not marked secret");
        assertTrue(input("API Secret Key").isSecret(), "API Secret Key is not marked secret");
        assertFalse(input("API Key ID").isPersistentValue(),
                "API Key ID would be written to the graph file");
        assertFalse(input("API Secret Key").isPersistentValue(),
                "API Secret Key would be written to the graph file");

        // The ordinary ports are saved, which is what makes a reloaded graph keep its wiring.
        assertTrue(input("Account Name").isPersistentValue());
        assertTrue(input("Paper Trading").isPersistentValue());
    }

    @Test
    void theSessionIsPublishedByNameAsSoonAsTheNodeJoinsTheGraph() {
        // Before anything runs: a Ref node elsewhere on the canvas resolves by name, and load order
        // is not something a graph's author controls.
        node.onActivated();

        assertSame(node.session(),
                ResourceRegistry.shared().find(name, AlpacaSession.class).orElse(null));
    }

    @Test
    void renamingTheAccountMovesItRatherThanLeavingTwo() {
        Nodes.run(node);
        String renamed = name + "-renamed";
        Nodes.set(node, "Account Name", renamed);
        try {
            Nodes.run(node);

            assertTrue(ResourceRegistry.shared().find(name, AlpacaSession.class).isEmpty(),
                    "the old name still resolves");
            assertNotNull(ResourceRegistry.shared().find(renamed, AlpacaSession.class).orElse(null));
        } finally {
            ResourceRegistry.shared().unregister(renamed);
        }
    }

    @Test
    void aRefNodePointedAtTheSameNameGetsTheSameSessionAndOpensNoSecondConnection() {
        Nodes.run(node);
        AlpacaAccountRefNode ref = new AlpacaAccountRefNode();
        Nodes.set(ref, "Account Name", name);

        Nodes.run(ref);

        assertSame(node.session(), Nodes.<AlpacaSession>get(ref, "Account"));
        assertEquals(true, Nodes.<Boolean>get(ref, "Is Paper"));
        assertEquals(1, alpaca.callsTo("GET", "/v2/account").size(), "the ref connected again");
    }

    @Test
    void aRefNodePointedAtNothingResolvesToNullRatherThanFailing() {
        AlpacaAccountRefNode ref = new AlpacaAccountRefNode();
        Nodes.set(ref, "Account Name", "nothing-is-called-this");

        Nodes.run(ref);

        assertNull(Nodes.get(ref, "Account"));
    }

    @Test
    void badKeysFailTheNodeAndLeaveItDisconnected() {
        alpaca.only("GET", "/v2/account", 401,
                StubAlpaca.error(40110000, "access key verification failed"));

        AlpacaException failure = assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(failure.getMessage().contains("Paper Trading"), failure.getMessage());
        assertFalse(node.session().isConnected());
        assertFalse(Boolean.TRUE.equals(Nodes.<Boolean>get(node, "Is Connected")));
    }

    @Test
    void missingKeysNeverReachTheNetwork() {
        Nodes.set(node, "API Secret Key", "");

        assertThrows(AlpacaException.class, () -> Nodes.run(node));

        assertTrue(alpaca.calls().isEmpty());
    }

    @Test
    void removingTheNodeTakesTheSessionOutOfTheRegistryAndForgetsTheKeys() {
        Nodes.run(node);

        node.onRemoved();

        assertTrue(ResourceRegistry.shared().find(name, AlpacaSession.class).isEmpty());
        assertFalse(node.session().isConnected());
    }

    @SuppressWarnings("unchecked")
    private NodeVariable<Object> input(String portName) {
        return (NodeVariable<Object>) node.getInputs().stream()
                .filter(variable -> variable.name.equals(portName))
                .findFirst()
                .orElseThrow();
    }

    private Object inputValue(String portName) {
        return input(portName).getValue();
    }
}
