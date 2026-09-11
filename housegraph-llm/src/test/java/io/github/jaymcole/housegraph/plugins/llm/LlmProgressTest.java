package io.github.jaymcole.housegraph.plugins.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The throttling behind the prompt node's Update port. Time is passed in rather than read, so
 * these are exact rather than a race against a sleeping test.
 */
class LlmProgressTest {

    private static final int EVERY = 1000;

    private static long millis(long count) {
        return TimeUnit.MILLISECONDS.toNanos(count);
    }

    @Test
    void nothingIsPublishedBeforeTheFirstIntervalHasPassed() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("Fr", "", false));

        assertNull(progress.tick(millis(999), EVERY));
        assertNotNull(progress.tick(millis(1000), EVERY), "and published the moment it has");
    }

    @Test
    void anUpdateCarriesEverythingSoFarAndOnlyWhatIsNew() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("Frank ", "", false));
        progress.add(new LlmStreamChunk("Herbert", "", false));

        LlmUpdate first = progress.tick(millis(1000), EVERY);
        assertEquals("Frank Herbert", first.answer());
        assertEquals("Frank Herbert", first.newText());

        progress.add(new LlmStreamChunk(" wrote it", "", false));
        LlmUpdate second = progress.tick(millis(2000), EVERY);
        // The whole answer for a display that replaces, only the new words for one that appends.
        assertEquals("Frank Herbert wrote it", second.answer());
        assertEquals(" wrote it", second.newText());
    }

    @Test
    void anIntervalThatPassedWithNoNewTextPublishesNothingAndDoesNotCostTheNextOne() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("Frank", "", false));
        assertNotNull(progress.tick(millis(1000), EVERY));

        // Nothing arrived: no update, and - the point of the test - the clock was not moved on, so
        // the next token publishes at once rather than waiting out a second interval.
        assertNull(progress.tick(millis(2000), EVERY));
        progress.add(new LlmStreamChunk(" Herbert", "", false));
        LlmUpdate resumed = progress.tick(millis(2001), EVERY);
        assertNotNull(resumed);
        assertEquals(" Herbert", resumed.newText());
    }

    @Test
    void tokensArrivingWhileAnUpdateIsOutCoalesceIntoTheNextOne() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("one ", "", false));
        assertEquals("one ", progress.tick(millis(1000), EVERY).answer());

        // Five chunks arrive during one slow Update branch: they become a single update carrying
        // the newest state, not five queued behind each other.
        for (String word : new String[]{"two ", "three ", "four ", "five ", "six"}) {
            progress.add(new LlmStreamChunk(word, "", false));
        }
        LlmUpdate caughtUp = progress.tick(millis(5000), EVERY);
        assertEquals("one two three four five six", caughtUp.answer());
        assertEquals("two three four five six", caughtUp.newText());
    }

    @Test
    void reasoningIsThePhaseUntilTheFirstAnswerTextArrives() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("", "The question is about", false));

        LlmUpdate reasoning = progress.tick(millis(1000), EVERY);
        assertEquals(LlmPhase.THINKING, reasoning.phase());
        assertEquals("The question is about", reasoning.thinking());
        assertEquals("The question is about", reasoning.newText(), "new text is the reasoning while reasoning");
        assertEquals("", reasoning.answer());

        progress.add(new LlmStreamChunk("Dune", " Dune.", false));
        LlmUpdate answering = progress.tick(millis(2000), EVERY);
        assertEquals(LlmPhase.ANSWERING, answering.phase());
        // The answer is what is new now; the reasoning's tail is still carried whole on Thinking.
        assertEquals("Dune", answering.newText());
        assertEquals("The question is about Dune.", answering.thinking());
    }

    @Test
    void theAnswerStartingPublishesAtOnceRatherThanWaitingOutTheInterval() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("", "Dune is a book.", false));
        assertEquals(LlmPhase.THINKING, progress.tick(millis(1000), EVERY).phase());

        // One millisecond later the model starts answering. Left to the clock this would publish
        // 999ms late - or, on a short answer, never, and a graph watching Phase would never see it
        // change at all. There is one such transition in a run, so it does not wait.
        progress.add(new LlmStreamChunk("Frank Herbert.", "", false));
        LlmUpdate answering = progress.tick(millis(1001), EVERY);
        assertNotNull(answering, "the phase changed and nothing was published");
        assertEquals(LlmPhase.ANSWERING, answering.phase());
        assertEquals("Frank Herbert.", answering.answer());

        // And the waiver is for the change, not for the phase: the next update waits again.
        progress.add(new LlmStreamChunk(" It is.", "", false));
        assertNull(progress.tick(millis(1002), EVERY));
    }

    @Test
    void theFirstUpdateOfARunStillWaits() {
        // There is no phase to have changed from, so a fast first token does not publish an update
        // a graph would overwrite milliseconds later.
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("Frank", "", false));

        assertNull(progress.tick(millis(1), EVERY));
    }

    @Test
    void aModelThatNeverReasonsIsAnsweringFromItsFirstUpdate() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("Frank Herbert.", "", false));

        assertEquals(LlmPhase.ANSWERING, progress.tick(millis(1000), EVERY).phase());
        assertEquals("", progress.thinking());
    }

    @Test
    void theFinishedAnswerIsEverythingThatWasAdded() {
        LlmProgress progress = new LlmProgress(0);
        progress.add(new LlmStreamChunk("Frank ", "thinking ", false));
        progress.add(new LlmStreamChunk("Herbert", "hard", false));
        // Including what no update ever published: the end of a run publishes from here, not from
        // the last tick.
        assertEquals("Frank Herbert", progress.answer());
        assertEquals("thinking hard", progress.thinking());
    }
}
