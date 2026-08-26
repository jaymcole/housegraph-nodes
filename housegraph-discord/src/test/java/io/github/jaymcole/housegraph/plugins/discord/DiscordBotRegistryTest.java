package io.github.jaymcole.housegraph.plugins.discord;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the token-keyed dedup at the heart of {@link DiscordBotRegistry}: two callers
 * acquiring the same token must be handed the same {@link DiscordBot} instance rather than each
 * getting their own, the shared instance must only be released once every caller that acquired it
 * has released it, and a joiner must not treat the shared connection as ready until the owner's
 * own connect attempt has actually settled (see {@link DiscordBotRegistry.Entry#awaitSettled()}).
 * Uses a fresh {@link DiscordBotRegistry} per test rather than {@link DiscordBotRegistry#shared()}
 * so tests can't see each other's tokens.
 */
class DiscordBotRegistryTest {

    @Test
    void aFreshTokenRegistersTheCandidateAsOwner() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot candidate = new DiscordBot();

        DiscordBotRegistry.Entry entry = registry.acquire("token-a", candidate);

        assertSame(candidate, entry.bot, "the first caller for a token becomes its owner");
        assertTrue(entry.isOwner(candidate));
    }

    @Test
    void aSecondAcquireForTheSameTokenSharesTheFirstCandidateInstead() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        DiscordBot secondCandidate = new DiscordBot();
        registry.acquire("token-a", owner);

        DiscordBotRegistry.Entry entry = registry.acquire("token-a", secondCandidate);

        assertSame(owner, entry.bot,
                "a second node with the same token must share the owner's DiscordBot, not connect its own");
        assertFalse(entry.isOwner(secondCandidate));
    }

    @Test
    void releasingWhileAnotherHolderRemainsDoesNotSignalLastReference() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        registry.acquire("token-a", owner);
        registry.acquire("token-a", new DiscordBot()); // a joiner sharing `owner`

        boolean wasLast = registry.release("token-a", owner);

        assertFalse(wasLast,
                "the joiner still holds a reference, so the owner releasing must not tear the shared connection down");
    }

    @Test
    void releasingTheLastHolderSignalsLastReferenceAndFreesTheToken() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        registry.acquire("token-a", owner);

        boolean wasLast = registry.release("token-a", owner);

        assertTrue(wasLast, "the sole holder releasing must be told to tear the connection down itself");

        DiscordBot nextCandidate = new DiscordBot();
        assertSame(nextCandidate, registry.acquire("token-a", nextCandidate).bot,
                "once the token is fully released, the next acquire registers a fresh owner");
    }

    @Test
    void releasingABotThatIsNotTheRegisteredOneIsANoOp() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        registry.acquire("token-a", owner);

        boolean wasLast = registry.release("token-a", new DiscordBot());

        assertFalse(wasLast, "releasing an instance that was never registered for this token must not affect it");
        assertSame(owner, registry.acquire("token-a", new DiscordBot()).bot,
                "the real owner's registration must be untouched by the bogus release");
    }

    @Test
    void aJoinerBlocksInAwaitSettledUntilTheOwnerReportsConnected() throws Exception {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        DiscordBotRegistry.Entry ownerEntry = registry.acquire("token-a", owner);
        DiscordBotRegistry.Entry joinerEntry = registry.acquire("token-a", new DiscordBot());

        AtomicReference<Boolean> returnedBeforeSettled = new AtomicReference<>();
        CountDownLatch joinerStarted = new CountDownLatch(1);
        CountDownLatch joinerDone = new CountDownLatch(1);
        Thread joiner = new Thread(() -> {
            joinerStarted.countDown();
            try {
                joinerEntry.awaitSettled();
                returnedBeforeSettled.set(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                joinerDone.countDown();
            }
        });
        joiner.start();
        joinerStarted.await();

        // Give the joiner every chance to (incorrectly) return early before the owner settles.
        assertFalse(joinerDone.await(200, TimeUnit.MILLISECONDS),
                "a joiner must not treat the shared connection as ready before the owner settles it");

        ownerEntry.settleConnected();
        assertTrue(joinerDone.await(2, TimeUnit.SECONDS), "the joiner must unblock once the owner settles");
    }

    @Test
    void aJoinerRethrowsTheOwnersFailureRatherThanLookingSilentlyDisconnected() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        DiscordBotRegistry.Entry ownerEntry = registry.acquire("token-a", owner);
        DiscordBotRegistry.Entry joinerEntry = registry.acquire("token-a", new DiscordBot());

        RuntimeException failure = new RuntimeException("Connect failed — check token & MESSAGE_CONTENT intent");
        ownerEntry.settleFailed(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, joinerEntry::awaitSettled);
        assertSame(failure, thrown, "a joiner must learn about (and report) the owner's actual connect failure");
    }
}
