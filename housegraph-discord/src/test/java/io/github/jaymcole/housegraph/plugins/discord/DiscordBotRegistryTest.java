package io.github.jaymcole.housegraph.plugins.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the token-keyed dedup at the heart of {@link DiscordBotRegistry}: two callers
 * acquiring the same token must be handed the same {@link DiscordBot} instance rather than each
 * getting their own, and the shared instance must only be released once every caller that
 * acquired it has released it. Uses a fresh {@link DiscordBotRegistry} per test rather than
 * {@link DiscordBotRegistry#shared()} so tests can't see each other's tokens.
 */
class DiscordBotRegistryTest {

    @Test
    void aFreshTokenRegistersTheCandidateAsOwner() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot candidate = new DiscordBot();

        DiscordBot acquired = registry.acquire("token-a", candidate);

        assertSame(candidate, acquired, "the first caller for a token becomes its owner");
    }

    @Test
    void aSecondAcquireForTheSameTokenSharesTheFirstCandidateInstead() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        DiscordBot secondCandidate = new DiscordBot();
        registry.acquire("token-a", owner);

        DiscordBot acquired = registry.acquire("token-a", secondCandidate);

        assertSame(owner, acquired,
                "a second node with the same token must share the owner's DiscordBot, not connect its own");
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
        assertSame(nextCandidate, registry.acquire("token-a", nextCandidate),
                "once the token is fully released, the next acquire registers a fresh owner");
    }

    @Test
    void releasingABotThatIsNotTheRegisteredOneIsANoOp() {
        DiscordBotRegistry registry = new DiscordBotRegistry();
        DiscordBot owner = new DiscordBot();
        registry.acquire("token-a", owner);

        boolean wasLast = registry.release("token-a", new DiscordBot());

        assertFalse(wasLast, "releasing an instance that was never registered for this token must not affect it");
        assertSame(owner, registry.acquire("token-a", new DiscordBot()),
                "the real owner's registration must be untouched by the bogus release");
    }
}
