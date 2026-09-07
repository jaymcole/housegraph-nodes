package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.resource.ResourceRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The backing store {@link AddToCollectionNode} and {@link ClearCollectionNode} share: a list
 * addressed by name rather than by node identity, so two node instances anywhere in the graph can
 * refer to the same collection with no edge between them — the same "referenced by name rather
 * than wired" idea {@link ResourceRegistry} already provides for long-lived resources (see
 * {@code docs/shared/node-library-rules.md}).
 * <p>
 * Keys are namespaced under {@code collections.list:} so a name typed into one of these nodes can
 * never collide with an unrelated resource (a bot, a server) some other node registered under the
 * same plain name.
 * <p>
 * A name's list is created empty on first use by either node and then lives for as long as the
 * running graph does. Nothing here ties it to any one node's lifecycle, which is deliberate: a
 * shared collection must outlive whichever single Add or Clear node happens to reach it first, or
 * removing one of several nodes that share a name would wipe out what the others are still using.
 */
final class NamedCollections {

    private NamedCollections() {
    }

    /**
     * The mutable, thread-safe list for {@code name}, creating it empty on first use. Callers
     * synchronize on the returned list themselves when reading or writing it.
     *
     * @param name the collection's name, as typed into a node's Name field
     * @return the same list object every caller with this name gets, never null
     */
    static List<Object> get(String name) {
        String key = key(name);
        ResourceRegistry registry = ResourceRegistry.shared();
        synchronized (NamedCollections.class) {
            Optional<List> existing = registry.find(key, List.class);
            if (existing.isPresent()) {
                @SuppressWarnings("unchecked")
                List<Object> found = existing.get();
                return found;
            }
            List<Object> created = Collections.synchronizedList(new ArrayList<>());
            registry.register(key, created);
            return created;
        }
    }

    private static String key(String name) {
        return "collections.list:" + name;
    }
}
