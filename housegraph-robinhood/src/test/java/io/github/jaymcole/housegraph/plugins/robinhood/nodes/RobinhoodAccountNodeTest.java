package io.github.jaymcole.housegraph.plugins.robinhood.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodException;
import io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession;
import io.github.jaymcole.housegraph.plugins.robinhood.StubRobinhood;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account node's own behaviour: logging in from its ports, publishing itself by name, and the
 * two things it deliberately does <em>not</em> do — keep a credential anywhere, or reconnect by
 * itself on load.
 */
class RobinhoodAccountNodeTest {

    private StubRobinhood robinhood;
    private String name;
    private RobinhoodAccountNode node;

    @BeforeEach
    void setUp() throws IOException {
        robinhood = StubRobinhood.openLoggedIn();
        // A name of this test's own: ResourceRegistry is process-wide, and tests run together.
        name = "robinhood-test-" + UUID.randomUUID();
        node = new RobinhoodAccountNode(robinhood.session());
        Nodes.set(node, "Account Name", name);
        Nodes.set(node, "Username", "trader@example.com");
        Nodes.set(node, "Password", "hunter2");
        Nodes.set(node, "MFA Secret", "GEZDGNBVGY3TQOJQ");
    }

    @AfterEach
    void tearDown() {
        ResourceRegistry.shared().unregister(name);
        robinhood.close();
    }

    @Test
    void connectsAndPublishesWhatItConnectedTo() {
        Nodes.run(node);

        assertEquals(true, Nodes.<Boolean>get(node, "Is Connected"));
        assertEquals("123456789", Nodes.<String>get(node, "Account Number"));
        assertSame(node.session(), Nodes.<RobinhoodSession>get(node, "Account"));
        assertTrue(node.session().isConnected());
    }

    @Test
    void publishesItselfUnderItsAccountNameForARefNodeToFind() {
        Nodes.run(node);

        RobinhoodAccountRefNode ref = new RobinhoodAccountRefNode();
        Nodes.set(ref, "Account Name", name);
        Nodes.run(ref);

        assertSame(node.session(), Nodes.<RobinhoodSession>get(ref, "Account"));
    }

    @Test
    void aRefNodePointingAtNothingResolvesToNothingRatherThanFailing() {
        // A typo in the name is this node's one failure mode; it has to be visible rather than
        // thrown, because the account node may simply not have loaded yet.
        RobinhoodAccountRefNode ref = new RobinhoodAccountRefNode();
        Nodes.set(ref, "Account Name", "not-a-real-account-" + UUID.randomUUID());

        Nodes.run(ref);

        assertNull(Nodes.get(ref, "Account"));
    }

    @Test
    void disconnectingLeavesTheSessionUsableAgainRatherThanBroken() {
        robinhood.on("POST", "/oauth2/revoke_token/", 200, "{}");
        Nodes.run(node);

        node.session().disconnect();
        assertFalse(node.session().isConnected());

        // Reconnecting is the same node and the same handle - everything downstream captured it
        // when the wire appeared, so swapping it here would strand them.
        RobinhoodSession before = node.session();
        Nodes.run(node);
        assertSame(before, node.session());
        assertTrue(node.session().isConnected());
    }

    @Test
    void failsWithSomethingReadableWhenTheCredentialsAreHalfFilledIn() {
        Nodes.set(node, "Password", "");

        String message = assertThrows(RobinhoodException.class, () -> Nodes.run(node)).getMessage();

        assertTrue(message.contains("Password"), message);
    }

    @Test
    void keepsNoCredentialInItsSavedState() {
        // markSecret() already keeps the port values out of the save file; this pins down that the
        // node does not smuggle one into its own state map either.
        Nodes.run(node);

        assertTrue(node.saveState().values().stream().noneMatch(value -> value.contains("hunter2")),
                "saved state was " + node.saveState());
        assertTrue(node.saveState().values().stream()
                        .noneMatch(value -> value.contains("GEZDGNBVGY3TQOJQ")),
                "saved state was " + node.saveState());
    }

    @Test
    void doesNotReconnectByItselfOnLoad() {
        // Unlike the Discord Bot and Local LLM Server nodes, which are AutoStartable. There would be
        // nothing to reconnect with - the credentials are deliberately not on disk - and a brokerage
        // session re-establishing itself the moment a file opens is not a thing to do quietly.
        assertFalse(node instanceof io.github.jaymcole.housegraph.sdk.AutoStartable);
    }

    @Test
    void hasThePortsItsDocumentationDescribes() {
        assertEquals(List.of("Account Name", "Username", "Password", "MFA Secret",
                "Approval Timeout (s)"), Nodes.inputNames(node));
        assertEquals(List.of("Account", "Account Number", "Is Connected"), Nodes.outputNames(node));
        assertEquals(List.of("Connect", "Disconnect"), Nodes.flowInputNames(node));
        assertEquals(List.of("Connected", "Disconnected"), Nodes.flowOutputNames(node));
    }

    @Test
    void noCredentialPortIsEverWrittenToASaveFile() {
        // Including Username, which is not much of a secret but is still a credential. A save file
        // records a manually-editable input's current value and cannot tell a typed one from one an
        // edge resolved a moment ago, so an unmarked port here would write whatever a Secret Loader
        // had just fetched straight into the graph file.
        for (String credential : List.of("Username", "Password", "MFA Secret")) {
            assertTrue(input(credential).isSecret(), credential + " is not marked secret");
            assertFalse(input(credential).isPersistentValue(),
                    credential + " would be written to the save file");
        }
        // And the settings that are not credentials still persist, or the node would forget its own
        // name every time the graph was reopened.
        assertTrue(input("Account Name").isPersistentValue());
        assertTrue(input("Approval Timeout (s)").isPersistentValue());
    }

    @Test
    void requiresAUsernameAndPasswordFromSomewhere() {
        assertTrue(input("Username").isRequired());
        assertTrue(input("Password").isRequired());
        // Optional: an account with app approval rather than an authenticator has no seed to give.
        assertFalse(input("MFA Secret").isRequired());
    }

    private NodeVariable<?> input(String name) {
        return node.getInputs().stream()
                .filter(variable -> variable.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no input named " + name));
    }

}
