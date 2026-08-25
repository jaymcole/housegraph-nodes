package io.github.jaymcole.housegraph.plugins.database;

import java.util.Locale;

/**
 * The ordering of a query, authored as one text field: a column name, optionally followed by
 * {@code desc} — {@code "created_at desc"}, {@code "name"}.
 * <p>
 * One field rather than a column plus a direction, because a direction would want a checkbox and a
 * boolean cannot be typed into a node input (only {@code String}, {@code Integer} and {@code Float}
 * have registered value editors). "Newest first" is the overwhelmingly common ordering in a house's
 * data, and {@code created_at desc} says it in the notation someone who has met a database already
 * knows.
 *
 * @param column     the column to order by
 * @param descending true for {@code desc}
 */
public record Sort(String column, boolean descending) {

    /**
     * Parses an authored ordering. Blank text means "no ordering" and returns null, which leaves the
     * rows in SQLite's own order — worth being explicit about: that is <em>not</em> a promise of
     * insertion order, it is whatever the query planner produces. Order by {@code id} or
     * {@code created_at} when the order matters.
     *
     * @param text the authored ordering, possibly null or blank
     * @return the ordering, or null when none was authored
     */
    public static Sort parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" desc") || lower.endsWith(" descending")) {
            return new Sort(trimmed.substring(0, trimmed.lastIndexOf(' ')).trim(), true);
        }
        if (lower.endsWith(" asc") || lower.endsWith(" ascending")) {
            return new Sort(trimmed.substring(0, trimmed.lastIndexOf(' ')).trim(), false);
        }
        return new Sort(trimmed, false);
    }

    /** This ordering as a SQL fragment, with the column quoted as an identifier. */
    String sql() {
        return Sql.identifier(column) + (descending ? " DESC" : " ASC");
    }
}
