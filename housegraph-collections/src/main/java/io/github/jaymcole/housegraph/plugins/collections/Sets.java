package io.github.jaymcole.housegraph.plugins.collections;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared rules every set node plays by, in one JavaFX-free place so they can be unit-tested
 * headlessly (see {@code SetsTest}) rather than re-derived in each node — the same reason
 * {@link Lists} exists for the list nodes it sits beside.
 * <p>
 * <b>A set here has no duplicates by {@link Lists#key text-form} identity, not by {@link Object
 * #equals}.</b> That follows directly from erasure (see {@link Lists}): a set port's element type
 * is unknown, so without a forgiving identity a {@code 3} and a {@code "3"} would count as two
 * different members, which is exactly the surprise <b>Distinct</b> exists to avoid for lists. Every
 * node here keeps that promise, so a set built from a list this library produced never re-splits
 * entries that <b>Distinct</b> or <b>To Set</b> already collapsed.
 * <p>
 * <b>Membership order is insertion order</b> ({@link LinkedHashSet}), so <b>To List</b> reports
 * entries in the order they were added rather than some hash-dependent order that would make a
 * saved graph's output change between runs for no visible reason.
 */
public final class Sets {

    /**
     * The type every set port in this library declares. See {@link Lists#TYPE} for why the erased
     * class has to be laundered through {@code Class<?>} to parameterise the variable as
     * {@code Set<?>}.
     */
    @SuppressWarnings("unchecked")
    public static final Class<Set<?>> TYPE = (Class<Set<?>>) (Class<?>) Set.class;

    private Sets() {
    }

    /**
     * A defensive, unmodifiable copy of an input set, or an empty set when it is null.
     *
     * @param set the set an input handed us, possibly null
     * @return an unmodifiable copy, never null, in the source's insertion order
     */
    public static Set<Object> copyOf(Set<?> set) {
        if (set == null || set.isEmpty()) {
            return Set.of();
        }
        return frozen(mutableCopyOf(set));
    }

    /**
     * A modifiable copy, for a node about to add or remove members before publishing them.
     *
     * @param set the set to copy, possibly null
     * @return a modifiable copy, never null, preserving insertion order
     */
    public static LinkedHashSet<Object> mutableCopyOf(Set<?> set) {
        LinkedHashSet<Object> copy = new LinkedHashSet<>();
        if (set != null) {
            copy.addAll(set);
        }
        return copy;
    }

    /**
     * The unmodifiable set a node publishes on its output, defensively copied so no later mutation
     * of the working set can reach downstream.
     *
     * @param members the members to publish
     * @return an unmodifiable snapshot, never null, in the given set's insertion order
     */
    public static Set<Object> frozen(Set<Object> members) {
        return members.isEmpty() ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    /**
     * Whether a set has a member matching the given value, the same {@link Lists#sameValue
     * forgiving} way every other lookup in this library does.
     *
     * @param set       the set to search, possibly null
     * @param candidate the value to look for, possibly null
     * @return true if some member matches
     */
    public static boolean contains(Set<?> set, Object candidate) {
        if (set == null) {
            return false;
        }
        for (Object member : set) {
            if (Lists.sameValue(member, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A set built from a list's entries, dropping repeats by {@link Lists#key text-form} identity
     * and keeping the first occurrence of each — the same rule and the same order
     * {@code DistinctListNode} uses, so the two never disagree about what counts as a duplicate.
     *
     * @param list the list to collapse, possibly null
     * @return an unmodifiable set, never null, in first-seen order
     */
    public static Set<Object> fromList(List<?> list) {
        Set<String> seenKeys = new HashSet<>();
        LinkedHashSet<Object> kept = new LinkedHashSet<>();
        for (Object entry : Lists.copyOf(list)) {
            if (seenKeys.add(Lists.key(entry))) {
                kept.add(entry);
            }
        }
        return frozen(kept);
    }
}
