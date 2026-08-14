package io.github.jaymcole.housegraph.plugins.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the lifecycle guarantees {@link NodeProcessServer} makes about a child process — that a
 * start which never listens is reported as a failure, and that {@link NodeProcessServer#stop()}
 * does not return until the port is genuinely free.
 *
 * <p>Both were regressions in practice: a supervised restart tore the old server down without
 * waiting, so the replacement spawned into a still-bound port, died on {@code EADDRINUSE} seconds
 * later, and — because nothing verified the spawn — still reported itself as running.
 *
 * <p>The stand-in server is a single-file Java program rather than a Node app, so the suite needs
 * no {@code node} on PATH and behaves the same on every platform. It reads {@code PORT} from the
 * environment exactly as the real thing is expected to, and lingers briefly on shutdown to imitate
 * a server draining its connections.
 */
class NodeProcessServerTest {

    private final NodeProcessServer server = new NodeProcessServer();

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void aStartCommandThatExitsWithoutListeningIsReportedAsAFailure(@TempDir Path project) {
        // `exit 7` is spelled the same for `sh -c` and `cmd /c`.
        IOException failure = assertThrows(IOException.class,
                () -> server.startProcessForTest(project, "exit 7", freePort()),
                "a start command that never binds the port must fail loudly, not report success");

        assertTrue(failure.getMessage().contains("7"), "the message should name the exit code: " + failure.getMessage());
        assertFalse(server.isRunning(), "nothing should be left running after a failed start");
    }

    @Test
    void stopDoesNotReturnUntilThePortIsFree(@TempDir Path project) throws Exception {
        int port = freePort();
        writeListener(project);

        server.startProcessForTest(project, listenerCommand(), port);
        assertTrue(server.isRunning(), "the stand-in server should be up");
        assertTrue(isListening(port), "start() must not return before something is listening");

        server.stop();

        assertFalse(server.isRunning());
        assertFalse(isListening(port),
                "stop() returned while the port was still bound — the next start would lose the race");
    }

    @Test
    void aServerCanBeRestartedOnTheSamePortImmediately(@TempDir Path project) throws Exception {
        int port = freePort();
        writeListener(project);

        server.startProcessForTest(project, listenerCommand(), port);
        server.stop();
        // No pause: this is the Restart flow-in's exact sequence, and the whole point of stop()
        // blocking is that the port is usable the instant it returns.
        server.startProcessForTest(project, listenerCommand(), port);

        assertTrue(server.isRunning(), "a restart on the same port should succeed");
        assertTrue(isListening(port));
    }

    /** A port nothing is using right now — close enough, since each test binds it moments later. */
    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static boolean isListening(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A minimal server that binds {@code $PORT} and, like a real one, takes a moment to go down —
     * the shutdown hook is what a graceful HTTP drain looks like from the outside. (On Windows a
     * destroyed process is terminated outright and the hook does not run; the assertions are about
     * the state {@code stop()} leaves behind, not about how long it took.)
     */
    private static void writeListener(Path project) throws IOException {
        Files.writeString(project.resolve("Listener.java"), """
                import java.net.ServerSocket;

                public class Listener {
                    public static void main(String[] args) throws Exception {
                        ServerSocket socket = new ServerSocket(Integer.parseInt(System.getenv("PORT")));
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try {
                                Thread.sleep(1500);
                                socket.close();
                            } catch (Exception ignored) {
                            }
                        }));
                        Thread.sleep(600_000);
                    }
                }
                """);
    }

    /** Runs the stand-in through the same shell the real start command goes through. */
    private static String listenerCommand() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return "\"" + java + "\" Listener.java";
    }
}
