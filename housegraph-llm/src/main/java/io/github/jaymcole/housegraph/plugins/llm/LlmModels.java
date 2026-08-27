package io.github.jaymcole.housegraph.plugins.llm;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
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
import java.util.List;

/**
 * The two questions that come <em>before</em> a prompt: is the server up, and does it have the
 * model. Both are answered over the same plain HTTP {@link LocalLlmClient} uses, and neither
 * involves loading a model — which is what makes {@link #status} cheap enough to poll in a loop
 * while a server is starting.
 * <p>
 * <b>{@link #status} does not throw when the server is down.</b> That is the whole point of it:
 * it is what a Local LLM Server node polls while it waits for a freshly spawned server to come up,
 * and what an LLM Server Status node branches on. Everywhere else in this library an unreachable
 * server is an exception, because there is no useful answer to hand downstream; here "unreachable"
 * <em>is</em> the answer. A blank or unparseable address still throws — that is a graph that is
 * wrong, not a server that is down, and no amount of waiting fixes it.
 * <p>
 * <b>{@link #pull} is Ollama's alone.</b> Ollama has a model registry and an API to fetch from it;
 * llama.cpp, LM Studio and vLLM are pointed at a file or a Hugging Face id that somebody put on
 * the disk themselves, and have no equivalent endpoint to call. Rather than pretend otherwise, the
 * method takes no {@link LlmApi} and the node above it says plainly which servers it works with.
 * <p>
 * <b>Nothing here retries</b>, for {@link LocalLlmClient}'s reason: a caller that wants another
 * attempt is better placed to decide when.
 */
public final class LlmModels {

    private static final Logger log = Log.get(LlmModels.class);

    /**
     * How long one status check waits. Short: it asks a server on this machine to list what it
     * already has in memory, so an answer that has not arrived in five seconds is not coming, and
     * a caller polling this while a server starts wants to go round the loop again rather than
     * hang on one probe.
     */
    public static final int DEFAULT_STATUS_TIMEOUT_SECONDS = 5;

    /**
     * How long a pull waits: an hour. A model is gigabytes over whatever connection the machine
     * has, and there is no useful smaller number — a 20-minute timeout on a 40-minute download
     * throws away the 20 minutes it already spent.
     */
    public static final int DEFAULT_PULL_TIMEOUT_SECONDS = 3600;

    /**
     * Shared, and separate from {@link LocalLlmClient}'s only in that it is declared here; both
     * pool connections to the same local server. The connect timeout is short because the answer
     * this class most often wants is "nothing is listening", and that answer should come back at
     * once rather than wait out the request timeout.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private LlmModels() {
    }

    /**
     * Asks a server which models it has, which doubles as asking whether it is up at all.
     *
     * @param api            the protocol the server speaks; null means {@link LlmApi#OLLAMA}
     * @param server         the server's address, e.g. {@code http://localhost:11434}
     * @param apiKey         an optional bearer token; null or blank sends no Authorization header
     * @param timeoutSeconds how long to wait, clamped to at least one second
     * @return what was found; never null, and never throws merely because the server is down
     * @throws LlmException if the address is blank or is not a usable URL
     */
    public static LlmServerStatus status(LlmApi api, String server, String apiKey, int timeoutSeconds) {
        LlmApi protocol = api == null ? LlmApi.OLLAMA : api;
        URI endpoint = protocol.modelsEndpoint(server);
        HttpRequest request = get(endpoint, apiKey, timeoutSeconds);

        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            return LlmServerStatus.down("Nothing is listening at " + endpoint + ".");
        } catch (HttpTimeoutException e) {
            return LlmServerStatus.down("The server at " + endpoint + " did not answer within "
                    + Math.max(1, timeoutSeconds) + "s.");
        } catch (IOException e) {
            return LlmServerStatus.down("Could not reach " + endpoint + ": " + e.getMessage());
        } catch (InterruptedException e) {
            // The engine interrupts process() to cancel a run. Restore the flag so anything above
            // still sees a cancelled thread, and report honestly rather than claiming "down".
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while checking the LLM server at " + endpoint + ".", e);
        }

