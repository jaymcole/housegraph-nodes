package io.github.jaymcole.housegraph.plugins.string;

import java.util.Locale;

/**
 * Parsing for the authored modes of this library — the case to change to, how to trim, how to
 * compare. See {@link Texts} for why a mode is typed as text rather than picked from a checkbox.
 * <p>
 * Written once here rather than three times, because an enum cannot inherit the parse it needs:
 * {@link CaseMode}, {@link TrimMode} and {@link CompareMode} each implement {@link Labelled} and
 * hand their constants to {@link #parse}.
 */
public final class Modes {

    /** A mode that spells out what a user types to select it. */
    public interface Labelled {
        /** @return the label a user types to select this mode. */
        String label();
    }

    private Modes() {
    }

    /**
     * Parses an authored mode. Blank text selects {@code fallback} — the mode a user who left the
     * field alone almost certainly wanted. Everything else is matched after case, spaces,
     * underscores and hyphens are normalised away, so "Starts With", "starts_with" and
     * "startswith" all name the same mode.
     * <p>
     * An unrecognised mode <b>throws</b> rather than falling back. A silent fallback would quietly
     * produce the wrong text for the life of the graph; a failed node says so on the canvas the
     * first time it runs.
     *
     * @param values   every mode of the enum being parsed
     * @param text     the authored mode, possibly null or blank
     * @param fallback the mode a blank field selects
     * @param what     what the modes are of, for the failure message (e.g. "case")
     * @param <E>      the mode enum
     * @return the selected mode
     * @throws IllegalArgumentException if the text names no known mode
     */
    public static <E extends Enum<E> & Labelled> E parse(E[] values, String text, E fallback, String what) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String normalised = normalise(text);
        for (E mode : values) {
            if (normalise(mode.label()).equals(normalised)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unknown " + what + " mode \"" + text + "\" - expected one of " + labels(values));
    }

    /**
     * Every mode's label, comma separated — for a node's tooltip and the parse failure message.
     *
     * @param values every mode of the enum
     * @param <E>    the mode enum
     * @return the labels, comma separated
     */
    public static <E extends Enum<E> & Labelled> String labels(E[] values) {
        StringBuilder text = new StringBuilder();
        for (E mode : values) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(mode.label());
        }
        return text.toString();
    }

    private static String normalise(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }
}
