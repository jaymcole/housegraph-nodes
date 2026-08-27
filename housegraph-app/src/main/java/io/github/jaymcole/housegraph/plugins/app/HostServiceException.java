package io.github.jaymcole.housegraph.plugins.app;

/**
 * Raised for anything that stops a request to the hosting application from coming back with an
 * answer: no such service published, a service that threw, or a reply that isn't the shape the
 * contract says.
 * <p>
 * The message is written to be read on the canvas, and it distinguishes the failure that is
 * really a fact about the running application — "this build doesn't offer that" — from the ones
 * that are a fault, because the first is the common case and the fix for it is entirely different.
 */
public class HostServiceException extends RuntimeException {

    /**
     * @param message what went wrong, safe to show on the canvas
     */
    public HostServiceException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong, safe to show on the canvas
     * @param cause   the underlying failure
     */
    public HostServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
