package io.github.jaymcole.housegraph.plugins.string;

import java.util.List;

/**
 * The shared rules every node in this library plays by, in one JavaFX-free place so they can be
 * unit-tested headlessly (see {@code TextsTest}) rather than re-derived — and re-diverged — in
 * fourteen node classes.
 * <p>
 * <b>Three conventions</b>, which every node here documents itself against:
 * <ul>
 *   <li><b>Absent text is empty text.</b> Every node reads its Text input through
 *       {@link #orEmpty}, so an unwired input and an empty string are the same case. Nothing in
 *       this library throws because text was null, and nothing emits the word {@code "null"}.</li>
 *   <li><b>A text output is never null.</b> Downstream nodes are handed {@code ""} for the empty
 *       case, and a list output is {@link List#of()} — never a null to defend against.</li>
 *   <li><b>A position may count from the end.</b> {@link #resolvePosition} reads a negative index
 *       as an offset from the end of the text, the same convention the Collections library uses
 *       for list indices, so "the last four characters" needs no Length node to express.</li>
 * </ul>
 * <p>
 * <b>An authored mode is a string, not a checkbox.</b> Several nodes here take a mode — the case
 * to change to, how to trim, how to compare. Only {@code Float}, {@code String} and
 * {@code Integer} are registered with the host's {@code ValueEditors}, so those are the only
 * types a user can type into an inline field; a {@code Boolean} input can be wired but not
 * authored. Rather than register a boolean editor globally from a plugin — a host-wide,
 * last-write-wins change to make one node convenient — every mode here is spelled out in text
 * (see {@link CaseMode}, {@link TrimMode}, {@link CompareMode}), which also reads better on the
 * canvas than an unlabelled checkbox would and leaves the mode wireable from upstream.
 */
public final class Texts {

    /**
     * The type every list port in this library declares. The cast is the one {@code ForEachNode}
     * and {@code ListToStringNode} perform: a {@code NodeVariable}'s type is a {@code Class<T>},
     * and {@code List.class} is a {@code Class<List>}, so parameterising the variable as
     * {@code List<?>} needs the erased class laundered through {@code Class<?>}. It is safe — the
     * runtime object is exactly {@code List.class} — and doing it once here keeps the unchecked
     * suppression out of the node classes.
     */
    @SuppressWarnings("unchecked")
    public static final Class<List<?>> LIST_TYPE = (Class<List<?>>) (Class<?>) List.class;

    private Texts() {
    }

    /**
     * Text as this library reads it: the string itself, or {@code ""} when it is null. An unwired
     * or never-typed input is therefore empty text rather than a null that every node would have
     * to guard.
     *
     * @param text the value an input handed us, possibly null
     * @return the text, never null
     */
    public static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * Any value rendered as text: {@link String#valueOf} for anything non-null, and {@code ""} for
     * null. This is how Format Text stringifies whatever was wired into a placeholder slot — the
     * slots are typed {@link Object} on purpose (see {@code FormatTextNode}), so the value may be
     * a number, a boolean, or a list.
     *
     * @param value the value to render, possibly null
     * @return the value's text form, never null
     */
    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Resolves an authored character position against a text's length: a negative position counts
     * back from the end ({@code -1} is one before the last character), and the result is clamped
     * into {@code [0, length]} so a position past either end saturates rather than throwing. A
     * null position — an input nobody wired or typed into — selects {@code fallback}, which is how
     * Substring means "from the start" and "to the end" without a magic number.
     *
     * @param position the authored position, negative to count from the end, or null
     * @param length   the text's length
     * @param fallback the position to use when none was authored
     * @return a position within {@code [0, length]}
     */
    public static int resolvePosition(Integer position, int length, int fallback) {
        if (position == null) {
            return Math.max(0, Math.min(fallback, length));
        }
        int resolved = position < 0 ? length + position : position;
        return Math.max(0, Math.min(resolved, length));
    }

    /**
     * Interprets the escape sequences a single-line inline field cannot otherwise carry:
     * {@code \n}, {@code \r}, {@code \t}, and a doubled backslash for a literal one. Used by the
     * separator of Join Text, because there is no way to type a real newline into a node's inline
     * field — and joining a list one entry per line is the whole reason someone reaches for it.
     * An unrecognised escape is left alone, backslash included, so a Windows path typed as a
     * separator survives intact.
     *
     * @param text the authored text, possibly null
     * @return the text with escape sequences interpreted, never null
     */
    public static String unescape(String text) {
        String source = orEmpty(text);
        StringBuilder result = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current != '\\' || i + 1 >= source.length()) {
                result.append(current);
                continue;
            }
            char next = source.charAt(i + 1);
            switch (next) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '\\' -> result.append('\\');
                default -> {
                    result.append(current).append(next);
                }
            }
            i++;
        }
        return result.toString();
    }
}
