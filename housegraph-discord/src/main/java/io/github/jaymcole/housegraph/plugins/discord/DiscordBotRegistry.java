package io.github.jaymcole.housegraph.plugins.discord;

import java.util.HashMap;
import java.util.Map;

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
 * its owner and is responsible for actually calling {@link DiscordBot#connect}; every later
 * acquire for the same token shares that owner's instance and bumps the count. {@link #release}
 * decrements it and reports whether the caller was the last node still using it — only then
 * should the caller actually disconnect the shared {@link DiscordBot}.
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
     * holds this token, bumps its reference count and returns its {@link DiscordBot} — the
     * caller must treat that as shared and must not call {@link DiscordBot#connect} on it.
     * Otherwise registers {@code candidate} as the owner (refcount 1) and returns it unchanged —
     * the caller is then responsible for actually connecting it.
     */
    public synchronized DiscordBot acquire(String token, DiscordBot candidate) {
        Entry existing = byToken.get(token);
        if (existing != null) {
            existing.refCount++;
            return existing.bot;
        }
        byToken.put(token, new Entry(candidate));
        return candidate;
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

    private static final class Entry {
        final DiscordBot bot;
        int refCount = 1;

        Entry(DiscordBot bot) {
            this.bot = bot;
        }
    }
}
