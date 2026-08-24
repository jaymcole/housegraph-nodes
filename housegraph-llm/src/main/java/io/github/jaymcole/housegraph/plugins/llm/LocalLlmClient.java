package io.github.jaymcole.housegraph.plugins.llm;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One POST to a model server on this machine, and the answer as text. Everything that is the same
 * whichever protocol the server speaks — the client, the timeouts, the optional bearer token, and
 * turning a failure into a sentence worth reading — lives here; {@link LlmApi} holds the rest.
 * <p>
 * <b>The failures are the interesting part.</b> A local model server fails in four ways that all
 * look like "it didn't work" from the canvas, so each one is told apart and named: nothing
 * listening (the server isn't running), an HTTP error carrying the server's own complaint (usually
 * a model that isn't installed), an answer that isn't the selected protocol's shape (the API
 * setting doesn't match the server), and a request that ran out of time. The last is the one that
 * catches people out, because a model that is not resident yet is loaded from disk on the first
 * prompt — the first call can take minutes where the second takes seconds — so its message says so
 * instead of just reporting a timeout.
 * <p>
 * <b>Nothing here retries.</b> A node that ran is a node that ran once; re-asking a model that
 * just refused would double the wait for the same answer, and a graph that wants another attempt
 * can wire one.
 */
public final class LocalLlmClient {

    private static final Logger log = Log.get(LocalLlmClient.class);

    /** Where Ollama listens when nobody has moved it. Pre-filled on the node so it works out of the box. */
    public static final String DEFAULT_SERVER = "http://localhost:11434";

    /** A small, current, widely-pulled model — a starting point to edit, not a claim about what is installed. */
    public static final String DEFAULT_MODEL = "llama3.2";

    /**
     * Two minutes. Long enough that loading a model from disk on the first prompt of the day does
     * not fail the node, short enough that a wedged server does not hold a run open forever.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /**
     * Shared, like every {@code HttpClient} in this repository: it pools connections, and a local
     * server prompted in a loop should be talked to over one of them. The connect timeout is short
     * on purpose — a machine that is not running a model server refuses the connection at once, and
     * that answer should not wait out the generation timeout.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private LocalLlmClient() {
    }

    /**
     * Sends one prompt and returns what the model generated.
     *
     * @param request what to ask, and where
     * @return the generated text, never null (a model that answered with nothing gives {@code ""})
     * @throws LlmException if the server can't be reached, rejects the request, runs out of time,
     *                      or answers with something other than the selected API's reply shape
     */
    public static String generate(LlmRequest request) {
        LlmApi api = request.api();
        URI endpoint = api.endpoint(request.server());
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(request.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(api.requestBody(request), StandardCharsets.UTF_8));
        if (!request.apiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }

        long startedAt = System.nanoTime();
        HttpResponse<String> response = send(builder.build(), request, endpoint);
        if (response.statusCode() / 100 != 2) {
            throw new LlmException("The LLM server at " + endpoint + " answered HTTP " + response.statusCode()
                    + ": " + errorFrom(response.body()));
        }
        String reply = api.replyFrom(response.body());
        // The prompt and the answer are the user's, and can be long: log the shape of the call, not
        // its content. That is enough to tell "the model is slow" from "the node never ran".
        log.debug("{} answered {} in {} ms with {} characters",
                endpoint, request.model(), (System.nanoTime() - startedAt) / 1_000_000L, reply.length());
        return reply;
    }

    private static HttpResponse<String> send(HttpRequest request, LlmRequest prompt, URI endpoint) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmException("The LLM server at " + endpoint + " did not finish within "
                    + prompt.timeoutSeconds() + "s. A model that isn't loaded yet is read from disk on the"
                    + " first prompt, which can take minutes - raise Timeout, or prompt it once to warm it up.", e);
        } catch (ConnectException e) {
            throw new LlmException("Nothing is listening at " + endpoint
                    + ". Start the model server (`ollama serve`, or whatever serves the address above)"
                    + " and check the Server input.", e);
        } catch (IOException e) {
            throw new LlmException("Could not reach the LLM server at " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            // The engine interrupts process() to cancel a run or to enforce a node timeout. Restore
            // the flag so anything above this still sees a cancelled thread, then fail this pass.
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting for the LLM server at " + endpoint + ".", e);
        }
    }

    /**
     * What the server said went wrong, dug out of an error body. Both protocols answer with an
     * {@code error} field but disagree on its shape — Ollama makes it a string, OpenAI an object
     * with a {@code message} — and a server that fell over answers with neither, so an
     * unrecognisable body is quoted rather than swallowed. Package-private: it is worth testing
     * against the bodies real servers send, but it is not part of this library's surface.
     */
    static String errorFrom(String body) {
        try {
            JSONObject json = new JSONObject(body == null ? "" : body);
            Object error = json.opt("error");
            if (error instanceof JSONObject object && object.has("message")) {
                return object.optString("message", LlmApi.excerpt(body));
            }
            if (error instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (JSONException e) {
            // Not JSON at all - an HTML error page from something else listening on that port,
            // most likely. The excerpt below says more than "unparseable" would.
        }
        return LlmApi.excerpt(body);
    }
}
