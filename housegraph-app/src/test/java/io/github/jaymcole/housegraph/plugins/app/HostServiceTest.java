package io.github.jaymcole.housegraph.plugins.app;

import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the contract that has no compiler behind it: this library and the application agree
 * only on {@code Function} and {@code Map}, so every way a reply can be wrong is a runtime
 * question, and each one has to produce a sentence rather than a {@code ClassCastException} thrown
 * out of a node.
 */
class HostServiceTest {

    private static final String NAME = "housegraph.test-service";

    private StubService stub;

    @AfterEach
    void unregisterStub() {
        if (stub != null) {
            stub.remove();
            stub = null;
        }
    }

    @Test
    void nothingRegisteredUnderTheNameIsSimplyEmpty() {
        assertEquals(Optional.empty(), HostService.find(NAME));
    }

    @Test
    void somethingRegisteredUnderTheNameThatIsNotAServiceIsAlsoEmpty() {
        // A user's own resource node could register anything at all under any name. Finding one is
        // not "the service is broken", it is "there is no service here".
        ResourceRegistry.shared().register(NAME, "not a service");
        try {
            assertEquals(Optional.empty(), HostService.find(NAME));
        } finally {
            ResourceRegistry.shared().unregister(NAME);
        }
    }

    @Test
    void aReplyThatIsNotAMapSaysTheTwoSidesDisagreeAboutTheContract() {
        register(request -> null);

        HostServiceException failure = assertThrows(HostServiceException.class, () -> call().call(Map.of()));

        assertTrue(failure.getMessage().contains("nothing"), failure.getMessage());
        assertTrue(failure.getMessage().contains("contract"), failure.getMessage());
    }

    @Test
    void anErrorInTheReplyIsTheMessageTheUserSees() {
        stub = StubService.answering(NAME, StubService.reply(HostService.ERROR_KEY, "the canvas is busy"));

        HostServiceException failure = assertThrows(HostServiceException.class, () -> call().call(Map.of()));

        assertTrue(failure.getMessage().contains("the canvas is busy"), failure.getMessage());
    }

    @Test
    void anEmptyErrorIsNotAnError() {
        // A service that sets the key unconditionally and leaves it blank on success is a service
        // that works; reading "" as a failure would break it for no gain.
        stub = StubService.answering(NAME, StubService.reply(HostService.ERROR_KEY, "  ", "files", List.of("a.png")));
        HostService service = call();

        assertEquals(List.of("a.png"), service.stringsOf(service.call(Map.of()), "files"));
    }

    @Test
    void theRequestReachesTheServiceWithTheCallersKeysIntact() {
        stub = StubService.answering(NAME, StubService.reply());

        call().call(Map.of("directory", "/tmp/pictures"));

        assertEquals("/tmp/pictures", stub.lastRequest.get("directory"));
        assertEquals(HostService.CONTRACT_REVISION, stub.lastRequest.get(HostService.CONTRACT_KEY));
    }

    @Test
    void aMissingListKeyIsAnEmptyListRatherThanAFailure() {
        stub = StubService.answering(NAME, StubService.reply());

        assertEquals(List.of(), call().stringsOf(Map.of(), "files"));
    }

    @Test
    void aListKeyHoldingSomethingOtherThanStringsIsReadAnyway() {
        // Erasure means a service can hand back Paths, or a mix, without the compiler noticing on
        // either side. The useful failure is a missing key, not element three being the wrong type.
        stub = StubService.answering(NAME, StubService.reply());
        // Expected via toString() rather than as a literal: what a Path renders as is the platform's
        // business (it is "\tmp\a.png" on Windows), and what's under test is that it was rendered.
        Path path = Path.of("/tmp/a.png");

        assertEquals(List.of(path.toString(), "7"),
                call().stringsOf(Map.of("files", Arrays.asList(path, 7)), "files"));
    }

    @Test
    void aListKeyThatIsNotAListIsAFailure() {
        stub = StubService.answering(NAME, StubService.reply());

        HostServiceException failure = assertThrows(HostServiceException.class,
                () -> call().stringsOf(Map.of("files", "a.png"), "files"));

        assertTrue(failure.getMessage().contains("list"), failure.getMessage());
    }

    private void register(Function<Map<String, Object>, Map<String, Object>> answer) {
        stub = StubService.register(NAME, answer);
    }

    private HostService call() {
        return HostService.find(NAME).orElseThrow(() -> new AssertionError("the stub was not registered"));
    }
}
