package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading one line of a streamed answer — the two protocols' framing, and the failures that only
 * a stream can have, where the server has already sent HTTP 200 and half an answer.
 */
class LlmStreamChunkTest {

    @Test
    void ollamaGenerateChunksCarryTheirResponseField() {
        LlmStreamChunk chunk = LlmApi.OLLAMA.chunkFrom(
                "{\"model\":\"llama3.2\",\"response\":\"The\",\"done\":false}");

        assertEquals("The", chunk.content());
        assertEquals("", chunk.thinking());
        assertFalse(chunk.done());
    }

    @Test
    void ollamaChatChunksCarryTheirMessageContent() {
        LlmStreamChunk chunk = LlmApi.OLLAMA.chunkFrom(
                "{\"message\":{\"role\":\"assistant\",\"content\":\" sky\"},\"done\":false}");

        assertEquals(" sky", chunk.content());
    }

    @Test
    void ollamaKeepsReasoningInAFieldOfItsOwn() {
        LlmStreamChunk generate = LlmApi.OLLAMA.chunkFrom("{\"thinking\":\"Let me\",\"response\":\"\"}");
        assertEquals("Let me", generate.thinking());
        assertEquals("", generate.content(), "reasoning must never land in the answer");

        LlmStreamChunk chat = LlmApi.OLLAMA.chunkFrom(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\" check\"}}");
        assertEquals(" check", chat.thinking());
    }

    @Test
    void ollamasLastChunkSaysSoAndCarriesNoText() {
        LlmStreamChunk last = LlmApi.OLLAMA.chunkFrom(
                "{\"response\":\"\",\"done\":true,\"eval_count\":259,\"total_duration\":10706818083}");

        assertTrue(last.done());
        assertTrue(last.isEmpty());
    }

    @Test
    void openAiPayloadsAreReadOutOfTheirDataLines() {
        LlmStreamChunk chunk = LlmApi.OPENAI.chunkFrom(
                "data: {\"choices\":[{\"delta\":{\"content\":\"The\"},\"finish_reason\":null}]}");

        assertEquals("The", chunk.content());
        assertFalse(chunk.done());
    }

    @Test
    void openAiFramingIsSkippedRatherThanRead() {
        // Blank separators, comments and non-data fields are the shape of SSE, not an answer.
        assertNull(LlmApi.OPENAI.chunkFrom(""));
        assertNull(LlmApi.OPENAI.chunkFrom("   "));
        assertNull(LlmApi.OPENAI.chunkFrom(": keep-alive"));
        assertNull(LlmApi.OPENAI.chunkFrom("event: message"));
        assertNull(LlmApi.OPENAI.chunkFrom("data:"));
        // And a usage-only frame with no choices in it: a keep-alive, not a protocol mismatch.
        assertNull(LlmApi.OPENAI.chunkFrom("data: {\"usage\":{\"total_tokens\":12}}"));
    }

    @Test
    void openAiStreamsEndAtDoneOrAFinishReason() {
        assertTrue(LlmApi.OPENAI.chunkFrom("data: [DONE]").done());
        assertTrue(LlmApi.OPENAI.chunkFrom(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}").done());
    }

    @Test
    void openAiReasoningIsReadWhereAServerSendsIt() {
        // llama.cpp and vLLM both stream it under this name; a server that sends none simply has
        // an empty field, which is not a failure.
        LlmStreamChunk chunk = LlmApi.OPENAI.chunkFrom(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"},\"finish_reason\":null}]}");

        assertEquals("hmm", chunk.thinking());
        assertEquals("", chunk.content());
    }

    @Test
    void blankOllamaLinesCarryNothing() {
        assertNull(LlmApi.OLLAMA.chunkFrom(""));
        assertNull(LlmApi.OLLAMA.chunkFrom(null));
    }

    @Test
    void anErrorArrivingMidStreamFailsRatherThanLookingLikeTheEnd() {
        // The failure a stream has and a single reply does not: 200 has gone out, some of the
        // answer with it, and the server then gives up. Read as an ordinary chunk this would hand
        // a graph half an answer as though it were the whole one.
        LlmException ollama = assertThrows(LlmException.class, () ->
                LlmApi.OLLAMA.chunkFrom("{\"error\":\"model requires more system memory\"}"));
        assertTrue(ollama.getMessage().contains("part-way through"), ollama.getMessage());
        assertTrue(ollama.getMessage().contains("more system memory"), ollama.getMessage());

        assertThrows(LlmException.class, () -> LlmApi.OPENAI.chunkFrom(
                "data: {\"error\":{\"message\":\"context window exceeded\"}}"));
    }

    @Test
    void aLineThatIsNotJsonAtAllIsAFailureNotAnEmptyChunk() {
        assertThrows(LlmException.class, () -> LlmApi.OLLAMA.chunkFrom("<html>gateway timeout</html>"));
    }
}
