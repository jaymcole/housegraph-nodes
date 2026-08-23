package io.github.jaymcole.housegraph.plugins.string;

import java.util.Locale;

/**
 * The case Change Case converts text to, authored as a plain string on a node input (see
 * {@link Texts} for why it is text and not a dropdown).
 * <p>
 * Every conversion uses {@link Locale#ROOT} rather than the host's default locale. That is
 * deliberate: a graph that upper-cases a device name must behave the same on a machine set to
 * Turkish, where the default-locale rules give a dotted capital I and would quietly stop matching
 * the identifier the graph used to produce.
 */
public enum CaseMode implements Modes.Labelled {

    /** Every character upper-cased. */
    UPPER("upper"),
    /** Every character lower-cased. */
    LOWER("lower"),
    /** The first letter of each whitespace-separated word upper-cased, the rest lower-cased. */
    TITLE("title"),
    /** The first letter of the text upper-cased, everything after it lower-cased. */
    SENTENCE("sentence");

    private final String label;

    CaseMode(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * Parses an authored case, defaulting a blank field to {@link #UPPER}.
     *
     * @param text the authored mode, possibly null or blank
     * @return the selected mode
     * @throws IllegalArgumentException if the text names no known mode
     */
    public static CaseMode parse(String text) {
        return Modes.parse(values(), text, UPPER, "case");
    }

    /** @return every mode's label, comma separated. */
    public static String labels() {
        return Modes.labels(values());
    }

    /**
     * Applies this case conversion.
     *
     * @param text the text to convert, possibly null
     * @return the converted text, never null
     */
    public String apply(String text) {
        String source = Texts.orEmpty(text);
        if (source.isEmpty()) {
            return source;
        }
        return switch (this) {
            case UPPER -> source.toUpperCase(Locale.ROOT);
            case LOWER -> source.toLowerCase(Locale.ROOT);
            case SENTENCE -> capitalise(source.toLowerCase(Locale.ROOT));
            case TITLE -> titleCase(source);
        };
    }

    /** Upper-cases the first character of every whitespace-separated word, lower-casing the rest. */
    private static String titleCase(String text) {
        StringBuilder result = new StringBuilder(text.length());
        boolean startOfWord = true;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isWhitespace(current)) {
                startOfWord = true;
                result.append(current);
            } else if (startOfWord) {
                startOfWord = false;
                result.append(Character.toUpperCase(current));
            } else {
                result.append(Character.toLowerCase(current));
            }
        }
        return result.toString();
    }

    /** Upper-cases the first character, leaving everything after it as it was. */
    private static String capitalise(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
