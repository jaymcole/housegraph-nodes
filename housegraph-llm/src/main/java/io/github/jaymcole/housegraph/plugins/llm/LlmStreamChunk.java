package io.github.jaymcole.housegraph.plugins.llm;

/**
 * One piece of a streamed answer, as it came off the wire — the new answer text, the new reasoning
 * text, and whether the server says that was the last of it.
 * <p>
 * <b>Both protocols stream deltas, not snapshots.</b> A chunk carries only what was generated since
 * the one before it, so a reader appends rather than replaces; {@link LlmProgress} is what does
 * that appending for a caller that wants the answer so far.
 * <p>
 * <b>Reasoning and answer are separate fields because the servers keep them separate.</b> A
 * thinking model asked to think puts its reasoning in {@code thinking} (Ollama) or
 * {@code reasoning_content} (llama.cpp, vLLM) and its answer in the ordinary content field, and a
 * chunk carries one or the other — never both. Merging them here would throw away the only signal
 * that says which phase the model is in, which is the thing a progress display most wants to know.
 * <p>
 * <b>A chunk with nothing in it is normal</b> and not an error: the final chunk of an Ollama stream
 * carries {@code done} and the run's timings with an empty {@code response}, and an SSE stream ends
 * with a chunk that has a finish reason and no text.
 *
 * @param content  new answer text; never null, often {@code ""}
 * @param thinking new reasoning text; never null, and always {@code ""} for a model that isn't thinking
 * @param done     whether the server says the answer is complete
 */
public record LlmStreamChunk(String content, String thinking, boolean done) {

    public LlmStreamChunk {
        content = content == null ? "" : content;
        thinking = thinking == null ? "" : thinking;
    }

    /**
     * The end of a stream, carrying no text — what {@code data: [DONE]} and a finish reason mean.
     * Named {@code end} rather than {@code done} because a record may not have a static method
     * sharing a component's name.
     */
    public static LlmStreamChunk end() {
        return new LlmStreamChunk("", "", true);
    }

    /** Whether this chunk carries no text at all, in either field. */
    public boolean isEmpty() {
        return content.isEmpty() && thinking.isEmpty();
    }
}
