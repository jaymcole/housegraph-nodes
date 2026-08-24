package io.github.jaymcole.housegraph.plugins.llm;

/**
 * One prompt, and everything needed to send it: which protocol the server speaks, where it is,
 * which model to ask, and the optional settings around it.
 * <p>
 * <b>The validation lives here rather than in the node</b>, so "what a usable request looks like"
 * is one thing that can be tested without a graph, and so the same rules apply however this
 * library is driven. All three of the required parts fail with a message naming the input that is
 * empty — an unwired Prompt would otherwise reach the model as the empty string and come back with
 * whatever a model says when asked nothing at all.
 *
 * @param api            the protocol the server speaks; null means {@link LlmApi#OLLAMA}
 * @param server         the server's address, e.g. {@code http://localhost:11434}
 * @param model          the model to prompt, as the server names it, e.g. {@code llama3.2}
 * @param system         an optional system prompt; null or blank sends none
 * @param prompt         what to ask
 * @param temperature    an optional sampling temperature; null leaves the server's default alone
 * @param apiKey         an optional bearer token; null or blank sends no Authorization header
 * @param timeoutSeconds how long to wait for the whole answer, clamped to at least one second
 */
public record LlmRequest(LlmApi api, String server, String model, String system, String prompt,
                         Float temperature, String apiKey, int timeoutSeconds) {

    public LlmRequest {
        api = api == null ? LlmApi.OLLAMA : api;
        server = server == null ? "" : server.trim();
        model = model == null ? "" : model.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (server.isEmpty()) {
            throw new LlmException("No LLM server address given (e.g. " + LocalLlmClient.DEFAULT_SERVER + ").");
        }
        if (model.isEmpty()) {
            throw new LlmException("No model named. Set Model to one the server has, e.g. "
                    + LocalLlmClient.DEFAULT_MODEL + " (`ollama list` shows what is installed).");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new LlmException("Nothing to send: the Prompt input is empty.");
        }
        timeoutSeconds = Math.max(1, timeoutSeconds);
    }
}
