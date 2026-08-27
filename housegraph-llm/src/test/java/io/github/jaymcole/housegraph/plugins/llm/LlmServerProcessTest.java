package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supervisor, without a real model server. Two of its three behaviours can be exercised
 * honestly against a stub: adopting something that is already answering, and failing when the
 * command it was given never answers. The third — a spawned server that comes up properly — would
 * need a program that binds a port and speaks Ollama's protocol, which is a model server; what that
 * path does with the answer once it has one is covered by {@link LlmModelsTest} instead.
 * <p>
 * Every test points {@code LlmServerRecord} at a temporary directory: the orphan reaping runs on
 * every start, and a suite has no business reading or writing the real user profile's cache.
 */
class LlmServerProcessTest {

    /** A loopback port with nothing on it, so a readiness probe is refused at once. */
    private static final String NOTHING_LISTENING = "http://localhost:1";

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateSpawnRecords() {
        LlmServerRecord.useDirectoryForTest(tempDir.resolve("records"));
    }

    @AfterEach
    void restoreSpawnRecords() {
        LlmServerRecord.useDirectoryForTest(null);
    }

    @Test
    void aServerThatIsAlreadyRunningIsAdoptedRatherThanStartedAgain() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest")) {
            LlmServerProcess process = new LlmServerProcess();
            // A command that would fail loudly if it ever ran, which is the assertion: it must not.
            LlmServerSpec spec = LlmServerSpec.of("adopted", "exit 7", null, null, stub.address(), null, 30);

            process.start(spec);

            assertTrue(process.isRunning());
            assertTrue(process.isAdopted());
            assertEquals(List.of("llama3.2:latest"), process.models());
            assertEquals(stub.address(), process.address());
        }
    }

    @Test
    void anAdoptedServerIsLeftRunningWhenTheNodeStops() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest")) {
            LlmServerProcess process = new LlmServerProcess();
            process.start(LlmServerSpec.of("adopted", "exit 7", null, null, stub.address(), null, 30));

            process.stop();

            assertFalse(process.isRunning());
            assertFalse(process.isAdopted());
            // The stub - standing in for the machine's own Ollama service - is still there.
            assertTrue(LlmModels.status(LlmApi.OLLAMA, stub.address(), null, 5).running(),
                    "stopping the node must not take down a server it never started");
        }
    }

    @Test
    void startingTwiceOnARunningServerDoesNothingTheSecondTime() throws IOException {
        try (StubLlmServer stub = StubLlmServer.openOllamaWith("llama3.2:latest")) {
            LlmServerProcess process = new LlmServerProcess();
            LlmServerSpec spec = LlmServerSpec.of("adopted", "exit 7", null, null, stub.address(), null, 30);

            process.start(spec);
            process.start(spec);

            assertTrue(process.isRunning());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCommandThatExitsWithoutAnsweringFailsWithItsExitCode() {
        LlmServerProcess process = new LlmServerProcess();
        LlmServerSpec spec = LlmServerSpec.of("gone", "exit 3", null, null, NOTHING_LISTENING, null, 30);

        IOException failure = assertThrows(IOException.class, () -> process.start(spec));

        assertTrue(failure.getMessage().contains("exited with 3"), failure.getMessage());
        assertTrue(failure.getMessage().contains("[llm]"), failure.getMessage());
        assertFalse(process.isRunning());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCommandThatNeverAnswersIsGivenUpOnAndKilled() {
        LlmServerProcess process = new LlmServerProcess();
        LlmServerSpec spec = LlmServerSpec.of("silent", "sleep 30", null, null, NOTHING_LISTENING, null, 1);

        IOException failure = assertThrows(IOException.class, () -> process.start(spec));

        assertTrue(failure.getMessage().contains("Nothing answered"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Startup Timeout"), failure.getMessage());
        assertFalse(process.isRunning(), "a start that gave up must not leave the process behind");
    }

    @Test
    void stoppingSomethingThatWasNeverStartedIsANoOp() {
        LlmServerProcess process = new LlmServerProcess();

        process.stop();
        process.stop();

        assertFalse(process.isRunning());
    }
}
