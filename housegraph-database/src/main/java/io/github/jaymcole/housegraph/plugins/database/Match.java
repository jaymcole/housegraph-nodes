package io.github.jaymcole.housegraph.plugins.database;

import java.util.Locale;

/**
 * How one {@link Criterion} compares a column against a value, authored as a plain string on a node
 * input — the same choice {@code housegraph-collections}' {@code Comparison} makes, for the same
 * reason: only {@code String}, {@code Integer} and {@code Float} have registered value editors, so
 * anything a person picks on the canvas is text they typed, and {@code >=} is how they would write
 * this down anyway.
 * <p>
 * <b>Equality is null-safe.</b> {@link #EQUALS} and {@link #NOT_EQUALS} compile to SQLite's
 * {@code IS} / {@code IS NOT} rather than {@code =} / {@code !=}. In SQL, {@code col != 'x'} is
 * neither true nor false for a row where {@code col} is NULL, so a plain {@code !=} would silently
 * drop exactly the rows written before that column existed — which, in a library whose columns
 * appear as they are used, is most of them.
 * <p>
 * <b>The text matches are case-insensitive for ASCII</b>, because they compile to {@code LIKE} and
 * that is what SQLite's {@code LIKE} does. Non-ASCII letters are compared exactly.
 * <p>
 * <b>The ordering comparisons order by storage class first</b>: SQLite sorts NULLs before numbers,
 * numbers before text, and text before blobs. So {@code > 3} against a column holding the text
 * {@code "5"} does not match, because text is not compared numerically against a number. Insert
 * numbers as numbers and this never comes up; <b>Parse Number</b> upstream fixes it when the value
 * arrived as text.
 */
public enum Match {

    EQUALS("="),
    NOT_EQUALS("!="),
    LESS("<"),
    LESS_OR_EQUAL("<="),
    GREATER(">"),
    GREATER_OR_EQUAL(">="),
    CONTAINS("contains"),
    STARTS_WITH("starts with"),
    ENDS_WITH("ends with"),
    IS_EMPTY("is empty"),
    IS_NOT_EMPTY("is not empty");

    /** What a user types to select this match, and what the parse failure lists as valid. */
    public final String label;

    Match(String label) {
        this.label = label;
    }

    /**
     * Parses an authored match. Blank text selects {@link #EQUALS} — the one a person means when
     * they have filled in a column and a value and not thought about the operator at all. Word
     * forms and the usual variants are accepted; anything else throws, rather than quietly
     * degrading to equality and returning a confidently wrong set of rows.
     *
     * @param text the authored match, possibly null or blank
     * @return the selected match
     * @throws IllegalArgumentException if the text names no known match
     */
    public static Match parse(String text) {
        if (text == null || text.isBlank()) {
            return EQUALS;
        }
        String normalised = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (normalised) {
            case "=", "==", "eq", "is" -> EQUALS;
            case "!=", "<>", "ne", "is not", "not" -> NOT_EQUALS;
            case "<", "lt" -> LESS;
            case "<=", "=<", "lte" -> LESS_OR_EQUAL;
            case ">", "gt" -> GREATER;
            case ">=", "=>", "gte" -> GREATER_OR_EQUAL;
            case "contains", "has", "like" -> CONTAINS;
            case "starts with", "startswith", "begins with" -> STARTS_WITH;
            case "ends with", "endswith" -> ENDS_WITH;
            case "is empty", "empty", "is null", "missing" -> IS_EMPTY;
            case "is not empty", "not empty", "is not null", "present" -> IS_NOT_EMPTY;
            default -> throw new IllegalArgumentException(
                    "Unknown test \"" + text + "\" - expected one of " + labels());
        };
    }

    /** Whether this match reads a value at all; the two emptiness tests do not. */
    public boolean needsValue() {
        return this != IS_EMPTY && this != IS_NOT_EMPTY;
    }

    /** Every match's label, comma separated — for the node's tooltip and the parse failure message. */
    public static String labels() {
        StringBuilder text = new StringBuilder();
        for (Match match : values()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(match.label);
        }
        return text.toString();
    }
}
