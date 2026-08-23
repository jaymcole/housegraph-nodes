package io.github.jaymcole.housegraph.plugins.collections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The shared rules every node in this library plays by, in one JavaFX-free place so they can be
 * unit-tested headlessly (see {@code ListsTest}) rather than re-derived — and re-diverged — in
 * each of two dozen nodes.
 * <p>
 * <b>Why any of this is needed.</b> A data anchor's type is a bare {@link Class}, so a list port
 * is {@code List.class} and <em>its element type is erased</em> — see {@code ForEachNode}, which
 * has to type its Current Item output as {@link Object} for exactly this reason. Any list may
 * therefore be wired into any list input, and a node here cannot assume it was handed the element
 * type its author had in mind. Every operation below is consequently defensive: it reads whatever
 * elements it actually got, and never throws because an element was of a surprising type.
 * <p>
 * <b>Three conventions follow from that</b>, and every node in this library documents itself
 * against them:
 * <ul>
 *   <li><b>Equality is forgiving.</b> {@link #sameValue} treats two elements as the same when
 *       {@link Objects#equals} says so <em>or</em> their {@link String#valueOf} forms match, so a
 *       {@code "3"} typed into an Item field finds the {@code 3} that some upstream node emitted.
 *       Without it, erasure would make text-authored inputs nearly useless.</li>
 *   <li><b>Numbers are parsed, not required.</b> {@link #number} reads a {@link Number} directly
 *       and parses a string, and returns null for anything else — so numeric nodes skip the
 *       elements they can't read rather than failing the whole list.</li>
 *   <li><b>A list output is never null.</b> Nodes emit {@link List#of()} for the empty case, so a
 *       downstream node is never handed a null it has to defend against.</li>
 * </ul>
 */
public final class Lists {

    /**
     * The type every list port in this library declares. The cast is the same one
     * {@code ForEachNode} and {@code ListToStringNode} perform: a {@code NodeVariable}'s type is a
     * {@code Class<T>}, and {@code List.class} is a {@code Class<List>}, so parameterising the
     * variable as {@code List<?>} needs the erased class laundered through {@code Class<?>}. It is
     * safe — the runtime object is exactly {@code List.class} — and doing it once here keeps the
     * unchecked suppression out of two dozen node classes.
     */
    @SuppressWarnings("unchecked")
    public static final Class<List<?>> TYPE = (Class<List<?>>) (Class<?>) List.class;

    private Lists() {
    }

    /**
     * A defensive, unmodifiable copy of an input list, or an empty list when it is null. Every
     * node reads its list input through this: it makes "no list wired in" and "an empty list"
     * the same case, and it means a node never hands downstream a view onto — or a mutation of —
     * the very list object its upstream node still holds. Nothing in this library mutates a list
     * it was given.
     *
     * @param list the list an input handed us, possibly null
     * @return an unmodifiable copy, never null
     */
    public static List<Object> copyOf(List<?> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return frozen(mutableCopyOf(list));
    }

    /**
     * A modifiable copy, for a node that is about to sort, shuffle or otherwise rework the
     * entries before publishing them.
     *
     * @param list the list to copy, possibly null
     * @return a modifiable copy, never null
     */
    public static List<Object> mutableCopyOf(List<?> list) {
        List<Object> copy = new ArrayList<>();
        if (list != null) {
            copy.addAll(list);
        }
        return copy;
    }

    /**
     * The unmodifiable list a node publishes on its output, defensively copied so no later
     * mutation of the working list can reach downstream.
     * <p>
     * Deliberately not {@link List#copyOf}, which throws on a null element. A list arriving here
     * may well hold nulls — the host's object decomposer emits one for an absent property, and any
     * node may pass one through — and a helper that dropped a whole run over that would be
     * exactly the kind of silent-until-it-isn't failure this library is trying to avoid.
     *
     * @param entries the entries to publish
     * @return an unmodifiable snapshot, never null
     */
    public static List<Object> frozen(List<Object> entries) {
        return entries.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * An element rendered as text: {@link String#valueOf} for anything non-null, and the empty
     * string for null — so a text filter or a template never has to special-case the word
     * {@code "null"} appearing in the middle of a match.
     *
     * @param value the element to render, possibly null
     * @return the element's text form, never null
     */
    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Whether two elements should count as the same value: equal by {@link Objects#equals}, or
     * equal once both are rendered with {@link String#valueOf}. The second clause is what makes a
     * manually-typed Item usable against a list whose element type nobody can see (see this
     * class's header). Null equals only null — never the empty string.
     *
     * @param a one element, possibly null
     * @param b the other element, possibly null
     * @return true if the two should be treated as the same value
     */
    public static boolean sameValue(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /**
     * The identity an element is deduplicated under, consistent with {@link #sameValue}: its
     * {@link String#valueOf} form, or null for a null element (so nulls collapse together rather
     * than colliding with an empty string).
     *
     * @param value the element, possibly null
     * @return the element's dedup key, null for a null element
     */
    public static String key(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * An element read as a number: a {@link Number} directly, a string parsed (trimmed) as a
     * double, and null for anything else — including a string that isn't a number. Callers skip
     * the nulls, which is what lets a numeric node work on a list that is mostly, but not
     * entirely, numeric.
     *
     * @param value the element, possibly null
     * @return the element's numeric value, or null if it doesn't read as one
     */
    public static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.valueOf(string.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * The order <b>Sort List</b> puts entries in, given that it cannot know their type (see this
     * class's header). Two entries that both {@link #number read as numbers} compare numerically —
     * so {@code "9"} sorts before {@code "10"}, which a plain text sort gets backwards and which is
     * the single most common surprise in a list of numbers-as-text. Anything else compares as text,
     * case-insensitively first so {@code "apple"} and {@code "Banana"} land in the order a person
     * expects, with a case-sensitive tiebreak so the sort stays total. Nulls sort first.
     */
    public static final Comparator<Object> NATURAL_ORDER = Lists::compare;

    /**
     * The comparison behind {@link #NATURAL_ORDER}, exposed for testing and for any node that
     * needs the same ordering without a comparator in hand.
     *
     * @param a one element, possibly null
     * @param b the other element, possibly null
     * @return a negative number, zero, or a positive number as {@code a} sorts before, with, or
     *         after {@code b}
     */
    public static int compare(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : (a == null ? -1 : 1);
        }
        Double left = number(a);
        Double right = number(b);
        if (left != null && right != null) {
            return Double.compare(left, right);
        }
        String leftText = String.valueOf(a);
        String rightText = String.valueOf(b);
        int insensitive = leftText.compareToIgnoreCase(rightText);
        return insensitive != 0 ? insensitive : leftText.compareTo(rightText);
    }

    /**
     * Turns the escape sequences a user can type into the characters they stand for:
     * {@code \n}, {@code \t}, {@code \r} and {@code \\}. A text field is the only way to author a
     * separator or a template, and a literal backslash-n in the middle of a message someone reads
     * is never what was meant.
     * <p>
     * Scanned rather than chained {@link String#replace} calls, so {@code \\n} is a backslash
     * followed by an {@code n} and not a newline — the bug every chained-replace version of this
     * has. An unknown escape is left exactly as it was typed, on the grounds that a Windows path
     * is a likelier explanation than a typo.
     *
     * @param text the authored text, possibly null
     * @return the text with escapes resolved, never null
     */
    public static String unescape(String text) {
        if (text == null) {
            return "";
        }
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '\\' -> out.append('\\');
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }

    /**
     * Resolves an authored index against a list's size, allowing a negative index to count back
     * from the end ({@code -1} is the last element) — the convention every positional node here
     * uses, so "the last thing that happened" needs no Count node to express.
     *
     * @param index the authored index, negative to count from the end
     * @param size  the list's size
     * @return the absolute index, or -1 when it falls outside the list
     */
    public static int resolveIndex(int index, int size) {
        int resolved = index < 0 ? size + index : index;
        return resolved >= 0 && resolved < size ? resolved : -1;
    }
}
