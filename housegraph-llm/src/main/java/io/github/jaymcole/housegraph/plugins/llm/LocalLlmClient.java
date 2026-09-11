package io.github.jaymcole.housegraph.plugins.llm;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 * <p>
 * <b>{@link #generate} and {@link #stream} are the same call asked for two ways.</b> The first
 * waits for the whole answer and hands it back; the second reads it as the model writes it and
 * hands each piece to a caller that wants to show progress. They are kept as separate methods
 * rather than one with a flag because the failure handling differs in the one way that matters:
 * a streamed answer has already had HTTP 200 and half its text delivered by the time a server can
 * fail, so a failure there is not simply "the call did not work".
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

    /**
     * Closes a streamed response that has run out of time - see {@link #stream}. One daemon thread
     * for the whole library: it does nothing but fire a close at a deadline, and a scheduled close
     * that a finished answer has already cancelled costs nothing at all.
     */
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "llm-stream-timeout");
                thread.setDaemon(true);
                return thread;
            });

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
        return answer(request).response();
    }

    /**
     * {@link #generate} with the model's reasoning as well as its answer - what a caller that set
     * a thinking option wants back, since a model told to think puts its reasoning in a field of
     * its own rather than in the reply.
     *
     * @param request what to ask, and where
     * @return the answer and the reasoning, neither ever null
     * @throws LlmException if the server can't be reached, rejects the request, runs out of time,
     *                      or answers with something other than the selected API's reply shape
     */
    public static LlmAnswer answer(LlmRequest request) {
        LlmApi api = request.api();
        URI endpoint = api.endpoint(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(request.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(api.requestBody(request), StandardCharsets.UTF_8));
        if (!request.apiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }

        long startedAt = System.nanoTime();
        HttpResponse<String> response = send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), request, endpoint);
        if (response.statusCode() / 100 != 2) {
            throw new LlmException("The LLM server at " + endpoint + " answered HTTP " + response.statusCode()
                    + ": " + errorFrom(response.body()));
        }
        String reply = api.replyFrom(response.body());
        // The prompt and the answer are the user's, and can be long: log the shape of the call, not
        // its content. That is enough to tell "the model is slow" from "the node never ran".
        log.debug("{} answered {} in {} ms with {} characters, after {} remembered exchanges",
                endpoint, request.model(), (System.nanoTime() - startedAt) / 1_000_000L, reply.length(),
                request.history().size() / 2);
        return new LlmAnswer(reply, api.thinkingFrom(response.body()));
    }

    /**
     * Sends one prompt and reads the answer as the model writes it, handing each piece to
     * {@code onChunk} and appending it to {@code progress}.
     * <p>
     * <b>It blocks for as long as the whole answer takes</b>, exactly as {@link #generate} does.
     * The difference is that a caller is told what has arrived while it waits, which is what makes
     * a progress display possible at all; {@code onChunk} runs on the calling thread, in order, so
     * a caller that fires a branch of a graph from it does so with no concurrency to reason about.
     * <b>An exception thrown by {@code onChunk} ends the call</b> and propagates - that is how a
     * cancelled run stops a generation part-way, which the non-streaming call cannot do.
     * <p>
     * <b>The timeout is enforced here rather than left to the HTTP client.</b> A request timeout
     * covers the arrival of a response, and a streamed response arrives the instant the model
     * begins - so a server that sent two words and then wedged would hold the call open forever,
     * losing the one promise {@link #DEFAULT_TIMEOUT_SECONDS} makes. A watchdog closes the body
     * when the deadline passes, which unblocks the read, and the failure is reported as the
     * timeout it is.
     *
     * @param request  what to ask, and where; its {@link LlmRequest#streaming()} must be true
     * @param progress accumulates the pieces; also what the caller reads the finished answer from
     * @param onChunk  called once per piece of the answer, on the calling thread, in order
     * @throws LlmException if the server can't be reached, rejects the request, runs out of time,
     *                      fails part-way through the answer, or answers in the other shape
     */
    public static void stream(LlmRequest request, LlmProgress progress, Consumer<LlmStreamChunk> onChunk) {
        LlmApi api = request.api();
        URI endpoint = api.endpoint(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(request.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(api.requestBody(request), StandardCharsets.UTF_8));
        if (!request.apiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }

        long startedAt = System.nanoTime();
        HttpResponse<InputStream> response = send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream(), request, endpoint);
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                // An error is answered whole, not streamed - read it and say what it said.
                throw new LlmException("The LLM server at " + endpoint + " answered HTTP "
                        + response.statusCode() + ": "
                        + errorFrom(new String(body.readAllBytes(), StandardCharsets.UTF_8)));
            }
            read(body, api, progress, onChunk, request, endpoint, startedAt);
        } catch (IOException e) {
            throw new LlmException("Could not read the answer from the LLM server at " + endpoint
                    + ": " + e.getMessage(), e);
        }
        // As in generate(): the prompt and the answer are the user's. Log the shape, not the text.
        log.debug("{} streamed {} in {} ms, {} characters of answer after {} of reasoning",
                endpoint, request.model(), (System.nanoTime() - startedAt) / 1_000_000L,
                progress.answer().length(), progress.thinking().length());
    }

    /**
     * Reads the response body a line at a time until the server says the answer is done or the
     * body ends, under a watchdog that closes it if the timeout passes - see {@link #stream}.
     * <p>
     * <b>A body that ends without a done marker is not a failure.</b> Both protocols close the
     * connection after the last chunk, and a server that closed it without saying {@code done}
     * first has still delivered everything it is going to; the answer already read is the answer.
     */
    private static void read(InputStream body, LlmApi api, LlmProgress progress,
                             Consumer<LlmStreamChunk> onChunk, LlmRequest request, URI endpoint,
                             long startedAt) throws IOException {
        long deadline = startedAt + TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> closeQuietly(body),
                Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LlmStreamChunk chunk = api.chunkFrom(line);
                if (chunk == null) {
                    continue;
                }
                progress.add(chunk);
                onChunk.accept(chunk);
                if (chunk.done()) {
                    return;
                }
            }
        } catch (IOException e) {
            if (System.nanoTime() >= deadline) {
                throw new LlmException("The LLM server at " + endpoint + " stopped part-way through"
                        + " the answer and did not finish within " + request.timeoutSeconds() + "s."
                        + " A model that isn't loaded yet is read from disk on the first prompt,"
                        + " which can take minutes - raise Timeout, or prompt it once to warm it up.", e);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
    }

    /** Closes the body to unblock a read that has run out of time; anything it throws is moot by then. */
    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException e) {
            log.debug("Closing a timed-out LLM response failed, which changes nothing: {}", e.getMessage());
        }
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                            LlmRequest prompt, URI endpoint) {
        try {
            return CLIENT.send(request, handler);
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
