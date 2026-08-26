package io.github.jaymcole.housegraph.plugins.discord;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Deduplicates {@link DiscordBot} connections by token within this process: two {@code Discord
 * Bot} nodes (in the same graph, or in different graphs loaded into the same running app) that
 * carry the same token share one gateway session instead of each opening their own. Without this,
 * every node with the same token gets its own independent JDA connection — Discord fans out
 * gateway events (messages, slash-command/button interactions) to every session on a token, so
 * two connections mean duplicate message handling and a race for the single-use interaction
 * acknowledgment, and each connection's own {@link SlashCommandRegistry}-driven
 * {@link DiscordBot#syncCommands} call does a full overwrite of the application's command list,
 * so whichever node syncs last silently wins (or wipes) what's registered with Discord.
 * <p>
 * This is refcounted, not a permanent cache: the first node to {@link #acquire} a token becomes
 * its owner ({@link Entry#isOwner}) and is responsible for actually calling
 * {@link DiscordBot#connect} and then reporting the outcome via {@link Entry#settleConnected()} or
 * {@link Entry#settleFailed}; every later acquire for the same token shares that owner's instance,
 * bumps the count, and must block in {@link Entry#awaitSettled()} until the owner's connect
 * attempt is known to have actually succeeded or failed — connecting is a blocking network round
 * trip (see {@link DiscordBot#connect}), so a joiner that returned immediately after sharing the
 * reference would report its own still-connecting bot as "disconnected", and would be left
 * permanently sharing a dead connection with no retry if the owner's attempt ultimately failed.
 * {@link #release} decrements the refcount and reports whether the caller was the last node still
 * using it — only then should the caller actually disconnect the shared {@link DiscordBot}.
 * <p>
 * This only dedupes within one JVM. It cannot stop two separate processes (different machines, or
 * two independent headless runs) from both logging in with the same token — there's no
 * cross-process coordination here, so that remains an operational constraint, not something this
 * class can prevent.
 */
public final class DiscordBotRegistry {

    private static final DiscordBotRegistry SHARED = new DiscordBotRegistry();

    /** token -> the live entry for it; absent once the last node using that token releases it. */
    private final Map<String, Entry> byToken = new HashMap<>();

    public static DiscordBotRegistry shared() {
        return SHARED;
    }

    /**
     * Registers this caller as a user of {@code token}'s connection. If another node already
     * holds this token, bumps its reference count and returns its {@link Entry} — the caller must
     * treat {@link Entry#bot} as shared, must not call {@link DiscordBot#connect} on it, and must
     * call {@link Entry#awaitSettled()} before treating the connection as usable (see class docs).
     * Otherwise registers {@code candidate} as the owner (refcount 1) and returns a fresh
     * {@link Entry} wrapping it unchanged — the caller is then responsible for actually connecting
     * it and reporting the outcome via {@link Entry#settleConnected()}/{@link Entry#settleFailed}.
     * <p>
     * The returned {@link Entry} is the caller's handle for the rest of this connection attempt:
     * settlement lives on the {@code Entry} object itself, not behind another by-token lookup, so
     * it stays reachable even after {@link #release} has removed the token from this registry.
     */
    public synchronized Entry acquire(String token, DiscordBot candidate) {
        Entry existing = byToken.get(token);
        if (existing != null) {
            existing.refCount++;
            return existing;
        }
        Entry created = new Entry(candidate);
        byToken.put(token, created);
        return created;
    }

    /**
     * Releases this caller's reference to {@code token}'s connection. Returns {@code true} when
     * this was the last reference — the entry is removed and the caller now owns tearing the
     * connection down — or {@code false} when other nodes still hold it, meaning the caller must
     * leave the shared {@link DiscordBot} alone. A no-op (returns {@code false}) if {@code bot}
     * isn't the instance currently registered for {@code token}.
     */
    public synchronized boolean release(String token, DiscordBot bot) {
        Entry existing = byToken.get(token);
        if (existing == null || existing.bot != bot) {
            return false;
        }
        existing.refCount--;
        if (existing.refCount <= 0) {
            byToken.remove(token);
            return true;
        }
        return false;
    }

    /**
     * One token's live connection attempt: the shared {@link DiscordBot}, its refcount, and
     * whether/how the owner's {@link DiscordBot#connect} call has settled. A joiner must call
     * {@link #awaitSettled()} — off the caller's own thread's blocking budget, same as
     * {@link DiscordBot#connect} itself — before treating {@link #bot} as ready.
     */
    public static final class Entry {
        public final DiscordBot bot;
        private int refCount = 1;
        private final CountDownLatch settled = new CountDownLatch(1);
        private volatile RuntimeException failure;

        private Entry(DiscordBot bot) {
            this.bot = bot;
        }

        /** Whether {@code candidate} is the node that registered this entry (and so owns connecting it). */
        public boolean isOwner(DiscordBot candidate) {
            return bot == candidate;
        }

        /** The owner calls this once its {@link DiscordBot#connect} attempt has succeeded. */
        public void settleConnected() {
            settled.countDown();
        }

        /** The owner calls this once its {@link DiscordBot#connect} attempt has failed with {@code failure}. */
        public void settleFailed(RuntimeException failure) {
            this.failure = failure;
            settled.countDown();
        }

        /**
         * Blocks until the owner's connect attempt has settled. Returns normally once
         * {@link #settleConnected()} was called; rethrows the owner's own failure (so a joiner
         * reports the same error the owner did, rather than silently looking disconnected) once
         * {@link #settleFailed} was called instead.
         */
        public void awaitSettled() throws InterruptedException {
            settled.await();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
