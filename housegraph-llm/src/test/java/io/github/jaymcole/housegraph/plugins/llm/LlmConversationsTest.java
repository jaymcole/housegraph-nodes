package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConversationsTest {

    private final AtomicLong now = new AtomicLong();
    private final LlmConversations conversations = new LlmConversations(now::get);

    @Test
    void aNameIsTheWholeConnectionBetweenTwoNodes() {
        LlmConversation first = conversations.get("user-1", 60);

        assertSame(first, conversations.get("user-1", 60));
        assertNotSame(first, conversations.get("user-2", 60));
    }

    @Test
    void askingWhetherAConversationExistsDoesNotStartOne() {
        assertTrue(conversations.peek("user-1").isEmpty());
        assertEquals(0, conversations.size());

        conversations.get("user-1", 60);

        assertTrue(conversations.peek("user-1").isPresent());
    }

    @Test
    void forgettingSaysWhetherThereWasAnythingToForget() {
        conversations.get("user-1", 60);

        assertTrue(conversations.forget("user-1"));
        assertFalse(conversations.forget("user-1"));
        assertTrue(conversations.peek("user-1").isEmpty());
    }

    @Test
    void aConversationNobodyContinuedIsForgottenWhenItsWindowPasses() {
        conversations.get("user-1", 30).record("who wrote Dune?", "Frank Herbert.", 8);

        now.set(29 * 60_000L);
        assertEquals(1, conversations.get("user-1", 30).exchanges(), "still inside the window");

        // Continuing it above started the window over, so this is 29 minutes after that.
        now.addAndGet(31 * 60_000L);
        assertEquals(0, conversations.get("user-1", 30).exchanges(), "the window passed, so this is a new conversation");
    }

    @Test
    void aWindowOfZeroKeepsAConversationForAsLongAsTheProcessRuns() {
        conversations.get("forever", 0).record("one", "1", 8);

        now.set(Long.MAX_VALUE / 4);

        assertEquals(1, conversations.get("forever", 0).exchanges());
    }

    @Test
    void oneNodesShortWindowDoesNotEvictAnothersConversation() {
        // The window travels with the conversation, not with the sweep: a /reset command polling
        // with a two-minute window must not throw away the hour-long conversation next to it.
        conversations.get("patient", 60).record("one", "1", 8);
        conversations.get("impatient", 2).record("one", "1", 8);

        now.set(3 * 60_000L);
        conversations.get("impatient", 2);

        assertEquals(1, conversations.get("patient", 60).exchanges());
        assertEquals(0, conversations.peek("impatient").orElseThrow().exchanges());
    }

    @Test
    void pastTheCapTheLeastRecentlyUsedConversationIsDropped() {
        // What a public bot looks like: one name per person, and nothing that says when they are
        // done talking.
        for (int index = 0; index < LlmConversations.MAX_CONVERSATIONS; index++) {
            conversations.get("user-" + index, 0);
        }
        conversations.get("user-0", 0); // touching it makes it the most recent, not the eldest

        conversations.get("one-too-many", 0);

        assertEquals(LlmConversations.MAX_CONVERSATIONS, conversations.size());
        assertTrue(conversations.peek("user-0").isPresent(), "recently used, so kept");
        assertTrue(conversations.peek("user-1").isEmpty(), "least recently used, so dropped");
    }

    @Test
    void theSharedMapIsTheSameOneEveryNodeGets() {
        assertSame(LlmConversations.shared(), LlmConversations.shared());
    }
}
