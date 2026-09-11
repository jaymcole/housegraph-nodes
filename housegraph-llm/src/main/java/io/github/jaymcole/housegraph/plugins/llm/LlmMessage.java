package io.github.jaymcole.housegraph.plugins.llm;

/**
 * One turn of a conversation: who said it, and what they said.
 * <p>
 * <b>The roles are the two both protocols agree on</b> — {@code user} and {@code assistant} — and
 * they are the wire words rather than an enum, because that is what goes into the JSON either way
 * (see {@link LlmApi}). A system prompt is deliberately <em>not</em> one of these: it is not part
 * of what was said, it is the standing instruction, and it is re-sent from the node's own input on
 * every run so that editing it changes the next answer instead of being frozen into the first turn.
 *
 * @param role    {@link #USER} or {@link #ASSISTANT}
 * @param content what was said; never null, and may be empty when the model answered with nothing
 */
public record LlmMessage(String role, String content) {

    /** The person's half of an exchange. */
    public static final String USER = "user";

    /** The model's half of an exchange. */
    public static final String ASSISTANT = "assistant";

    public LlmMessage {
        if (role == null || role.isBlank()) {
            throw new LlmException("A conversation turn needs a role.");
        }
        content = content == null ? "" : content;
    }

    /** One turn said by the person. */
    public static LlmMessage user(String content) {
        return new LlmMessage(USER, content);
    }

    /** One turn said by the model. */
    public static LlmMessage assistant(String content) {
        return new LlmMessage(ASSISTANT, content);
    }
}
