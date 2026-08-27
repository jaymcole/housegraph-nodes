package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status check and the pull, against a stub standing in for Ollama. The interesting part is the
 * asymmetry: a server that is down is a <em>result</em> here and an exception everywhere else in
 * this library, so most of these tests are about what comes back rather than what is thrown.
 */
class LlmModelsTest {

    /** A loopback port with nothing on it. Port 1 is privileged and unused, so nothing can be there. */
    private static final String NOTHING_LISTENING = "http://localhost:1";

    @Test
    void aRunningServerReportsItsModels() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest", "qwen2.5:7b")) {
            LlmServerStatus status = LlmModels.status(LlmApi.OLLAMA, stub.address(), null, 5);

            assertTrue(status.running());
            assertEquals(List.of("llama3.2:latest", "qwen2.5:7b"), status.models());
            assertTrue(status.detail().contains("llama3.2:latest"), status.detail());
            assertEquals(List.of("/api/tags"), stub.requestedPaths());
        }
    }

    @Test
    void aServerThatIsNotThereIsAnAnswerRatherThanAFailure() {
        LlmServerStatus status = LlmModels.status(LlmApi.OLLAMA, NOTHING_LISTENING, null, 5);

        assertFalse(status.running());
        assertEquals(List.of(), status.models());
        assertTrue(status.detail().contains("/api/tags"), status.detail());
    }

    @Test
    void aFreshInstallIsRunningWithNothingPulled() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith()) {
            LlmServerStatus status = LlmModels.status(LlmApi.OLLAMA, stub.address(), null, 5);

            assertTrue(status.running(), "an empty model list is a running server, not a down one");
            assertEquals(List.of(), status.models());
        }
    }

    @Test
    void somethingElseOnThePortIsReportedDownWithTheReason() throws IOException {
        try (StubLlmServer stub = StubLlmServer.open()) {
            stub.on("/api/tags", 200, "<html>a web server</html>");

            LlmServerStatus status = LlmModels.status(LlmApi.OLLAMA, stub.address(), null, 5);

            // Not "running with no models": that would let a server node adopt a stranger.
            assertFalse(status.running());
            assertTrue(status.detail().contains("<html>a web server</html>"), status.detail());
        }
    }

    @Test
    void anHttpErrorCarriesWhatTheServerSaid() throws IOException {
        try (StubLlmServer stub = StubLlmServer.open()) {
            stub.on("/api/tags", 401, "{\"error\":\"missing api key\"}");

            LlmServerStatus status = LlmModels.status(LlmApi.OLLAMA, stub.address(), null, 5);

            assertFalse(status.running());
            assertTrue(status.detail().contains("401"), status.detail());
            assertTrue(status.detail().contains("missing api key"), status.detail());
        }
    }

    @Test
    void aBlankAddressStillFails() {
        // A graph that is wrong, not a server that is down - no amount of waiting fixes it.
        assertThrows(LlmException.class, () -> LlmModels.status(LlmApi.OLLAMA, "  ", null, 5));
    }

    @Test
    void aModelThatIsAlreadyThereIsNotDownloadedAgain() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest")) {
            assertFalse(LlmModels.pull(stub.address(), "llama3.2", null, 60),
                    "an untagged name should match the :latest tag Ollama reports");
            assertEquals(List.of("/api/tags"), stub.requestedPaths(), "it should not have called /api/pull");
        }
    }

    @Test
    void aModelThatIsMissingIsPulled() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("qwen2.5:7b")) {
            stub.on("/api/pull", 200, "{\"status\":\"success\"}");

            assertTrue(LlmModels.pull(stub.address(), "llama3.2", null, 60));
            assertEquals(List.of("/api/tags", "/api/pull"), stub.requestedPaths());
            assertTrue(stub.bodies().get(1).contains("\"model\":\"llama3.2\""), stub.bodies().get(1));
            assertTrue(stub.bodies().get(1).contains("\"stream\":false"), stub.bodies().get(1));
        }
    }

    @Test
    void aModelThatDoesNotExistFailsEvenThoughOllamaAnswers200() throws IOException {
        // Ollama reports a bad model name in the body of a 200. Reading only the status code would
        // report a typo as a successful download and leave it to fail on the prompt node later.
        try (StubLlmServer stub = StubLlmServer.openOllamaWith()) {
            stub.on("/api/pull", 200, "{\"error\":\"pull model manifest: file does not exist\"}");

            LlmException failure = assertThrows(LlmException.class,
                    () -> LlmModels.pull(stub.address(), "llama3.9", null, 60));
            assertTrue(failure.getMessage().contains("llama3.9"), failure.getMessage());
            assertTrue(failure.getMessage().contains("file does not exist"), failure.getMessage());
        }
    }

    @Test
    void pullingFromAServerThatIsDownSaysToStartItFirst() {
        LlmException failure = assertThrows(LlmException.class,
                () -> LlmModels.pull(NOTHING_LISTENING, "llama3.2", null, 60));
        assertTrue(failure.getMessage().contains("Local LLM Server"), failure.getMessage());
    }

    @Test
    void pullingNothingFails() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith()) {
            assertThrows(LlmException.class, () -> LlmModels.pull(stub.address(), "  ", null, 60));
        }
    }
}
