package io.github.jaymcole.housegraph.plugins.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a streamed answer off a real socket. A stub that hands back one whole body would not
 * exercise the thing this is for — that pieces arrive over time and are handed on as they do.
 */
class LocalLlmStreamTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void everyPieceIsHandedOnAsItArrivesAndTheWholeAnswerIsLeftBehind() throws IOException {
        String address = streaming(
                "{\"response\":\"Frank \",\"done\":false}",
                "{\"response\":\"Herbert\",\"done\":false}",
                "{\"response\":\".\",\"done\":false}",
                "{\"response\":\"\",\"done\":true}");

        List<String> seen = new ArrayList<>();
        LlmProgress progress = new LlmProgress(System.nanoTime());
        LocalLlmClient.stream(request(address), progress, chunk -> seen.add(chunk.content()));

        assertEquals(List.of("Frank ", "Herbert", ".", ""), seen);
        assertEquals("Frank Herbert.", progress.answer());
    }

    @Test
    void reasoningAndAnswerStayApartAllTheWayThrough() throws IOException {
        String address = streaming(
                "{\"thinking\":\"Dune is\",\"response\":\"\",\"done\":false}",
                "{\"thinking\":\" a book.\",\"response\":\"\",\"done\":false}",
                "{\"thinking\":\"\",\"response\":\"Frank Herbert.\",\"done\":false}",
                "{\"done\":true}");

        LlmProgress progress = new LlmProgress(System.nanoTime());
        LocalLlmClient.stream(request(address), progress, chunk -> { });

        assertEquals("Frank Herbert.", progress.answer());
        assertEquals("Dune is a book.", progress.thinking());
    }

    @Test
    void anOpenAiEventStreamIsReadThroughItsFraming() throws IOException {
        String address = streaming(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Frank\"},\"finish_reason\":null}]}",
                "",
                ": keep-alive",
                "data: {\"choices\":[{\"delta\":{\"content\":\" Herbert\"},\"finish_reason\":null}]}",
                "",
                "data: [DONE]");

        LlmProgress progress = new LlmProgress(System.nanoTime());
        LocalLlmClient.stream(new LlmRequest(LlmApi.OPENAI, address, "local-model", null, List.of(),
                false, "Who wrote Dune?", null, null, null, 10, true, null), progress, chunk -> { });

        assertEquals("Frank Herbert", progress.answer());
    }

    @Test
    void aFailureAnnouncedPartWayThroughFailsTheCallRatherThanTruncatingTheAnswer() throws IOException {
        String address = streaming(
                "{\"response\":\"Frank \",\"done\":false}",
                "{\"error\":\"model runner has stopped\"}");

        LlmProgress progress = new LlmProgress(System.nanoTime());
        LlmException failure = assertThrows(LlmException.class, () ->
                LocalLlmClient.stream(request(address), progress, chunk -> { }));

        assertTrue(failure.getMessage().contains("model runner has stopped"), failure.getMessage());
    }

    @Test
    void whatTheConsumerThrowsEndsTheCall() throws IOException {
        // This is how a cancelled run stops a generation part-way: the node's consumer checks
        // cancellation and throws, and nothing swallows it on the way out.
        String address = streaming(
                "{\"response\":\"one\",\"done\":false}",
                "{\"response\":\"two\",\"done\":false}",
                "{\"response\":\"\",\"done\":true}");

        LlmProgress progress = new LlmProgress(System.nanoTime());
        List<String> seen = new ArrayList<>();
        assertThrows(IllegalStateException.class, () ->
                LocalLlmClient.stream(request(address), progress, chunk -> {
                    seen.add(chunk.content());
                    throw new IllegalStateException("cancelled");
                }));

        assertEquals(List.of("one"), seen, "and stopped at the piece it threw on");
    }

    @Test
    void aServerThatRefusesTheRequestIsReportedWithWhatItSaid() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"error\":\"model 'nope' not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        String address = "http://localhost:" + server.getAddress().getPort();

        LlmException failure = assertThrows(LlmException.class, () ->
                LocalLlmClient.stream(request(address), new LlmProgress(System.nanoTime()), chunk -> { }));

        assertTrue(failure.getMessage().contains("HTTP 404"), failure.getMessage());
        assertTrue(failure.getMessage().contains("not found"), failure.getMessage());
    }

    @Test
    void aBodyThatEndsWithoutSayingDoneStillDeliversWhatArrived() throws IOException {
        // Both protocols just close the connection after the last chunk; a server that closed it
        // without a done marker has still said everything it is going to.
        String address = streaming("{\"response\":\"Frank Herbert.\",\"done\":false}");

        LlmProgress progress = new LlmProgress(System.nanoTime());
        LocalLlmClient.stream(request(address), progress, chunk -> { });

        assertEquals("Frank Herbert.", progress.answer());
    }

    private static LlmRequest request(String address) {
        return new LlmRequest(LlmApi.OLLAMA, address, "llama3.2", null, List.of(), false,
                "Who wrote Dune?", null, null, null, 10, true, null);
    }

    /** A server that writes {@code lines} one at a time, flushing each, as a real one does. */
    private String streaming(String... lines) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            // 0: chunked, so the body arrives in pieces rather than all at once at the end.
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String line : lines) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }
}
