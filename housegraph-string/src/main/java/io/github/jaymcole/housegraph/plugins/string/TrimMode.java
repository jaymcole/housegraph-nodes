package io.github.jaymcole.housegraph.plugins.string;

/**
 * Which whitespace Trim Text removes, authored as a plain string on a node input (see
 * {@link Texts} for why it is text and not a dropdown).
 * <p>
 * Trimming is by {@link String#strip()} rather than the older {@code trim()}, so it removes every
 * Unicode whitespace character — including the non-breaking spaces that arrive with text copied
 * out of a web page or a chat client, which {@code trim()} leaves behind and which then break an
 * equality test that looks like it should pass.
 */
public enum TrimMode implements Modes.Labelled {

    /** Whitespace removed from both ends. */
    BOTH("both"),
    /** Whitespace removed from the start only. */
    LEADING("leading"),
    /** Whitespace removed from the end only. */
    TRAILING("trailing"),
    /** Both ends trimmed, and every internal run of whitespace collapsed to a single space. */
    COLLAPSE("collapse");

    private final String label;

    TrimMode(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * Parses an authored trim mode, defaulting a blank field to {@link #BOTH}.
     *
     * @param text the authored mode, possibly null or blank
     * @return the selected mode
     * @throws IllegalArgumentException if the text names no known mode
     */
    public static TrimMode parse(String text) {
        return Modes.parse(values(), text, BOTH, "trim");
    }

    /** @return every mode's label, comma separated. */
    public static String labels() {
        return Modes.labels(values());
    }

    /**
     * Applies this trim.
     *
     * @param text the text to trim, possibly null
     * @return the trimmed text, never null
     */
    public String apply(String text) {
        String source = Texts.orEmpty(text);
        return switch (this) {
            case BOTH -> source.strip();
            case LEADING -> source.stripLeading();
            case TRAILING -> source.stripTrailing();
            case COLLAPSE -> source.strip().replaceAll("\\s+", " ");
        };
    }
}
