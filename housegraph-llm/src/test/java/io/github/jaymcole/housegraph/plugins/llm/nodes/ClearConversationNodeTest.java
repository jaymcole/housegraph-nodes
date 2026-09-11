package io.github.jaymcole.housegraph.plugins.llm.nodes;

import io.github.jaymcole.housegraph.plugins.llm.LlmConversation;
import io.github.jaymcole.housegraph.plugins.llm.LlmConversations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClearConversationNodeTest {

    @Test
    void itHasThePortsItsDocumentationDescribes() {
        ClearConversationNode node = new ClearConversationNode();

        assertEquals(List.of("Conversation"), Nodes.inputNames(node));
        assertEquals(List.of("Forgotten", "Found"), Nodes.outputNames(node));
        assertEquals(1, node.getFlowInputs().size());
        assertEquals(1, node.getFlowOutputs().size());
    }

    @Test
    void clearingForgetsTheConversationAndSaysHowMuchWentWithIt() {
        String name = aConversation();
        LlmConversations.shared().get(name, 60).record("Who wrote Dune?", "Frank Herbert.", 8);

        assertEquals(1, new ClearConversationNode().forget(name));
        assertTrue(LlmConversations.shared().peek(name).isEmpty());
    }

    @Test
    void clearingSomethingNobodyStartedIsNotAFailure() {
        // What /reset typed by someone who hasn't said anything yet does.
        assertEquals(-1, new ClearConversationNode().forget(aConversation()));
    }

    @Test
    void aBlankNameForgetsNothing() {
        // An unwired field must not be able to erase a conversation another node is holding.
        assertEquals(-1, new ClearConversationNode().forget(""));
    }

    @Test
    void beingPulledForDataReportsWhatIsThereAndChangesNothing() {
        String name = aConversation();
        LlmConversation conversation = LlmConversations.shared().get(name, 60);
        conversation.record("Who wrote Dune?", "Frank Herbert.", 8);
        ClearConversationNode node = new ClearConversationNode();
        Nodes.set(node, "Conversation", name);

        // Nodes.run() builds a context with no flow arrival, which is exactly a pull.
        Nodes.run(node);

        boolean found = Nodes.get(node, "Found");
        assertEquals(0, (Integer) Nodes.get(node, "Forgotten"), "a pull forgets nothing");
        assertTrue(found);
        assertEquals(1, conversation.exchanges(), "and leaves the conversation alone");
    }

    @Test
    void beingPulledForAConversationThatDoesNotExistDoesNotStartOne() {
        String name = aConversation();
        ClearConversationNode node = new ClearConversationNode();
        Nodes.set(node, "Conversation", name);

        Nodes.run(node);

        boolean found = Nodes.get(node, "Found");
        assertFalse(found);
        assertEquals(0, (Integer) Nodes.get(node, "Forgotten"));
        assertTrue(LlmConversations.shared().peek(name).isEmpty(), "asking must not be what creates it");
    }

    /** A name no other test shares — the conversations are process-wide by design. */
    private static String aConversation() {
        return "test-" + UUID.randomUUID();
    }
}
