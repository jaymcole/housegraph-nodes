package io.github.jaymcole.housegraph.plugins.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the graph-image request against a stub application registered in the resource
 * registry — no window, no canvas, no JavaFX, which is the whole reason the request lives outside
 * the node.
 */
class GraphImagesTest {

    @TempDir
    Path tempDir;

    private StubService stub;

    @AfterEach
    void unregisterStub() {
        if (stub != null) {
            stub.remove();
            stub = null;
        }
    }

    @Test
    void anApplicationOfferingNoSuchServiceNamesBothReasonsItMightNot() {
        HostServiceException failure = assertThrows(HostServiceException.class,
                () -> GraphImages.export(tempDir, null));

        assertTrue(failure.getMessage().contains(GraphImages.SERVICE_NAME), failure.getMessage());
        // The two cases a reader has to tell apart, and cannot from here: an application too old to
        // have the service, or one that runs graphs without a canvas to draw.
        assertTrue(failure.getMessage().contains("predates"), failure.getMessage());
        assertTrue(failure.getMessage().contains("canvas"), failure.getMessage());
    }

    @Test
    void theFolderReachesTheApplicationAbsoluteHoweverItWasWritten() throws IOException {
        Path target = tempDir.resolve("pictures");
        // A folder typed into the node is text, so it can perfectly well be relative - and the
        // application resolves paths against its own working directory, not the graph's.
        Path relative = Path.of("").toAbsolutePath().relativize(target);
        stub = StubService.answering(GraphImages.SERVICE_NAME,
                StubService.reply(GraphImages.FILES_KEY, List.of(write(target, "a.png"))));

        GraphImages.export(relative, null);

        assertFalse(relative.isAbsolute());
        assertEquals(target.toString(), stub.lastRequest.get(GraphImages.DIRECTORY_KEY));
    }

    @Test
    void everyRequestSaysWhichContractRevisionItSpeaks() {
        stub = StubService.answering(GraphImages.SERVICE_NAME, StubService.reply(GraphImages.FILES_KEY, List.of()));

        GraphImages.export(tempDir, null);

        assertEquals(HostService.CONTRACT_REVISION, stub.lastRequest.get(HostService.CONTRACT_KEY));
    }

    @Test
    void theFolderExistsBeforeTheApplicationIsAskedToWriteIntoIt() {
        Path target = tempDir.resolve("not").resolve("there").resolve("yet");
        stub = StubService.register(GraphImages.SERVICE_NAME, request -> {
            assertTrue(Files.isDirectory(target), "the service was asked to write into a folder that isn't there");
            return StubService.reply(GraphImages.FILES_KEY, List.of());
        });

        GraphImages.export(target, null);

        assertTrue(Files.isDirectory(target));
    }

    @Test
    void aBaseNameIsTrimmedAndPassedOn() throws IOException {
        stub = StubService.answering(GraphImages.SERVICE_NAME,
                StubService.reply(GraphImages.FILES_KEY, List.of(write(tempDir, "a.png"))));

        GraphImages.export(tempDir, "  house  ");

        assertEquals("house", stub.lastRequest.get(GraphImages.BASE_NAME_KEY));
    }

    @Test
    void noBaseNameIsSentAtAllWhenThereIsNoneToSend() throws IOException {
        stub = StubService.answering(GraphImages.SERVICE_NAME,
                StubService.reply(GraphImages.FILES_KEY, List.of(write(tempDir, "a.png"))));

        GraphImages.export(tempDir, "   ");

        // Absent rather than empty: the application falls back to the open graph's own name, and it
        // can only do that if it can tell "no preference" from "call it nothing".
        assertFalse(stub.lastRequest.containsKey(GraphImages.BASE_NAME_KEY), stub.lastRequest.toString());
    }

    @Test
    void everyComponentComesBackInTheOrderTheApplicationDrewIt() throws IOException {
        String first = write(tempDir, "house-1.png");
        String second = write(tempDir, "house-2.png");
        stub = StubService.answering(GraphImages.SERVICE_NAME,
                StubService.reply(GraphImages.FILES_KEY, List.of(second, first)));

        List<Path> files = GraphImages.export(tempDir, null);

        assertEquals(List.of(Path.of(second), Path.of(first)), files);
    }

    @Test
    void aGraphWithNothingOnItIsAnEmptyListRatherThanAFailure() {
        stub = StubService.answering(GraphImages.SERVICE_NAME, StubService.reply(GraphImages.FILES_KEY, List.of()));

        assertEquals(List.of(), GraphImages.export(tempDir, null));
    }

    @Test
    void aFileTheApplicationNamedButDidNotWriteIsAFailure() {
        stub = StubService.answering(GraphImages.SERVICE_NAME,
                StubService.reply(GraphImages.FILES_KEY, List.of(tempDir.resolve("never-written.png").toString())));

        HostServiceException failure = assertThrows(HostServiceException.class,
                () -> GraphImages.export(tempDir, null));

        assertTrue(failure.getMessage().contains("never-written.png"), failure.getMessage());
    }

    @Test
    void theApplicationsOwnReasonIsWhatTheUserSees() {
        stub = StubService.answering(GraphImages.SERVICE_NAME,
                StubService.reply(HostService.ERROR_KEY, "the graph is still loading"));

        HostServiceException failure = assertThrows(HostServiceException.class,
                () -> GraphImages.export(tempDir, null));

        assertTrue(failure.getMessage().contains("the graph is still loading"), failure.getMessage());
    }

    @Test
    void aServiceThatThrowsFailsTheRequestWithWhatItSaid() {
        stub = StubService.register(GraphImages.SERVICE_NAME, request -> {
            throw new IllegalStateException("no window is open");
        });

        HostServiceException failure = assertThrows(HostServiceException.class,
                () -> GraphImages.export(tempDir, null));

        assertTrue(failure.getMessage().contains("no window is open"), failure.getMessage());
    }

    @Test
    void aFolderlessRequestIsRefusedBeforeTheApplicationIsBothered() {
        stub = StubService.register(GraphImages.SERVICE_NAME, request -> {
            throw new AssertionError("the service should not have been called");
        });

        assertThrows(HostServiceException.class, () -> GraphImages.export(null, null));
    }

    /** An empty file standing in for a PNG: nothing here decodes them, it only checks they are there. */
    private static String write(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.writeString(file, "");
        return file.toString();
    }
}
