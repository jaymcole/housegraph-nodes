package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConversationTest {

    @Test
    void anExchangeIsTheQuestionAndTheAnswer() {
        LlmConversation conversation = new LlmConversation(0L);

        conversation.record("Who wrote Dune?", "Frank Herbert.", 8);

        assertEquals(1, conversation.exchanges());
        assertEquals(List.of("user:Who wrote Dune?", "assistant:Frank Herbert."), flatten(conversation.history(8)));
    }

    @Test
    void trimmingDropsWholeExchangesOldestFirst() {
        LlmConversation conversation = new LlmConversation(0L);

        conversation.record("one", "1", 2);
        conversation.record("two", "2", 2);
        conversation.record("three", "3", 2);

        // Never a question without its answer: a history cut mid-pair reads to the model as
        // something that did not happen.
        assertEquals(2, conversation.exchanges());
        assertEquals(List.of("user:two", "assistant:2", "user:three", "assistant:3"),
                flatten(conversation.history(8)));
    }

    @Test
    void readingLessThanIsHeldTakesTheMostRecent() {
        LlmConversation conversation = new LlmConversation(0L);
        conversation.record("one", "1", 8);
        conversation.record("two", "2", 8);

        assertEquals(List.of("user:two", "assistant:2"), flatten(conversation.history(1)));
        assertEquals(List.of(), conversation.history(0));
        assertEquals(List.of(), conversation.history(-3));
    }

    @Test
    void keepingNothingIsAWayToTurnMemoryOff() {
        LlmConversation conversation = new LlmConversation(0L);

        conversation.record("one", "1", 0);

        assertEquals(0, conversation.exchanges());
        assertEquals(List.of(), conversation.history(8));
    }

    @Test
    void aModelThatSaidNothingIsStillAnExchange() {
        LlmConversation conversation = new LlmConversation(0L);

        conversation.record("hello?", "", 8);

        assertEquals(List.of("user:hello?", "assistant:"), flatten(conversation.history(8)));
    }

    @Test
    void clearingLeavesTheConversationItselfInPlace() {
        LlmConversation conversation = new LlmConversation(0L);
        conversation.record("one", "1", 8);

        conversation.clear();

        assertEquals(0, conversation.exchanges());
        conversation.record("two", "2", 8);
        assertEquals(1, conversation.exchanges());
    }

    @Test
    void anIdleWindowOfZeroMeansNever() {
        LlmConversation conversation = new LlmConversation(0L);

        conversation.touch(0L, 0L);

        assertFalse(conversation.isExpired(Long.MAX_VALUE / 2));
    }

    @Test
    void aConversationExpiresOnlyAfterItsWindowHasPassed() {
        LlmConversation conversation = new LlmConversation(0L);
        conversation.touch(1_000L, 60_000L);

        assertFalse(conversation.isExpired(61_000L), "exactly at the window is still within it");
        assertTrue(conversation.isExpired(61_001L));

        // Using it again starts the window over.
        conversation.touch(61_001L, 60_000L);
        assertFalse(conversation.isExpired(100_000L));
    }

    /** A history as {@code role:content}, which is what these tests actually care about. */
    private static List<String> flatten(List<LlmMessage> history) {
        return history.stream().map(message -> message.role() + ":" + message.content()).toList();
    }
}
