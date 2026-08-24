package io.github.jaymcole.housegraph.plugins.collections;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What {@link Lists} is to the list nodes, this is to the map ones: the rules they all play by, in
 * one JavaFX-free place so they can be unit-tested headlessly rather than re-derived — and
 * re-diverged — in each of ten nodes.
 * <p>
 * <b>Read {@link Lists} first.</b> Everything there about erasure applies here and more sharply: a
 * map port's type is a bare {@code Map.class}, so <em>both</em> its key and its value types are
 * invisible, and a map produced anywhere may be wired into any map input.
 *
 * <h2>A map published here is keyed by text</h2>
 *
 * Every map this library emits is a {@link LinkedHashMap} whose keys are {@link String} — the
 * {@link Lists#key text form} of whatever key it was given. Values are left exactly as they
 * arrived. Three things drive that, and it is the single decision the rest of this class follows
 * from:
 * <ul>
 *   <li><b>A key is something you type.</b> Only {@code String}, {@code Integer} and {@code Float}
 *       have registered value editors, so a Key field on the canvas is a text field. A map whose
 *       keys were {@code Integer} would be unaddressable from the one place a person actually
 *       authors a key.</li>
 *   <li><b>It keeps lookup O(1).</b> Preserving original key objects and comparing them with
 *       {@link Lists#sameValue} would mean scanning the whole map for every <b>Map Get</b> — the
 *       obvious alternative, and one that turns <b>Tally</b> over a long list quadratic.</li>
 *   <li><b>Nothing downstream can tell.</b> The library's own conventions absorb the loss:
 *       {@link Lists#number} parses {@code "3"} back to a number, {@link Lists#sameValue} matches
 *       it against a {@code 3}, and {@link Lists#NATURAL_ORDER} sorts a list of such keys
 *       numerically rather than alphabetically.</li>
 * </ul>
 * Note the asymmetry with {@link Sets}, which keeps its members as the objects they arrived as.
 * That is deliberate: a map's key is a <em>handle you look things up by</em>, while a set's member
 * is a <em>value that flows on downstream</em> and is worth keeping typed.
 *
 * <h2>Two more conventions</h2>
 * <ul>
 *   <li><b>A blank key is an unfilled field, not a key.</b> {@link #key} returns null for a null or
 *       blank key, and every node here skips such an entry rather than storing something under
 *       {@code ""}. An empty Key field means "I haven't wired this up yet"; treating it as a
 *       genuine key would put a mystery entry in the map of every half-built graph.</li>
 *   <li><b>A map output is never null</b> — nodes emit {@link Map#of()} for the empty case, so a
 *       downstream node is never handed a null it has to defend against.</li>
 * </ul>
 */
public final class Maps {

    /**
     * The type every map port in this library declares. The cast is the same laundering
     * {@link Lists#TYPE} performs and for the same reason: a {@code NodeVariable}'s type is a
     * {@code Class<T>}, and {@code Map.class} is a raw {@code Class<Map>}, so parameterising the
     * variable as {@code Map<?, ?>} needs the erased class passed through {@code Class<?>}. It is
     * safe — the runtime object is exactly {@code Map.class} — and doing it once here keeps the
     * unchecked suppression out of ten node classes.
     */
    @SuppressWarnings("unchecked")
    public static final Class<Map<?, ?>> TYPE = (Class<Map<?, ?>>) (Class<?>) Map.class;

    private Maps() {
    }

    /**
     * A key normalised to the form this library stores under: {@link Lists#key} for anything
     * non-null and non-blank, and null for anything else. Callers treat a null result as "no key
     * given" and skip the entry — see the class header on why a blank Key field is not a key.
     *
     * @param value the key as authored or as wired in, possibly null
     * @return the normalised key, or null when no key was really given
     */
    public static String key(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /**
     * A modifiable, text-keyed copy of an input map, or an empty one when it is null. Every node
     * reads its map input through this: it makes "no map wired in" and "an empty map" the same
     * case, it normalises a foreign map's keys onto the rule above, and it means a node never
     * hands downstream a view onto — or a mutation of — the very map its upstream node still
     * holds. Nothing in this library mutates a map it was given.
     * <p>
     * <b>Insertion order is preserved</b>, and where two foreign keys collapse to the same text —
     * a {@code 3} and a {@code "3"} in the same incoming map — <b>the first one wins</b>,
     * consistent with <b>Distinct</b> keeping the first of each repeat. Either answer is
     * defensible for a collision nobody should be able to author here; matching the rest of the
     * library is the tiebreak.
     * <p>
     * An entry with a null value is dropped, for the reason {@link #put} gives. Between that and
     * {@code put}, <b>no map this library publishes ever holds a null value</b>, so a downstream
     * <b>Map Get</b> reporting Found can always be believed.
     *
     * @param map the map an input handed us, possibly null
     * @return a modifiable, text-keyed copy, never null
     */
    public static Map<String, Object> mutableCopyOf(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (map == null) {
            return copy;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = key(entry.getKey());
            if (key != null && entry.getValue() != null) {
                copy.putIfAbsent(key, entry.getValue());
            }
        }
        return copy;
    }

    /**
     * A read-only, text-keyed copy, for a node that only inspects what it was given.
     *
     * @param map the map an input handed us, possibly null
     * @return an unmodifiable, text-keyed copy, never null
     */
    public static Map<String, Object> copyOf(Map<?, ?> map) {
        return frozen(mutableCopyOf(map));
    }

    /**
     * The unmodifiable map a node publishes on its output, defensively copied so no later mutation
     * of the working map can reach downstream.
     * <p>
     * Deliberately not {@link Map#copyOf}, which does not preserve iteration order — and order is
     * the whole thing that makes <b>Map Entries</b>' parallel Keys and Values lists mean anything.
     * It would also throw on a null value; nothing upstream of here should produce one (see
     * {@link #put}), but a helper that failed a whole run over a stray null would be exactly the
     * kind of silent-until-it-isn't failure this library is trying to avoid.
     *
     * @param entries the entries to publish
     * @return an unmodifiable snapshot, never null
     */
    public static Map<String, Object> frozen(Map<String, Object> entries) {
        return entries.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * Stores one entry, and only a whole one: <b>a half-filled pair contributes nothing</b>, so a
     * key with no value wired to it is skipped exactly as a value with no key is. That is the
     * same rule <b>Build List</b> applies to its trailing spare slot, and it is the reason
     * <b>Map Get</b>'s <b>Found</b> can be trusted — a map that stored keys with null values would
     * answer "found" for an entry whose value nobody ever supplied, which is a worse lie than
     * "not found".
     * <p>
     * Returns whether the entry went in, which is what lets a node report how many of its slots
     * actually contributed.
     *
     * @param target the working map
     * @param key    the key as authored or wired, possibly null
     * @param value  the value to store, possibly null
     * @return true if an entry was stored
     */
    public static boolean put(Map<String, Object> target, Object key, Object value) {
        String normalised = key(key);
        if (normalised == null || value == null) {
            return false;
        }
        target.put(normalised, value);
        return true;
    }
}
