package io.github.jaymcole.housegraph.plugins.collections;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What {@link Lists} is to the list nodes, this is to the set ones. <b>Read {@link Lists}
 * first</b> — everything there about erasure applies, since a set port's type is a bare
 * {@code Set.class} and its member type is invisible.
 *
 * <h2>A set here is "a list with the repeats taken out", and knows it</h2>
 *
 * Every set this library emits is a {@link LinkedHashSet} that
 * <ul>
 *   <li><b>keeps insertion order</b>, not hash order, so <b>Set to List</b> gives back the order
 *       things went in rather than a shuffle that changes between JVM runs — a set whose printed
 *       form reordered itself for no reason would be unusable in a message someone reads;</li>
 *   <li><b>holds the first-seen original object</b> for each distinct member, exactly as
 *       <b>Distinct</b> does for a list, so a set built from numbers still yields numbers when it
 *       is turned back into a list and fed to <b>List Statistics</b>;</li>
 *   <li><b>counts two members as the same when their {@link Lists#key text forms} match</b>, so a
 *       {@code 3} and a {@code "3"} are one member and a typed {@code "3"} finds either.</li>
 * </ul>
 *
 * <h2>Why that last point needs this class at all</h2>
 *
 * {@link Set#contains} uses {@code equals}, which says a {@code 3} and a {@code "3"} are different
 * — the very distinction erasure makes invisible on the canvas, and the one {@link Lists} spends
 * its header explaining away. So <b>membership is never asked of the {@code Set} itself</b>: it is
 * asked of {@link #contains} here, which compares text forms. The published {@code Set} is still a
 * plain {@code java.util.Set} (nothing exotic to serialise or wire), and it stays honest under
 * {@code equals} because {@link #mutableCopyOf} and {@link #add} are the only ways members get in
 * — and neither ever admits two members sharing a text form in the first place.
 * <p>
 * Note the asymmetry with {@link Maps}, which normalises its keys <em>to</em> text rather than
 * merely comparing by it. See that class for why the two differ.
 */
public final class Sets {

    /**
     * The type every set port in this library declares — the same laundering, for the same reason,
     * as {@link Lists#TYPE} and {@link Maps#TYPE}.
     */
    @SuppressWarnings("unchecked")
    public static final Class<Set<?>> TYPE = (Class<Set<?>>) (Class<?>) Set.class;

    private Sets() {
    }

    /**
     * A modifiable set built from any collection — a list, another set — with repeats dropped by
     * text form, keeping the first of each and the order they arrived in. This is every set's front
     * door: nothing in this library publishes a set that did not come through here or through
     * {@link #add}, which is what keeps the "no two members share a text form" invariant true.
     * <p>
     * A null member contributes nothing, so a list carrying the nulls the host's object decomposer
     * can emit does not gain a null member on the way in.
     * <p>
     * Deduplication runs off a local index of text keys rather than calling {@link #contains} per
     * member, which would make building a set from a list quadratic. Every node that walks two
     * sets against each other does the same — see {@link #keysOf}.
     *
     * @param values the collection to build from, possibly null
     * @return a modifiable set, never null
     */
    public static Set<Object> mutableCopyOf(Collection<?> values) {
        Set<Object> copy = new LinkedHashSet<>();
        if (values == null) {
            return copy;
        }
        Set<String> seen = new HashSet<>();
        for (Object value : values) {
            if (value != null && seen.add(Lists.key(value))) {
                copy.add(value);
            }
        }
        return copy;
    }

    /**
     * The text forms of a collection's members, for a node that has to test membership repeatedly —
     * <b>Set Intersection</b> and <b>Set Difference</b> ask "is this in the other set?" once per
     * member, and doing that through {@link #contains} would be a scan inside a scan. Nulls are
     * skipped, matching everything else here.
     *
     * @param values the collection to index, possibly null
     * @return the distinct text forms of its members, never null
     */
    public static Set<String> keysOf(Collection<?> values) {
        Set<String> keys = new HashSet<>();
        if (values != null) {
            for (Object value : values) {
                if (value != null) {
                    keys.add(Lists.key(value));
                }
            }
        }
        return keys;
    }

    /**
     * A read-only set built from any collection, for a node that only inspects what it was given.
     *
     * @param values the collection to build from, possibly null
     * @return an unmodifiable set, never null
     */
    public static Set<Object> copyOf(Collection<?> values) {
        return frozen(mutableCopyOf(values));
    }

    /**
     * The unmodifiable set a node publishes on its output, defensively copied so no later mutation
     * of the working set can reach downstream. Deliberately not {@link Set#copyOf}, which throws on
     * a null member and — the point here — does not preserve iteration order.
     *
     * @param members the members to publish
     * @return an unmodifiable snapshot, never null
     */
    public static Set<Object> frozen(Set<Object> members) {
        return members.isEmpty() ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    /**
     * Adds one member unless the set already holds something with the same text form, or the value
     * is null. Returns whether it went in — which is both what <b>Set Add</b> reports on its
     * <b>Added</b> output and how <b>To Set</b> counts the repeats it dropped.
     *
     * @param target the working set
     * @param value  the member to add, possibly null
     * @return true if the set grew
     */
    public static boolean add(Set<Object> target, Object value) {
        if (value == null || contains(target, value)) {
            return false;
        }
        return target.add(value);
    }

    /**
     * Whether a set holds a member with the same {@link Lists#key text form} as {@code value} —
     * the forgiving membership test this library uses everywhere, and the reason nothing here
     * calls {@link Set#contains} directly (see the class header).
     * <p>
     * A null value is held by no set, matching {@link #add} refusing to put one in.
     *
     * @param members the set to search, possibly null
     * @param value   the value to look for, possibly null
     * @return true if the set holds it
     */
    public static boolean contains(Set<Object> members, Object value) {
        if (members == null || value == null) {
            return false;
        }
        String wanted = Lists.key(value);
        for (Object member : members) {
            if (wanted.equals(Lists.key(member))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes every member with the same text form as {@code value}, and reports whether anything
     * went. Written as a scan rather than {@link Set#remove} for the reason in the class header:
     * {@code remove} would compare by {@code equals} and so miss the {@code 3} a user asked for by
     * typing {@code "3"}.
     *
     * @param target the working set
     * @param value  the member to take out, possibly null
     * @return true if the set shrank
     */
    public static boolean remove(Set<Object> target, Object value) {
        if (value == null) {
            return false;
        }
        String unwanted = Lists.key(value);
        return target.removeIf(member -> unwanted.equals(Lists.key(member)));
    }
}
