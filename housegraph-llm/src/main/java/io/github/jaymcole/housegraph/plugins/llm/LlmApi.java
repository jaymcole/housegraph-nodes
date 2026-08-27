package io.github.jaymcole.housegraph.plugins.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two shapes a locally running LLM answers to, and everything that differs between them: the
 * path to POST to, the request body, where in the reply the generated text is, and how to ask the
 * server which models it has.
 * <p>
 * <b>{@link #OLLAMA}</b> is Ollama's own {@code /api/generate}: model, prompt and an optional
 * system prompt as top-level fields, sampling settings under {@code options}, and the answer in
 * {@code response}.
 * <p>
 * <b>{@link #OPENAI}</b> is OpenAI's {@code /v1/chat/completions}, which is what llama.cpp's
 * server, LM Studio, vLLM, LocalAI and text-generation-webui all expose. The prompt becomes a
 * one-turn conversation ({@code system} then {@code user}), and the answer is
 * {@code choices[0].message.content}. Ollama serves this endpoint too, so it is also the way to
 * point one graph at either server without rewiring.
 * <p>
 * <b>Which one to use is authored as text</b>, not picked from a dropdown, for the reason the Text
 * library spells out: only {@code String}, {@code Integer} and {@code Float} have registered value
 * editors, so text is what a user can actually type into a node. {@link #parse} accepts the name of
 * the <em>server</em> as well as the name of the protocol — "lm studio" and "llama.cpp" both select
 * {@link #OPENAI} — because the server is what someone actually installed and knows the name of.
 */
public enum LlmApi {

    /** Ollama's native {@code /api/generate}. The default: it is what "run an LLM locally" usually means. */
    OLLAMA("ollama", List.of("ollama")),

    /** OpenAI's {@code /v1/chat/completions}, as served by llama.cpp, LM Studio, vLLM, LocalAI and friends. */
    OPENAI("openai", List.of("openai", "openaicompatible", "compatible", "llamacpp", "llama.cpp",
            "lmstudio", "vllm", "localai", "textgenerationwebui", "oobabooga"));

    private final String label;
    private final List<String> aliases;

    LlmApi(String label, List<String> aliases) {
        this.label = label;
        this.aliases = aliases;
    }

    /** @return the label a user types to select this API. */
    public String label() {
        return label;
    }

    /**
     * Parses an authored API. Blank text selects {@link #OLLAMA} — the field left alone should
     * mean the common case — and an unrecognised one <b>throws</b> rather than falling back,
     * because a silent fallback would POST an Ollama body at an OpenAI server for the life of the
     * graph and report only the confusing answer that came back.
     * <p>
     * Matching ignores case, spaces, underscores and hyphens, so "OpenAI", "open-ai" and
     * "open ai" all name the same API.
     *
     * @param text the authored API, possibly null or blank
     * @return the selected API
     * @throws LlmException if the text names no known API
     */
    public static LlmApi parse(String text) {
        if (text == null || text.isBlank()) {
            return OLLAMA;
        }
        String normalised = normalise(text);
        for (LlmApi api : values()) {
            for (String alias : api.aliases) {
                if (normalise(alias).equals(normalised)) {
                    return api;
                }
            }
        }
        throw new LlmException("Unknown LLM API \"" + text + "\" - expected " + labels()
                + " (or the name of the server, e.g. \"lm studio\", \"llama.cpp\", \"vllm\").");
    }

    /** @return every API's label, comma separated — for the failure message and the node's docs. */
    public static String labels() {
        StringBuilder text = new StringBuilder();
        for (LlmApi api : values()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(api.label);
        }
        return text.toString();
    }

    private static String normalise(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    /**
     * The URL to POST to, from whatever was typed as the server.
     * <p>
     * <b>A bare host works</b>: no scheme means {@code http://}, since a local model server is
     * almost never behind TLS. <b>An address that already names the endpoint is left alone</b>,
     * and so is one that stops at {@code /v1} — people copy the base URL out of their server's
     * own documentation, and having the node paste a second {@code /v1} onto it would turn a
     * correct address into a 404.
     *
     * @param server the authored server address
     * @return the endpoint to POST a prompt to
     * @throws LlmException if the server is blank or is not a usable address
     */
    public URI endpoint(String server) {
        String base = baseUrl(server);
        return uri(server, base + pathToAppend(base.toLowerCase(Locale.ROOT)));
    }

    /**
     * The URL to GET for the list of models this server has, and the readiness check that comes
     * with it: {@code /api/tags} for Ollama, {@code /v1/models} for an OpenAI-compatible server.
     * <p>
     * <b>Both are cheap and neither loads a model</b>, which is what makes them the right way to
     * ask "is the server up?" — a question the server nodes ask in a loop while one is starting.
     * A TCP connect would be cheaper still and would answer the wrong question: a port comes up
     * the instant the process binds it, seconds before the API behind it will answer anything, and
     * a port bound by something else entirely answers just as readily.
     * <p>
     * <b>A Server that names the prompt endpoint is understood</b>, not appended to. People paste
     * whatever address they already had working into the Server field, and for the
     * {@link #endpoint} that is harmless — it is left alone. Here it would produce
     * {@code /api/generate/api/tags}, so the prompt path is stripped back off first.
     *
     * @param server the authored server address
     * @return the endpoint to GET the installed models from
     * @throws LlmException if the server is blank or is not a usable address
     */
    public URI modelsEndpoint(String server) {
        String root = root(server);
        return uri(server, root + modelsPath(root.toLowerCase(Locale.ROOT)));
    }

    /**
     * The names of the models the server reports, out of a {@link #modelsEndpoint} reply — Ollama's
     * {@code models[].name}, OpenAI's {@code data[].id}.
     * <p>
     * <b>A server with no models is not a failure</b> and gives an empty list: Ollama answers
     * exactly that on a fresh install, and "running, nothing pulled yet" is a state a graph should
     * be able to see and act on rather than an error. A reply <em>without</em> the field
     * <b>throws</b>, for {@link #replyFrom}'s reason: it is something other than this API on that
     * address, and saying so beats reporting "no models".
     *
     * @param responseBody the server's response body
     * @return the model names, in the order the server listed them; never null
     * @throws LlmException if the body is not this API's model-list shape
     */
    public List<String> modelsFrom(String responseBody) {
        JSONObject json = parseObject(responseBody);
        String field = this == OLLAMA ? "models" : "data";
        String nameField = this == OLLAMA ? "name" : "id";
        JSONArray entries = json.optJSONArray(field);
        if (entries == null) {
            throw new LlmException(missingField(field, responseBody));
        }
        List<String> names = new ArrayList<>(entries.length());
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            String name = entry == null ? null : entry.optString(nameField, null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * The server's own address, with any endpoint path the user typed stripped back off, so
     * another path can be hung on it. Package-private: {@link LlmModels} needs it to reach Ollama's
     * {@code /api/pull}, and {@code LlmServerProcess} to work out what to export as
     * {@code OLLAMA_HOST}; nothing outside this library should be building URLs by hand.
     *
     * @param server the authored server address
     * @return the root URL, with a scheme and no trailing slash
     * @throws LlmException if the server is blank or is not a usable address
     */
    String root(String server) {
        String base = baseUrl(server);
        String lower = base.toLowerCase(Locale.ROOT);
        String suffix = this == OLLAMA ? "/api/generate" : "/chat/completions";
        return lower.endsWith(suffix) ? base.substring(0, base.length() - suffix.length()) : base;
    }

    /**
     * An authored address as a URL: a scheme (plain {@code http://} unless one was typed, since a
     * local model server is almost never behind TLS) and no trailing slash.
     *
     * @param server the authored server address
     * @return the normalised base URL
     * @throws LlmException if the server is blank
     */
    private static String baseUrl(String server) {
        String base = server == null ? "" : server.trim();
        if (base.isEmpty()) {
            throw new LlmException("No LLM server address given (e.g. " + LocalLlmClient.DEFAULT_SERVER + ").");
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            base = "http://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** A built URL as a {@link URI}, blaming the address the user actually typed if it will not parse. */
    private static URI uri(String server, String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new LlmException("\"" + server + "\" is not a usable LLM server address: " + e.getMessage(), e);
        }
    }

    /** What is missing from an already-normalised base URL to make it this API's endpoint. */
    private String pathToAppend(String base) {
        return switch (this) {
            case OLLAMA -> base.endsWith("/api/generate") ? "" : "/api/generate";
            case OPENAI -> {
                if (base.endsWith("/chat/completions")) {
                    yield "";
                }
                yield base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
            }
        };
    }

    /** What to hang on an already-stripped root URL to make it this API's model list. */
    private String modelsPath(String root) {
        return switch (this) {
            case OLLAMA -> root.endsWith("/api/tags") ? "" : "/api/tags";
            case OPENAI -> {
                if (root.endsWith("/models")) {
                    yield "";
                }
                yield root.endsWith("/v1") ? "/models" : "/v1/models";
            }
        };
    }

    /**
     * This API's request body for one prompt.
     * <p>
     * Both bodies say {@code "stream": false}: Ollama streams by default, and a streamed answer
     * arrives as a run of newline-separated JSON objects that {@link #replyFrom} could not read.
     * A null or blank system prompt is left out entirely rather than sent as an empty string,
     * which some servers treat as "an empty system prompt" instead of "none", and a null
     * temperature is left out so the server's own default stands.
     *
     * @param request what to ask, and how
     * @return the JSON body to POST
     */
    public String requestBody(LlmRequest request) {
        JSONObject body = new JSONObject()
                .put("model", request.model())
                .put("stream", false);
        String system = request.system();
        Float temperature = request.temperature();
        switch (this) {
            case OLLAMA -> {
                body.put("prompt", request.prompt());
                if (system != null && !system.isBlank()) {
                    body.put("system", system);
                }
                if (temperature != null) {
                    body.put("options", new JSONObject().put("temperature", temperature.doubleValue()));
                }
            }
            case OPENAI -> {
                JSONArray messages = new JSONArray();
                if (system != null && !system.isBlank()) {
                    messages.put(new JSONObject().put("role", "system").put("content", system));
                }
                messages.put(new JSONObject().put("role", "user").put("content", request.prompt()));
                body.put("messages", messages);
                if (temperature != null) {
                    body.put("temperature", temperature.doubleValue());
                }
            }
        }
        return body.toString();
    }

    /**
     * The generated text out of a successful reply.
     * <p>
     * A model that answered with nothing gives {@code ""} — a text output in this repository is
     * never null — but a reply that does not have the field at all <b>throws</b>, because that is
     * not an empty answer: it is a server speaking a protocol other than the one selected, and
     * saying so beats handing an empty string downstream as though the model had shrugged.
     *
     * @param responseBody the server's response body
     * @return the generated text, never null
     * @throws LlmException if the body is not this API's reply shape
     */
    public String replyFrom(String responseBody) {
        JSONObject json = parseObject(responseBody);
        return switch (this) {
            case OLLAMA -> {
                if (!json.has("response")) {
                    throw new LlmException(missingField("response", responseBody));
                }
                yield json.optString("response", "");
            }
            case OPENAI -> {
                JSONArray choices = json.optJSONArray("choices");
                JSONObject first = choices == null || choices.isEmpty() ? null : choices.optJSONObject(0);
                JSONObject message = first == null ? null : first.optJSONObject("message");
                if (message == null || !message.has("content")) {
                    throw new LlmException(missingField("choices[0].message.content", responseBody));
                }
                yield message.optString("content", "");
            }
        };
    }

    private String missingField(String field, String responseBody) {
        return "The " + label + " server's reply has no " + field + " field, so it is not "
                + (this == OLLAMA ? "an Ollama" : "an OpenAI-compatible")
                + " answer - check the API setting matches the server. It said: " + excerpt(responseBody);
    }

    private static JSONObject parseObject(String responseBody) {
        try {
            return new JSONObject(responseBody == null ? "" : responseBody);
        } catch (JSONException e) {
            throw new LlmException("The LLM server did not answer with JSON. It said: "
                    + excerpt(responseBody), e);
        }
    }

    /**
     * A response body cut down to something that fits on a node. Kept short deliberately: an HTML
     * error page or a whole model dump would bury the one line that says what went wrong.
     */
    static String excerpt(String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            return "(nothing)";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
