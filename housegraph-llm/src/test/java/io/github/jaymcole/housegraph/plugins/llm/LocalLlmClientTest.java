package io.github.jaymcole.housegraph.plugins.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client against a stub server standing in for Ollama or an OpenAI-compatible one. A real
 * model would make these tests slow, non-deterministic and dependent on what happens to be pulled
 * on the machine running them; what is worth testing here is the request that goes out and what is
 * made of what comes back, both of which a five-line handler can answer for.
 */
class LocalLlmClientTest {

    private HttpServer server;
    private String lastPath;
    private String lastBody;
    private String lastAuthorization;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void anOllamaAnswerComesBackAsText() throws IOException {
        String address = serve(200, "{\"model\":\"llama3.2\",\"response\":\"Rayleigh scattering.\",\"done\":true}");

        assertEquals("Rayleigh scattering.", LocalLlmClient.generate(
                new LlmRequest(LlmApi.OLLAMA, address, "llama3.2", null, "Why is the sky blue?", null, null, 10)));
        assertEquals("/api/generate", lastPath);
        assertTrue(lastBody.contains("\"prompt\":\"Why is the sky blue?\""), lastBody);
        assertNull(lastAuthorization, "a local server with no key should not be sent an Authorization header");
    }

    @Test
    void anOpenAiAnswerComesBackAsText() throws IOException {
        String address = serve(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Because of the air.\"}}]}");

        assertEquals("Because of the air.", LocalLlmClient.generate(
                new LlmRequest(LlmApi.OPENAI, address, "local-model", "Be brief.", "Why is the sky blue?",
                        0.1f, null, 10)));
        assertEquals("/v1/chat/completions", lastPath);
        assertTrue(lastBody.contains("\"role\":\"system\""), lastBody);
    }

    @Test
    void anApiKeyIsSentAsABearerTokenWhenThereIsOne() throws IOException {
        String address = serve(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        LocalLlmClient.generate(new LlmRequest(LlmApi.OPENAI, address, "local-model", null, "hello",
                null, " s3cret ", 10));

        assertEquals("Bearer s3cret", lastAuthorization);
    }

    @Test
    void aModelTheServerDoesNotHaveSurfacesWhatItSaid() throws IOException {
        String address = serve(404, "{\"error\":\"model 'llama3.2' not found, try pulling it first\"}");

        LlmException failure = assertThrows(LlmException.class, () -> LocalLlmClient.generate(
                new LlmRequest(LlmApi.OLLAMA, address, "llama3.2", null, "hello", null, null, 10)));
        assertTrue(failure.getMessage().contains("HTTP 404"), failure.getMessage());
        assertTrue(failure.getMessage().contains("try pulling it first"), failure.getMessage());
    }

    @Test
    void anOpenAiErrorObjectSurfacesItsMessage() throws IOException {
        String address = serve(400, "{\"error\":{\"message\":\"model not loaded\",\"type\":\"invalid_request_error\"}}");

        LlmException failure = assertThrows(LlmException.class, () -> LocalLlmClient.generate(
                new LlmRequest(LlmApi.OPENAI, address, "local-model", null, "hello", null, null, 10)));
        assertTrue(failure.getMessage().contains("model not loaded"), failure.getMessage());
    }

    @Test
    void somethingElseListeningOnThePortIsSaidPlainly() throws IOException {
        String address = serve(200, "<html><body>Some other web server</body></html>");

        LlmException failure = assertThrows(LlmException.class, () -> LocalLlmClient.generate(
                new LlmRequest(LlmApi.OLLAMA, address, "llama3.2", null, "hello", null, null, 10)));
        assertTrue(failure.getMessage().contains("did not answer with JSON"), failure.getMessage());
    }

    @Test
    void nothingListeningSaysSoRatherThanTimingOut() {
        // Port 1 has nothing on it: the connection is refused at once.
        LlmException failure = assertThrows(LlmException.class, () -> LocalLlmClient.generate(
                new LlmRequest(LlmApi.OLLAMA, "http://localhost:1", "llama3.2", null, "hello", null, null, 10)));
        assertTrue(failure.getMessage().contains("Nothing is listening"), failure.getMessage());
    }

    @Test
    void anErrorBodyThatIsNeitherShapeIsQuotedRatherThanSwallowed() {
        assertEquals("model 'x' not found", LocalLlmClient.errorFrom("{\"error\":\"model 'x' not found\"}"));
        assertEquals("model not loaded", LocalLlmClient.errorFrom("{\"error\":{\"message\":\"model not loaded\"}}"));
        assertEquals("Service Unavailable", LocalLlmClient.errorFrom("Service Unavailable"));
        assertEquals("(nothing)", LocalLlmClient.errorFrom(null));
    }

    /** Starts a server that answers every POST with {@code body}, and returns its address. */
    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> respond(exchange, status, body));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        lastPath = exchange.getRequestURI().getPath();
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
