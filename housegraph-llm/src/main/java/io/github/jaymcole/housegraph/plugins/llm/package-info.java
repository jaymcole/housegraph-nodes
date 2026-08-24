/**
 * Talking to a language model that is running on the same machine — the wire format, the URL
 * building and the error mapping — kept out of the
 * {@link io.github.jaymcole.housegraph.plugins.llm.nodes nodes} so all of it is testable as plain
 * functions against a stub HTTP server, with no graph and no JavaFX.
 * <p>
 * <b>Local means two protocols, not one.</b> Ollama has its own {@code /api/generate} shape;
 * llama.cpp's server, LM Studio, vLLM and text-generation-webui all speak OpenAI's
 * {@code /v1/chat/completions} instead. Both are one POST and one JSON reply, so
 * {@link io.github.jaymcole.housegraph.plugins.llm.LlmApi} holds what differs (the path, the
 * request body, where the text is in the answer) and
 * {@link io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient} holds what does not.
 * <p>
 * <b>Nothing here streams.</b> Every request asks for the whole answer in one response
 * ({@code "stream": false}), because a node hands downstream nodes a finished string — there is
 * no port that a half-written token could go out of. That is also why a slow model looks like a
 * slow node rather than a partial one, and why the timeout is worth setting.
 * <p>
 * <b>No API key is required to be useful.</b> A local server usually has no auth at all; the key
 * is sent only when one is given, for a llama.cpp server started with {@code --api-key}. It is
 * never logged and never put in an error message.
 */
package io.github.jaymcole.housegraph.plugins.llm;
