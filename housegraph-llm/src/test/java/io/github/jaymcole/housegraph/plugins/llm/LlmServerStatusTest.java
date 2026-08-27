package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmServerStatusTest {

    @Test
    void anUntaggedNameFindsTheLatestTag() {
        // The case that matters: `ollama pull llama3.2` gives a model reported as llama3.2:latest,
        // and llama3.2 is what the Local LLM node ships with in its Model field.
        LlmServerStatus status = LlmServerStatus.up(List.of("llama3.2:latest"), "");

        assertTrue(status.has("llama3.2"));
        assertTrue(status.has("llama3.2:latest"));
        assertTrue(status.has("LLaMA3.2"), "model names should not be case sensitive");
    }

    @Test
    void aNamedTagIsMatchedExactly() {
        LlmServerStatus status = LlmServerStatus.up(List.of("llama3.2:1b"), "");

        assertTrue(status.has("llama3.2:1b"));
        assertFalse(status.has("llama3.2:3b"));
        assertFalse(status.has("llama3.2"), "asking for a specific tag means it, so :latest is not it");
    }

    @Test
    void nothingIsFoundOnAServerThatIsDown() {
        LlmServerStatus status = LlmServerStatus.down("Nothing is listening.");

        assertFalse(status.running());
        assertFalse(status.has("llama3.2"));
        assertEquals(List.of(), status.models());
        assertEquals("Nothing is listening.", status.detail());
    }

    @Test
    void nothingIsWhatABlankNameFinds() {
        LlmServerStatus status = LlmServerStatus.up(List.of("llama3.2:latest"), "");

        assertFalse(status.has(null));
        assertFalse(status.has("  "));
    }

    @Test
    void theModelListIsACopyNobodyCanReachBackInto() {
        List<String> models = new java.util.ArrayList<>(List.of("llama3.2:latest"));
        LlmServerStatus status = LlmServerStatus.up(models, "");

        models.clear();

        assertEquals(List.of("llama3.2:latest"), status.models());
        assertThrows(UnsupportedOperationException.class, () -> status.models().add("qwen2.5"));
    }
}
