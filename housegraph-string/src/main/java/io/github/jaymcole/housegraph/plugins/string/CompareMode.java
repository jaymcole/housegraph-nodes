package io.github.jaymcole.housegraph.plugins.string;

import java.util.Locale;

/**
 * How Compare Text tests one piece of text against another, authored as a plain string on a node
 * input (see {@link Texts} for why it is text and not a dropdown).
 * <p>
 * <b>Comparison is case-insensitive</b>, deliberately: this node exists for names, labels, chat
 * messages and commands, where case is noise more often than signal. When case matters, use
 * <b>Regex Match</b>, whose pattern says exactly what it means. This mirrors the Collections
 * library's <b>Filter by Text</b> down to the mode labels, so testing one string and filtering a
 * list of them never disagree about what "starts with" meant.
 * <p>
 * <b>Why the negated modes exist</b> rather than leaving the caller to invert a boolean: there is
 * no Not node in the host's built-in library, so without them "the message does not mention
 * kitchen" would need an If Bool with its branches crossed over — which reads backwards on the
 * canvas and is easy to mis-wire.
 */
public enum CompareMode implements Modes.Labelled {

    /** The search text appears somewhere in the text. */
    CONTAINS("contains"),
    /** The search text appears nowhere in the text. */
    NOT_CONTAINS("not contains"),
    /** The text begins with the search text. */
    STARTS_WITH("starts with"),
    /** The text ends with the search text. */
    ENDS_WITH("ends with"),
    /** The text and the search text are the same, ignoring case. */
    EQUALS("equals"),
    /** The text and the search text differ. */
    NOT_EQUALS("not equals");

    private final String label;

    CompareMode(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * Parses an authored comparison, defaulting a blank field to {@link #CONTAINS}.
     *
     * @param text the authored mode, possibly null or blank
     * @return the selected mode
     * @throws IllegalArgumentException if the text names no known mode
     */
    public static CompareMode parse(String text) {
        return Modes.parse(values(), text, CONTAINS, "comparison");
    }

    /** @return every mode's label, comma separated. */
    public static String labels() {
        return Modes.labels(values());
    }

    /**
     * Whether {@code text} satisfies this comparison against {@code search}. Both sides are
     * lower-cased first (see the class header). A blank search text satisfies every positive mode
     * and no negated one, which is what {@link String#contains} and friends already do for an
     * empty string — an unfilled Search field therefore reports a match rather than a mystery.
     *
     * @param text   the text being tested, possibly null
     * @param search the text to compare against, possibly null
     * @return true if the comparison holds
     */
    public boolean test(String text, String search) {
        String haystack = fold(text);
        String needle = fold(search);
        return switch (this) {
            case CONTAINS -> haystack.contains(needle);
            case NOT_CONTAINS -> !haystack.contains(needle);
            case STARTS_WITH -> haystack.startsWith(needle);
            case ENDS_WITH -> haystack.endsWith(needle);
            case EQUALS -> haystack.equals(needle);
            case NOT_EQUALS -> !haystack.equals(needle);
        };
    }

    /**
     * Where the search text first appears, ignoring case, or -1 when it does not appear at all.
     * Reported alongside every comparison rather than only the positional ones: the position is
     * what a Substring downstream needs, and it is the same answer whichever mode was selected.
     *
     * @param text   the text being searched, possibly null
     * @param search the text to look for, possibly null
     * @return the index of the first occurrence, or -1
     */
    public static int indexOf(String text, String search) {
        return fold(text).indexOf(fold(search));
    }

    private static String fold(String text) {
        return Texts.orEmpty(text).toLowerCase(Locale.ROOT);
    }
}
