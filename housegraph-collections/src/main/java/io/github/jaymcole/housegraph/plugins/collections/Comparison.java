package io.github.jaymcole.housegraph.plugins.collections;

import java.util.Locale;

/**
 * How <b>Filter by Number</b> compares each numeric element against the threshold it was given,
 * authored as a plain string on a node input — for the same reason {@link TextMatch} is a string
 * rather than a set of boolean flags, and because {@code "> 20"} is how a person would write this
 * down anyway.
 * <p>
 * Comparison is done in {@code double}, whatever the elements actually were (see
 * {@link Lists#number}). {@link #EQUAL} and {@link #NOT_EQUAL} therefore carry the usual
 * floating-point caveat: they test exact equality of the parsed values, so a computed
 * {@code 0.1 + 0.2} will not equal an authored {@code 0.3}. Prefer a range — two filters, or a
 * {@code >=} — when the values are computed rather than authored.
 */
public enum Comparison {

    GREATER(">"),
    GREATER_OR_EQUAL(">="),
    LESS("<"),
    LESS_OR_EQUAL("<="),
    EQUAL("=="),
    NOT_EQUAL("!=");

    /** What a user types to select this comparison, and what the error message lists as valid. */
    public final String label;

    Comparison(String label) {
        this.label = label;
    }

    /**
     * Parses an authored comparison. Blank text selects {@link #GREATER}. Beyond the symbols, the
     * word forms {@code gt}, {@code gte}, {@code lt}, {@code lte}, {@code eq} and {@code ne} are
     * accepted, as is a bare {@code =} for equality — the variants people actually type. An
     * unrecognised comparison throws, for the reason spelled out in {@link TextMatch#parse}.
     *
     * @param text the authored comparison, possibly null or blank
     * @return the selected comparison
     * @throws IllegalArgumentException if the text names no known comparison
     */
    public static Comparison parse(String text) {
        if (text == null || text.isBlank()) {
            return GREATER;
        }
        String normalised = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return switch (normalised) {
            case ">", "gt" -> GREATER;
            case ">=", "gte", "=>" -> GREATER_OR_EQUAL;
            case "<", "lt" -> LESS;
            case "<=", "lte", "=<" -> LESS_OR_EQUAL;
            case "==", "=", "eq" -> EQUAL;
            case "!=", "<>", "ne" -> NOT_EQUAL;
            default -> throw new IllegalArgumentException(
                    "Unknown comparison \"" + text + "\" - expected one of " + labels());
        };
    }

    /**
     * Whether {@code value} stands in this relation to {@code threshold}.
     *
     * @param value     the element's numeric value
     * @param threshold the authored threshold
     * @return true if the element should be kept
     */
    public boolean test(double value, double threshold) {
        return switch (this) {
            case GREATER -> value > threshold;
            case GREATER_OR_EQUAL -> value >= threshold;
            case LESS -> value < threshold;
            case LESS_OR_EQUAL -> value <= threshold;
            case EQUAL -> value == threshold;
            case NOT_EQUAL -> value != threshold;
        };
    }

    /** Every comparison's label, comma separated - for the node's tooltip and the parse failure message. */
    public static String labels() {
        StringBuilder text = new StringBuilder();
        for (Comparison comparison : values()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(comparison.label);
        }
        return text.toString();
    }
}
