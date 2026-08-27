package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmServerSpecTest {

    @TempDir
    Path tempDir;

    @Test
    void anEmptySpecIsOllamaOnThisMachine() {
        LlmServerSpec spec = LlmServerSpec.of(null, null, null, null, null, null, 0);

        assertEquals(LlmServerSpec.DEFAULT_NAME, spec.name());
        assertEquals(LlmServerSpec.DEFAULT_COMMAND, spec.command());
        assertEquals(LlmApi.OLLAMA, spec.api());
        assertEquals(LocalLlmClient.DEFAULT_SERVER, spec.server());
        assertNull(spec.directory(), "a blank directory means HouseGraph's own, not a path of nothing");
        assertEquals(1, spec.startupTimeoutSeconds(), "a zero timeout is clamped, never taken literally");
    }

    @Test
    void theAddressIsWhatTheReadinessCheckWillLookAt() {
        assertEquals("http://localhost:11434",
                LlmServerSpec.of(null, null, null, "ollama", "localhost:11434", null, 30).address());
        // A Server that names the prompt endpoint still gives a root to hang /api/tags on.
        assertEquals("http://localhost:11434",
                LlmServerSpec.of(null, null, null, "ollama", "http://localhost:11434/api/generate", null, 30)
                        .address());
        assertEquals("http://localhost:8080/v1",
                LlmServerSpec.of(null, null, null, "openai", "http://localhost:8080/v1", null, 30).address());
    }

    @Test
    void aDirectoryThatWasTypedAndDoesNotExistIsATypo() {
        LlmException failure = assertThrows(LlmException.class, () -> LlmServerSpec.of(
                null, "llama-server -m model.gguf", tempDir.resolve("nope").toString(), null, null, null, 30));
        assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
    }

    @Test
    void aDirectoryThatExistsIsKept() {
        LlmServerSpec spec = LlmServerSpec.of(null, null, tempDir.toString(), null, null, null, 30);

        assertEquals(tempDir.toAbsolutePath().normalize(), spec.directory());
    }

    @Test
    void anUnusableAddressFailsHereRatherThanThreeMinutesIntoAStart() {
        assertThrows(LlmException.class,
                () -> LlmServerSpec.of(null, null, null, null, "http://host name with spaces", null, 30));
    }

    @Test
    void anUnknownApiFailsRatherThanGuessing() {
        assertThrows(LlmException.class, () -> LlmServerSpec.of(null, null, null, "anthropic", null, null, 30));
    }

    @Test
    void namesAndCommandsAreTrimmedNotTakenAsTyped() {
        LlmServerSpec spec = LlmServerSpec.of("  box  ", "  ollama serve  ", null, null, null, null, 30);

        assertEquals("box", spec.name());
        assertEquals("ollama serve", spec.command());
    }
}
