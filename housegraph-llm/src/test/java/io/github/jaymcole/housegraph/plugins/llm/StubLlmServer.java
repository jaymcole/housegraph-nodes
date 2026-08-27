package io.github.jaymcole.housegraph.plugins.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for a model server, answering per path. A real Ollama would make these tests slow,
 * non-deterministic and dependent on what happens to be pulled on the machine running them; what is
 * worth testing is what goes out and what is made of what comes back, both of which a handler in
 * a few lines can answer for.
 * <p>
 * Public only because the node tests live in the sibling {@code nodes} package and need it too. It
 * is in the test source set, so it is never part of the library or its jar.
 */
public final class StubLlmServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Response> routes = new LinkedHashMap<>();
    private final List<String> requestedPaths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    private StubLlmServer(HttpServer server) {
        this.server = server;
    }

    /** Starts a stub on a free loopback port that answers 404 until something is registered. */
    public static StubLlmServer open() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        StubLlmServer stub = new StubLlmServer(http);
        http.createContext("/", stub::handle);
        http.start();
        return stub;
    }

    /** A stub already answering Ollama's model list with {@code models}. */
    public static StubLlmServer openOllamaWith(String... models) throws IOException {
        StubLlmServer stub = open();
        stub.on("/api/tags", 200, ollamaTags(models));
        return stub;
    }

    /** Ollama's {@code /api/tags} body for a machine with {@code models} pulled. */
    public static String ollamaTags(String... models) {
        StringBuilder json = new StringBuilder("{\"models\":[");
        for (int index = 0; index < models.length; index++) {
            json.append(index == 0 ? "" : ",").append("{\"name\":\"").append(models[index]).append("\"}");
        }
        return json.append("]}").toString();
    }

    /** Registers the answer for one path, replacing any previous one. */
    public StubLlmServer on(String path, int status, String body) {
        routes.put(path, new Response(status, body));
        return this;
    }

    /** The address to hand a node's Server input. */
    public String address() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Every path asked for, in order. */
    public List<String> requestedPaths() {
        return List.copyOf(requestedPaths);
    }

    /** Every request body received, in order — empty strings for GETs. */
    public List<String> bodies() {
        return List.copyOf(bodies);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        synchronized (this) {
            requestedPaths.add(path);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }
        Response response = routes.getOrDefault(path, new Response(404, "{\"error\":\"no such route\"}"));
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private record Response(int status, String body) {
    }
}
