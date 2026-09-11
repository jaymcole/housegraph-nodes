package io.github.jaymcole.housegraph.plugins.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * What one named conversation remembers: the exchanges so far, and when it was last used.
 * <p>
 * <b>An exchange is a pair</b> — what the person asked and what the model answered — and turns are
 * only ever added and dropped two at a time. A history cut in the middle of a pair would send a
 * question with no answer or an answer with no question, which reads to the model as something that
 * did not happen.
 * <p>
 * <b>Trimming keeps the most recent exchanges</b> and is applied when an exchange is recorded, so
 * what is held in memory is bounded by the same number that bounds what is sent. It is a proxy for
 * the real limit, which is the model's context window measured in tokens: there is no tokenizer in
 * this library to count those, so a long history against a small-window model still fails at the
 * server, with the server's own message.
 * <p>
 * <b>Every method is synchronized</b> on this object, and each one is a whole operation: the node
 * takes a snapshot, makes its HTTP call outside the lock — a prompt can take minutes, which is not
 * a lock anything should be waiting behind — and records the exchange afterwards. Two prompts in
 * flight on one conversation therefore both see the history as it was and both append; the
 * resulting order is last-writer, which is a fair description of two people talking at once rather
 * than a corruption.
 */
public final class LlmConversation {

    private final List<LlmMessage> turns = new ArrayList<>();

    private long lastUsedMillis;
    private long idleMillis;

    LlmConversation(long nowMillis) {
        this.lastUsedMillis = nowMillis;
    }

    /**
     * The most recent {@code exchanges} exchanges, oldest first, as a list safe to hold onto.
     *
     * @param exchanges how many exchanges to take; zero or less gives nothing
     * @return the trailing slice of the history, never null
     */
    public synchronized List<LlmMessage> history(int exchanges) {
        if (exchanges <= 0 || turns.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, turns.size() - exchanges * 2);
        return List.copyOf(turns.subList(from, turns.size()));
    }

    /**
     * Appends one exchange and trims back to {@code exchanges}.
     * <p>
     * Called only after a successful reply. A run that failed — a timeout, a server that is not
     * there, a model that is not installed — records nothing, so a retry does not ask the same
     * question twice and a failed turn does not sit in the context of every later one.
     *
     * @param prompt    what was asked
     * @param reply     what the model answered
     * @param exchanges how many exchanges to keep; zero or less keeps nothing
     */
    public synchronized void record(String prompt, String reply, int exchanges) {
        turns.add(LlmMessage.user(prompt));
        turns.add(LlmMessage.assistant(reply));
        int keep = Math.max(0, exchanges) * 2;
        while (turns.size() > keep) {
            turns.remove(0);
        }
    }

    /** How many exchanges this conversation is holding. */
    public synchronized int exchanges() {
        return turns.size() / 2;
    }

    /** Forgets everything said so far, leaving the conversation itself in place. */
    public synchronized void clear() {
        turns.clear();
    }

    /**
     * Records that this conversation was just used, and how long it may sit idle before
     * {@link LlmConversations} forgets it. The window travels with the conversation rather than
     * with the sweep, so a node with a short window does not evict a conversation another node is
     * holding open for longer.
     *
     * @param nowMillis  the current time
     * @param idleMillis how long this conversation may go untouched; zero or less means forever
     */
    synchronized void touch(long nowMillis, long idleMillis) {
        this.lastUsedMillis = nowMillis;
        this.idleMillis = idleMillis;
    }

    /** Whether this conversation has gone untouched for longer than its idle window allows. */
    synchronized boolean isExpired(long nowMillis) {
        return idleMillis > 0 && nowMillis - lastUsedMillis > idleMillis;
    }
}
