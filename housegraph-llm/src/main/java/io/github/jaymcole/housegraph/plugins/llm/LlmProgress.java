package io.github.jaymcole.housegraph.plugins.llm;

import java.util.concurrent.TimeUnit;

/**
 * Turns a stream of {@link LlmStreamChunk}s into the occasional progress update — the accumulating
 * and the throttling behind the prompt node's <b>Update</b> flow output.
 * <p>
 * <b>Why a model's tokens cannot go straight out of a flow port.</b> A local model emits tokens
 * several times a second, and every update fires a whole branch of the graph — for the case this
 * was built for, an edit to a Discord message, which is a network round trip Discord rate limits.
 * Firing per token would spend the entire generation queued behind edits it will overwrite a moment
 * later. So chunks are accumulated here and handed out at most once per interval, and the update
 * carries the <em>newest</em> state rather than the one that was current when the interval began:
 * a slow consumer coalesces to the latest instead of falling behind by a growing backlog.
 * <p>
 * <b>An interval that has elapsed with nothing new is not an update.</b> {@link #tick} answers null
 * for it and leaves the clock where it was, so the next chunk carrying text publishes at once
 * rather than waiting out another interval — and a consumer is never handed the same text twice.
 * <p>
 * <b>A change of phase does not wait for the interval.</b> "The model has stopped reasoning and
 * started answering" is the one thing a progress display most wants to be told promptly, and left
 * to the clock it is not merely late but <em>unreliable</em>: a model whose answer arrives less
 * than an interval after its last reasoning would finish without ever publishing an answering
 * update at all, and a graph watching the phase would never see it change. There is at most one
 * such transition in a run, so waiving the interval for it costs one extra update at most. The
 * <em>first</em> update of a run still waits, since there is no phase to have changed from.
 * <p>
 * <b>The phase is derived, not tracked</b>: a model is {@linkplain LlmPhase#THINKING thinking}
 * until the first answer text arrives and {@linkplain LlmPhase#ANSWERING answering} from then on.
 * That makes a model which never thinks — which is most of them — {@code ANSWERING} from its first
 * chunk with no special case anywhere.
 * <p>
 * <b>Not thread safe</b>, and it does not need to be: one streamed answer is read by one thread,
 * which is the thread inside the node's {@code process()}.
 */
public final class LlmProgress {

    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder answer = new StringBuilder();

    /** How much of each has already gone out in an update, so {@link LlmUpdate#newText} is the rest. */
    private int publishedThinking;
    private int publishedAnswer;

    private long lastTickNanos;

    /** The phase of the last update published, or null before there has been one — see {@link #tick}. */
    private LlmPhase publishedPhase;

    /**
     * @param startedAtNanos the reading of {@link System#nanoTime()} the stream began at — the
     *                       first update is due one interval after this, not immediately, so a
     *                       fast first token doesn't publish an update a graph will overwrite
     *                       milliseconds later
     */
    public LlmProgress(long startedAtNanos) {
        this.lastTickNanos = startedAtNanos;
    }

    /** Appends a chunk's text. Every chunk is added, including the ones no update is due for. */
    public void add(LlmStreamChunk chunk) {
        thinking.append(chunk.thinking());
        answer.append(chunk.content());
    }

    /**
     * The update to publish now, or null if there is nothing to publish.
     *
     * @param nowNanos     the current {@link System#nanoTime()}
     * @param everyMillis  the shortest gap between two updates, in milliseconds
     * @return the snapshot to publish, or null if the interval has not elapsed or nothing has been
     *         added since the last one
     */
    public LlmUpdate tick(long nowNanos, int everyMillis) {
        LlmPhase phase = phase();
        boolean changedPhase = publishedPhase != null && phase != publishedPhase;
        if (!changedPhase && nowNanos - lastTickNanos < TimeUnit.MILLISECONDS.toNanos(Math.max(1, everyMillis))) {
            return null;
        }
        // The text of whichever phase is current. At the tick where reasoning gave way to an
        // answer, that is the new answer text: the tail of the reasoning is skipped here but is
        // still carried whole on the update's thinking field, so nothing is lost.
        String newText = phase == LlmPhase.ANSWERING
                ? answer.substring(publishedAnswer)
                : thinking.substring(publishedThinking);
        if (newText.isEmpty()) {
            // Deliberately without moving the clock - see the class documentation.
            return null;
        }
        lastTickNanos = nowNanos;
        publishedPhase = phase;
        publishedThinking = thinking.length();
        publishedAnswer = answer.length();
        return new LlmUpdate(phase, thinking.toString(), answer.toString(), newText);
    }

    /** Which half of its work the model is in, from what has arrived so far. */
    public LlmPhase phase() {
        return answer.length() == 0 ? LlmPhase.THINKING : LlmPhase.ANSWERING;
    }

    /** Everything the model has answered so far; the complete answer once the stream has ended. */
    public String answer() {
        return answer.toString();
    }

    /** Everything the model has reasoned so far; {@code ""} for a model that isn't thinking. */
    public String thinking() {
        return thinking.toString();
    }
}
