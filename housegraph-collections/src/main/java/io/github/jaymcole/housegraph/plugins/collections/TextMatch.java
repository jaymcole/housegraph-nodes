package io.github.jaymcole.housegraph.plugins.collections;

import java.util.Locale;

/**
 * How <b>Filter by Text</b> compares each element against the text it was given, authored as a
 * plain string on a node input.
 * <p>
 * <b>Why a string and not a boolean flag.</b> Only {@code Float}, {@code String} and
 * {@code Integer} are registered with the host's {@code ValueEditors}, so those are the only types
 * a user can type into an inline field; a {@code Boolean} input can be wired but not authored.
 * Rather than register a boolean editor globally from a plugin — a host-wide, last-write-wins
 * change to make one node convenient — the mode is spelled out in text, which also reads better on
 * the canvas than an unlabelled checkbox would.
 * <p>
 * <b>Matching is case-insensitive</b>, deliberately: these filters exist for names, labels and
 * messages, where case is noise more often than signal. When case matters, use <b>Filter by
 * Pattern</b>, whose regular expression says exactly what it means.
 */
public enum TextMatch {

    CONTAINS("contains"),
    NOT_CONTAINS("not contains"),
    STARTS_WITH("starts with"),
    ENDS_WITH("ends with"),
    EQUALS("equals"),
    NOT_EQUALS("not equals");

    /** What a user types to select this mode, and what the error message lists as valid. */
    public final String label;

    TextMatch(String label) {
        this.label = label;
    }

    /**
     * Parses an authored mode. Blank text selects {@link #CONTAINS} — the mode a user who left the
     * field alone almost certainly wanted. Everything else is matched after case, spaces,
     * underscores and hyphens are normalised away, so {@code "Starts With"}, {@code "starts_with"}
     * and {@code "startswith"} are all the same mode.
     * <p>
     * An unrecognised mode <b>throws</b> rather than falling back to a default. A silent fallback
     * would quietly return the wrong rows for the life of the graph; a failed node says so on the
     * canvas the first time it runs.
     *
     * @param text the authored mode, possibly null or blank
     * @return the selected mode
     * @throws IllegalArgumentException if the text names no known mode
     */
    public static TextMatch parse(String text) {
        if (text == null || text.isBlank()) {
            return CONTAINS;
        }
        String normalised = normalise(text);
        for (TextMatch mode : values()) {
            if (normalise(mode.label).equals(normalised)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown text match mode \"" + text + "\" - expected one of " + labels());
    }

    /**
     * Whether an element's text satisfies this mode against {@code needle}. Both sides are
     * lower-cased first (see the class header). A blank needle matches everything under the
     * positive modes and nothing under the negated ones, which is what {@link String#contains} and
     * friends already do for an empty string — an unfilled Text field therefore filters nothing
     * out rather than emptying the list.
     *
     * @param value  the element's text form (see {@link Lists#text})
     * @param needle the text to compare against, possibly null
     * @return true if the element should be kept
     */
    public boolean matches(String value, String needle) {
        String haystack = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String target = needle == null ? "" : needle.toLowerCase(Locale.ROOT);
        return switch (this) {
            case CONTAINS -> haystack.contains(target);
            case NOT_CONTAINS -> !haystack.contains(target);
            case STARTS_WITH -> haystack.startsWith(target);
            case ENDS_WITH -> haystack.endsWith(target);
            case EQUALS -> haystack.equals(target);
            case NOT_EQUALS -> !haystack.equals(target);
        };
    }

    /** Every mode's label, comma separated - for the node's tooltip and the parse failure message. */
    public static String labels() {
        StringBuilder text = new StringBuilder();
        for (TextMatch mode : values()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(mode.label);
        }
        return text.toString();
    }

    private static String normalise(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }
}
