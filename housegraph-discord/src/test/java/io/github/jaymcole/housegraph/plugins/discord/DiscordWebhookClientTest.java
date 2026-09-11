package io.github.jaymcole.housegraph.plugins.discord;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client against a stub server standing in for Discord's webhook endpoint — same shape as
 * {@code LocalLlmClientTest} and for the same reason: a real webhook would make these slow,
 * non-deterministic, and dependent on a Discord server actually existing.
 */
class DiscordWebhookClientTest {

    private HttpServer server;
    private String lastBody;
    private final List<String> bodies = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void aSuccessfulPostSendsTheMessageAsJsonContent() throws IOException {
        String address = serve(204, "");

        assertDoesNotThrow(() -> DiscordWebhookClient.send(address, "hello there", null, null, 5));

        assertTrue(lastBody.contains("\"content\":\"hello there\""), lastBody);
        assertFalse(lastBody.contains("username"), lastBody);
        assertFalse(lastBody.contains("avatar_url"), lastBody);
    }

    @Test
    void usernameAndAvatarOverridesAreIncludedWhenGiven() throws IOException {
        String address = serve(204, "");

        DiscordWebhookClient.send(address, "hi", "Doorbell", "https://example.com/avatar.png", 5);

        assertTrue(lastBody.contains("\"username\":\"Doorbell\""), lastBody);
        assertTrue(lastBody.contains("\"avatar_url\":\"https://example.com/avatar.png\""), lastBody);
    }

    @Test
    void blankUsernameAndAvatarAreOmittedRatherThanSentEmpty() throws IOException {
        String address = serve(204, "");

        DiscordWebhookClient.send(address, "hi", "  ", "", 5);

        assertFalse(lastBody.contains("username"), lastBody);
        assertFalse(lastBody.contains("avatar_url"), lastBody);
    }

    @Test
    void aRejectionSurfacesDiscordsOwnMessage() throws IOException {
        String address = serve(400, "{\"message\":\"Invalid Webhook Token\",\"code\":50027}");

        DiscordWebhookException failure = assertThrows(DiscordWebhookException.class,
                () -> DiscordWebhookClient.send(address, "hi", null, null, 5));
        assertTrue(failure.getMessage().contains("HTTP 400"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Invalid Webhook Token"), failure.getMessage());
    }

    @Test
    void contentTooLongForOneDiscordMessageIsPostedAsSeveral() throws IOException {
        String address = serve(204, "");
        // A long answer with nothing but paragraphs to break on - the shape an LLM node produces,
        // and the shape that used to fail the whole send.
        String paragraph = "A".repeat(1500) + "\n\n";
        String answer = paragraph.repeat(3);

        DiscordWebhookClient.send(address, answer, null, null, 5);

        assertEquals(3, bodies.size(), "one POST per message Discord will accept");
        for (String body : bodies) {
            assertTrue(body.contains("\"content\":\"" + "A".repeat(1500) + "\""), "a whole paragraph per post");
        }
    }

    @Test
    void nothingListeningFailsWithoutLeakingTheUrl() {
        // Port 1 has nothing on it: the connection is refused at once.
        DiscordWebhookException failure = assertThrows(DiscordWebhookException.class,
                () -> DiscordWebhookClient.send("http://localhost:1/whatever", "hi", null, null, 5));
        assertFalse(failure.getMessage().contains("localhost:1"), failure.getMessage());
    }

    /** Starts a server that answers every POST with {@code body}, and returns its address. */
    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> respond(exchange, status, body));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(lastBody);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
