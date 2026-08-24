package io.github.jaymcole.housegraph.plugins.llm;

/**
 * Raised for anything that stops a prompt from coming back as text: no server listening, a model
 * the server does not have, a reply that is not the JSON this library knows how to read.
 * <p>
 * The message is written to be shown as-is on the node — it names the endpoint that was tried and,
 * where the server said something useful, quotes it. It never contains the API key or the prompt:
 * the first is a secret, and the second can be long enough to make the failure unreadable.
 */
public class LlmException extends RuntimeException {

    /**
     * @param message what went wrong, safe to show on the canvas
     */
    public LlmException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong, safe to show on the canvas
     * @param cause   the underlying failure
     */
    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
