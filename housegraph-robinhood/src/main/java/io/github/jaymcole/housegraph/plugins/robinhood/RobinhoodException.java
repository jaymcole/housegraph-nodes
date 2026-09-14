package io.github.jaymcole.housegraph.plugins.robinhood;

/**
 * Anything this library could not do, with a message written to be read off a node's status line.
 * <p>
 * <b>Every message here is meant to be shown to the person whose money it is</b>, so it says what
 * failed and — where Robinhood told us — why, in the words a person would use: "Robinhood rejected
 * the order: not enough buying power", not "HTTP 400". Nothing that reaches a message is ever a
 * password, a token, or an MFA code; see {@code RobinhoodHttp.describe}.
 */
public class RobinhoodException extends RuntimeException {

    public RobinhoodException(String message) {
        super(message);
    }

    public RobinhoodException(String message, Throwable cause) {
        super(message, cause);
    }
}
