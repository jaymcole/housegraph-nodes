package io.github.jaymcole.housegraph.plugins.llm;

/**
 * One throttled progress snapshot: everything the prompt node publishes when it fires its
 * <b>Update</b> flow output. Produced by {@link LlmProgress#tick}.
 * <p>
 * <b>It is a snapshot and a delta together</b>, because the two consumers want different things. A
 * Discord message being edited in place wants {@link #answer()} — the whole answer so far, since an
 * edit replaces the message body. A log, a console or a text-to-speech node wants
 * {@link #newText()} — only what is new, since it appends. Carrying both costs one string and
 * saves every graph from reconstructing the other.
 *
 * @param phase    which half of its work the model is in
 * @param thinking all reasoning so far; {@code ""} for a model that isn't thinking
 * @param answer   all answer text so far; {@code ""} while the model is still reasoning
 * @param newText  what was added since the last update — to the answer while
 *                 {@linkplain LlmPhase#ANSWERING answering}, to the reasoning while
 *                 {@linkplain LlmPhase#THINKING thinking}; never empty
 */
public record LlmUpdate(LlmPhase phase, String thinking, String answer, String newText) {
}
