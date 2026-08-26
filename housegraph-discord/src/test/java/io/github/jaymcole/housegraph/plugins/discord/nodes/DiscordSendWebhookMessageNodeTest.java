package io.github.jaymcole.housegraph.plugins.discord.nodes;

import com.sun.net.httpserver.HttpServer;
import io.github.jaymcole.housegraph.plugins.discord.DiscordWebhookException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code DiscordSendWebhookMessageNode} needs no Discord Bot wired in — unlike
 * {@code DiscordSendMessageNode} it talks to Discord over a plain HTTP POST, so it can be
 * exercised end to end against a stub server the same way {@code DiscordWebhookClientTest}
 * exercises the client alone.
 */
class DiscordSendWebhookMessageNodeTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void declaresWebhookUrlMessageUsernameAvatarAndTimeoutInputsInOrder() {
        DiscordSendWebhookMessageNode node = new DiscordSendWebhookMessageNode();

        assertEquals(List.of("Webhook URL", "Message", "Username", "Avatar URL", "Timeout (s)"),
                Nodes.inputNames(node));
    }

    @Test
    void processingWithNoWebhookUrlFailsRatherThanDoingNothingSilently() {
        DiscordSendWebhookMessageNode node = new DiscordSendWebhookMessageNode();
        Nodes.set(node, "Message", "hello");

        DiscordWebhookException failure = assertThrows(DiscordWebhookException.class, () -> Nodes.run(node));
        assertEquals("Webhook URL is empty.", failure.getMessage());
    }

    @Test
    void processingWithNoMessageFailsRatherThanDoingNothingSilently() {
        DiscordSendWebhookMessageNode node = new DiscordSendWebhookMessageNode();
        Nodes.set(node, "Webhook URL", "http://localhost:1/whatever");

        DiscordWebhookException failure = assertThrows(DiscordWebhookException.class, () -> Nodes.run(node));
        assertEquals("Message is empty.", failure.getMessage());
    }

    @Test
    void processingWithBothWiredPostsToTheWebhook() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        String address = "http://localhost:" + server.getAddress().getPort();

        DiscordSendWebhookMessageNode node = new DiscordSendWebhookMessageNode();
        Nodes.set(node, "Webhook URL", address);
        Nodes.set(node, "Message", "hello there");

        assertDoesNotThrow(() -> Nodes.run(node));
    }
}
