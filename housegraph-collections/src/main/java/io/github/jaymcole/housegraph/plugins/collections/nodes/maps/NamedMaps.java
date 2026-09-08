package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.resource.ResourceRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The backing store {@link PutInMapNode} and {@link ClearMapNode} share: a map addressed by name
 * rather than by node identity. See {@code NamedCollections} in the {@code lists} package, whose
 * design this mirrors exactly — a name's map is created empty on first use, keys are namespaced
 * under {@code collections.map:} so a name here can never collide with an unrelated resource (or
 * with a same-named <em>list</em>, which lives under {@code collections.list:} instead), and
 * nothing ties a map's lifecycle to any one node.
 */
final class NamedMaps {

    private NamedMaps() {
    }

    /**
     * The mutable, thread-safe, insertion-ordered map for {@code name}, creating it empty on first
     * use. Callers synchronize on the returned map themselves when reading or writing it.
     *
     * @param name the collection's name, as typed into a node's Name field
     * @return the same map object every caller with this name gets, never null
     */
    static Map<String, Object> get(String name) {
        String key = key(name);
        ResourceRegistry registry = ResourceRegistry.shared();
        synchronized (NamedMaps.class) {
            Optional<Map> existing = registry.find(key, Map.class);
            if (existing.isPresent()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> found = existing.get();
                return found;
            }
            Map<String, Object> created = Collections.synchronizedMap(new LinkedHashMap<>());
            registry.register(key, created);
            return created;
        }
    }

    private static String key(String name) {
        return "collections.map:" + name;
    }
}
