package io.github.jaymcole.housegraph.plugins.llm;

import io.github.jaymcole.housegraph.resource.ResourceRegistry;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Every conversation the graph is holding, addressed by the name typed into a node rather than by
 * an edge — so a <b>Local LLM</b> node and a <b>Clear Conversation</b> node anywhere on the canvas
 * reach the same history with nothing wired between them. This is the same "referenced by name
 * rather than wired" idea {@link ResourceRegistry} provides for long-lived resources, and the same
 * shape {@code housegraph-collections} uses for its named collections.
 * <p>
 * <b>The whole map is one registry entry</b>, not one entry per conversation. Capping and expiry
 * then belong to this class rather than to the registry, and a Discord bot keyed by sender id
 * cannot fill the registry's name space with one entry per person who ever ran a command.
 * <p>
 * <b>Memory only. Nothing is written to the save file.</b> Two reasons, either of them sufficient:
 * a graph file is not the place for somebody's private conversation with a bot, and a save file
 * should stay small where a history does not. A restarted HouseGraph starts every conversation
 * fresh, exactly as a named collection does.
 * <p>
 * <b>Two limits keep it bounded</b>, because the names come from outside and there is no event that
 * says a conversation is over:
 * <ul>
 *   <li><b>Idle expiry</b> — a conversation untouched for longer than its window (the node's
 *       <b>Forget After (min)</b>) is dropped. Sweeping is lazy, on use: there is no timer here,
 *       which keeps this class something a test can drive and a graph doing nothing cost-free.</li>
 *   <li><b>{@value #MAX_CONVERSATIONS} at once</b> — reached only by something like a public bot,
 *       where the least recently used conversation is dropped to make room. It is a constant rather
 *       than a port because it exists to stop the process growing without bound, not to be tuned
 *       from the canvas.</li>
 * </ul>
 * Both drop conversations silently, which is the honest behaviour for memory that was never
 * promised to be durable: the next message simply starts a new conversation under the same name.
 */
public final class LlmConversations {

    /** How many conversations are held at once before the least recently used one is dropped. */
    static final int MAX_CONVERSATIONS = 200;

    /**
     * Namespaced so a name typed into a node cannot collide with a bot or a server some other
     * library registered under the same plain name.
     */
    private static final String REGISTRY_KEY = "llm.conversations";

    private final LongSupplier clock;

    /**
     * Access-ordered, so iteration starts at the least recently used and the eldest entry is the
     * one to drop. Guarded by this object's monitor rather than being concurrent: every operation
     * here is a read-modify-write of the map, and the work inside it is a handful of references.
     */
    private final Map<String, LlmConversation> byName = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LlmConversation> eldest) {
            return size() > MAX_CONVERSATIONS;
        }
    };

    LlmConversations(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * The one map this JVM's nodes share, created on first use.
     *
     * @return the shared conversations, never null
     */
    public static LlmConversations shared() {
        ResourceRegistry registry = ResourceRegistry.shared();
        synchronized (LlmConversations.class) {
            Optional<LlmConversations> existing = registry.find(REGISTRY_KEY, LlmConversations.class);
            if (existing.isPresent()) {
                return existing.get();
            }
            LlmConversations created = new LlmConversations(System::currentTimeMillis);
            registry.register(REGISTRY_KEY, created);
            return created;
        }
    }

    /**
     * The conversation called {@code name}, started empty if there isn't one, and marked as just
     * used. Expired conversations are swept first, so a name whose window has passed comes back
     * empty rather than carrying something said hours ago.
     *
     * @param name        the conversation's name, as typed into a node
     * @param idleMinutes how long it may sit idle before being forgotten; zero or less means never
     * @return the conversation, never null
     */
    public synchronized LlmConversation get(String name, int idleMinutes) {
        long now = clock.getAsLong();
        sweepExpired(now);
        LlmConversation conversation = byName.computeIfAbsent(name, ignored -> new LlmConversation(now));
        conversation.touch(now, Math.max(0, idleMinutes) * 60_000L);
        return conversation;
    }

    /**
     * The conversation called {@code name} if there is one — without starting it. Asking whether a
     * conversation exists must not be what creates it, which is what a <b>Clear Conversation</b>
     * node being pulled for data would otherwise do.
     *
     * @param name the conversation's name
     * @return the conversation, or empty if nothing is held under that name
     */
    public synchronized Optional<LlmConversation> peek(String name) {
        sweepExpired(clock.getAsLong());
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Forgets the conversation called {@code name} entirely.
     *
     * @param name the conversation's name
     * @return true if there was one to forget
     */
    public synchronized boolean forget(String name) {
        return byName.remove(name) != null;
    }

    /** How many conversations are currently held. For tests and for the cap's own documentation. */
    synchronized int size() {
        return byName.size();
    }

    private void sweepExpired(long now) {
        Iterator<Map.Entry<String, LlmConversation>> entries = byName.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().isExpired(now)) {
                entries.remove();
            }
        }
    }
}
