package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRequestTest {

    @Test
    void theDefaultsFillThemselvesIn() {
        LlmRequest request = new LlmRequest(null, " http://localhost:11434 ", " llama3.2 ", null, "hello",
                null, null, 30);
        assertEquals(LlmApi.OLLAMA, request.api());
        assertEquals("http://localhost:11434", request.server());
        assertEquals("llama3.2", request.model());
        assertEquals("", request.apiKey());
    }

    @Test
    void anEmptyPromptFailsBeforeAnythingIsSent() {
        LlmException failure = assertThrows(LlmException.class, () -> request("http://localhost:11434", "llama3.2", "  "));
        assertTrue(failure.getMessage().contains("Prompt"), failure.getMessage());
    }

    @Test
    void anUnnamedModelSaysWhatToPutThere() {
        LlmException failure = assertThrows(LlmException.class, () -> request("http://localhost:11434", " ", "hello"));
        assertTrue(failure.getMessage().contains("Model"), failure.getMessage());
    }

    @Test
    void anUnnamedServerFails() {
        assertThrows(LlmException.class, () -> request("", "llama3.2", "hello"));
    }

    @Test
    void aTimeoutIsAtLeastASecond() {
        assertEquals(1, new LlmRequest(LlmApi.OLLAMA, "http://localhost:11434", "llama3.2", null, "hello",
                null, null, 0).timeoutSeconds());
        assertEquals(1, new LlmRequest(LlmApi.OLLAMA, "http://localhost:11434", "llama3.2", null, "hello",
                null, null, -30).timeoutSeconds());
    }

    private static LlmRequest request(String server, String model, String prompt) {
        return new LlmRequest(LlmApi.OLLAMA, server, model, null, prompt, null, null, 30);
    }
}
