package io.github.jaymcole.housegraph.plugins.collections;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shared rules every map node plays by, in one JavaFX-free place so they can be unit-tested
 * headlessly (see {@code MapsTest}) rather than re-derived in each node — the same reason
 * {@link Lists} exists for the list nodes it sits beside.
 * <p>
 * <b>Why any of this is needed.</b> A data anchor's type is a bare {@link Class}, so a map port is
 * {@code Map.class} and <em>both its key and value types are erased</em>, exactly as a list port's
 * element type is (see {@link Lists}). A node here cannot assume what it was handed, so every
 * operation is defensive, and key lookups use {@link Lists#sameValue} — the same forgiving,
 * text-form equality the list nodes use — so a typed {@code "3"} finds an upstream node's key of
 * {@code 3}.
 * <p>
 * <b>Key order is preserved.</b> Copies are backed by {@link LinkedHashMap}, so <b>Map Keys</b> and
 * <b>Map Values</b> report entries in the order they were put, which is what makes the two outputs
 * of that pair line up index-for-index.
 */
public final class Maps {

    /**
     * The type every map port in this library declares. See {@link Lists#TYPE} for why the erased
     * class has to be laundered through {@code Class<?>} to parameterise the variable as
     * {@code Map<?, ?>}.
     */
    @SuppressWarnings("unchecked")
    public static final Class<Map<?, ?>> TYPE = (Class<Map<?, ?>>) (Class<?>) Map.class;

    private Maps() {
    }

    /**
     * A defensive, unmodifiable copy of an input map, or an empty map when it is null. Every node
     * reads its map input through this, so "no map wired in" and "an empty map" are the same case
     * and a node never hands downstream a view onto — or a mutation of — the very map its upstream
     * node still holds.
     *
     * @param map the map an input handed us, possibly null
     * @return an unmodifiable copy, never null, with entries in the source's iteration order
     */
    public static Map<Object, Object> copyOf(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return frozen(mutableCopyOf(map));
    }

    /**
     * A modifiable copy, for a node that is about to put or remove entries before publishing them.
     *
     * @param map the map to copy, possibly null
     * @return a modifiable copy, never null, preserving the source's iteration order
     */
    public static Map<Object, Object> mutableCopyOf(Map<?, ?> map) {
        Map<Object, Object> copy = new LinkedHashMap<>();
        if (map != null) {
            copy.putAll(map);
        }
        return copy;
    }

    /**
     * The unmodifiable map a node publishes on its output, defensively copied so no later mutation
     * of the working map can reach downstream.
     * <p>
     * Deliberately not {@link Map#copyOf}, which throws on a null value — a legitimate thing to
     * store under a key (see <b>Put</b>) — and which does not preserve iteration order.
     *
     * @param entries the entries to publish
     * @return an unmodifiable snapshot, never null, in the given map's iteration order
     */
    public static Map<Object, Object> frozen(Map<Object, Object> entries) {
        return entries.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * Whether a map has an entry under a given key, matching the same {@link Lists#sameValue
     * forgiving} way every other lookup in this library does.
     *
     * @param map        the map to search, possibly null
     * @param wantedKey  the key to look for, possibly null
     * @return true if some entry's key matches
     */
    public static boolean containsKey(Map<?, ?> map, Object wantedKey) {
        if (map == null) {
            return false;
        }
        for (Object key : map.keySet()) {
            if (Lists.sameValue(key, wantedKey)) {
                return true;
            }
        }
        return false;
    }
}