        if (response.statusCode() / 100 != 2) {
            return LlmServerStatus.down("The server at " + endpoint + " answered HTTP "
                    + response.statusCode() + ": " + LocalLlmClient.errorFrom(response.body()));
        }
        try {
            List<String> models = protocol.modelsFrom(response.body());
            return LlmServerStatus.up(models, describe(models));
        } catch (LlmException e) {
            // Something is listening, but it is not this protocol - a web server on the port, or the
            // API setting pointing at the wrong one. Reporting it as "down" with the reason attached
            // is more useful to a node that is about to spawn a server than an exception would be,
            // and stops a start() from adopting a stranger as though it were the model server.
            return LlmServerStatus.down(e.getMessage());
        }
    }

    /**
     * Makes sure an Ollama server has a model, fetching it if it does not.
     * <p>
     * <b>It checks before it pulls.</b> Ollama's own {@code /api/pull} is happy to be called for a
     * model that is already there and returns quickly, but "quickly" is still a round trip that
     * re-verifies every layer's digest, and the caller wants to know which of the two happened —
     * a graph wiring "the model was just downloaded" to a notification should not fire it on every
     * poll. So the model list is read first and the answer says which way it went.
     * <p>
     * <b>The pull is not streamed.</b> Ollama streams progress by default, one JSON object per
     * line; this asks for the single final object instead, because there is no port a percentage
     * could go out of. What that costs is a node that sits there for minutes with nothing to show
     * — which is what the {@code [llm]} lines in HouseGraph's log are for if the server is one this
     * library started.
     *
     * @param server         the Ollama server's address, e.g. {@code http://localhost:11434}
     * @param model          the model to ensure is present, as Ollama names it, e.g. {@code llama3.2}
     * @param apiKey         an optional bearer token; null or blank sends no Authorization header
     * @param timeoutSeconds how long to wait for the download, clamped to at least one second
     * @return true if this call downloaded the model, false if it was already there
     * @throws LlmException if the model is not named, the server can't be reached, or it refused
     */
    public static boolean pull(String server, String model, String apiKey, int timeoutSeconds) {
        String wanted = model == null ? "" : model.trim();
        if (wanted.isEmpty()) {
            throw new LlmException("No model named. Set Model to one Ollama can fetch, e.g. "
                    + LocalLlmClient.DEFAULT_MODEL + ".");
        }
        String root = LlmApi.OLLAMA.root(server);
        LlmServerStatus before = status(LlmApi.OLLAMA, server, apiKey, DEFAULT_STATUS_TIMEOUT_SECONDS);
        if (!before.running()) {
            throw new LlmException("Cannot pull \"" + wanted + "\": " + before.detail()
                    + " Start the model server first - a Local LLM Server node's Ready port is the"
                    + " place to wire this from.");
        }
        if (before.has(wanted)) {
            log.debug("{} already has {}; not pulling it", root, wanted);
            return false;
        }

        URI endpoint = URI.create(root + "/api/pull");
        String body = new JSONObject().put("model", wanted).put("stream", false).toString();
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        addKey(builder, apiKey);

        log.info("Pulling {} from {} - this downloads gigabytes and can take a while", wanted, root);
        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmException("Pulling \"" + wanted + "\" did not finish within "
                    + Math.max(1, timeoutSeconds) + "s. A model is gigabytes over whatever connection this"
                    + " machine has - raise Timeout, or fetch it once with `ollama pull " + wanted + "`.", e);
        } catch (ConnectException e) {
            throw new LlmException("Nothing is listening at " + endpoint
                    + ". Start the model server and check the Server input.", e);
        } catch (IOException e) {
            throw new LlmException("Could not reach the Ollama server at " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while pulling \"" + wanted + "\" from " + endpoint + ".", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new LlmException("Ollama at " + endpoint + " would not pull \"" + wanted + "\": HTTP "
                    + response.statusCode() + " - " + LocalLlmClient.errorFrom(response.body()));
        }
        requireSuccess(wanted, endpoint, response.body());
        log.info("Pulled {} in {} s", wanted, (System.nanoTime() - startedAt) / 1_000_000_000L);
        return true;
    }

    /**
     * Ollama answers a completed pull with HTTP 200 either way and puts the outcome in the body —
     * {@code {"status":"success"}} when it worked, an {@code error} field when the model does not
     * exist in the registry. Reading only the status code would report a typo'd model name as a
     * successful download and leave the failure to surface as "model not found" on the prompt node
     * some minutes later.
     */
    private static void requireSuccess(String model, URI endpoint, String body) {
        JSONObject json;
        try {
            json = new JSONObject(body == null ? "" : body);
        } catch (org.json.JSONException e) {
            throw new LlmException("Ollama at " + endpoint + " did not answer the pull of \"" + model
                    + "\" with JSON. It said: " + LlmApi.excerpt(body), e);
        }
        if (json.has("error")) {
            throw new LlmException("Ollama would not pull \"" + model + "\": "
                    + LocalLlmClient.errorFrom(body));
        }
        String status = json.optString("status", "");
        if (!status.isEmpty() && !"success".equalsIgnoreCase(status)) {
            throw new LlmException("Ollama did not finish pulling \"" + model + "\"; it last said \""
                    + status + "\".");
        }
    }

    /** The one-line summary a status carries when the server answered. */
    private static String describe(List<String> models) {
        if (models.isEmpty()) {
            return "Running, with no models installed yet.";
        }
        return "Running, with " + models.size() + (models.size() == 1 ? " model: " : " models: ")
                + String.join(", ", models) + ".";
    }

    private static HttpRequest get(URI endpoint, String apiKey, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Accept", "application/json")
                .GET();
        addKey(builder, apiKey);
        return builder.build();
    }

    private static void addKey(HttpRequest.Builder builder, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
    }
}
