package io.github.jaymcole.housegraph.plugins.alpaca;

/**
 * Something this library could not do, with a sentence saying why.
 * <p>
 * Unchecked on purpose. A node reports failure by throwing out of {@code process()} — the engine
 * fires the node's {@code Error} port, halts the branch, and puts this message on
 * {@code Error Message} — so every failure in this library is a throw, and the message is the whole
 * of what a person or a downstream handler gets. Every one of them is written to be read by
 * somebody who is looking at a graph rather than at this code: which field is wrong, or what Alpaca
 * said back.
 */
public class AlpacaException extends RuntimeException {

    public AlpacaException(String message) {
        super(message);
    }

    public AlpacaException(String message, Throwable cause) {
        super(message, cause);
    }
}
