package io.github.jaymcole.housegraph.plugins.database;

import java.util.List;

/**
 * One condition in a {@code WHERE} clause: a column, a {@link Match}, and the value to compare
 * against. A row of the growing <b>Where</b> section on <b>Find Rows</b> becomes one of these.
 * <p>
 * <b>A criterion is either complete or absent — never half-filled.</b> A blank column is an unfilled
 * spare and the node skips it before it ever gets here (the same rule <b>Build Map</b> applies to a
 * trailing empty pair). A criterion that names a column but has nothing wired into its value is the
 * dangerous case and {@link #of} refuses it: silently dropping the condition would widen the match
 * instead of narrowing it, which for <b>Find Rows</b> means quietly returning the whole table, and
 * for the delete and update nodes this design leads to would mean touching every row in it. The
 * only conditions that legitimately have no value are {@link Match#IS_EMPTY} and
 * {@link Match#IS_NOT_EMPTY}.
 *
 * @param column the column name as the user typed it
 * @param match  how the column is compared
 * @param value  the value compared against, or null for the emptiness tests
 */
public record Criterion(String column, Match match, Object value) {

    /**
     * A criterion, with the half-filled case refused.
     *
     * @param column the column name; must not be blank
     * @param match  how to compare
     * @param value  the value to compare against; required unless {@code match} is an emptiness test
     * @return the criterion
     * @throws DatabaseException if the column is blank, or a value is needed and missing
     */
    public static Criterion of(String column, Match match, Object value) {
        if (column == null || column.isBlank()) {
            throw new DatabaseException("A condition needs a column name");
        }
        if (match.needsValue() && value == null) {
            throw new DatabaseException("The condition on \"" + column.trim() + "\" (" + match.label
                    + ") has nothing wired into its value. Wire one, or use \"" + Match.IS_EMPTY.label
                    + "\" to look for rows that have no value there.");
        }
        return new Criterion(column.trim(), match, value);
    }

    /** This criterion as a SQL fragment with {@code ?} placeholders, to be joined with AND. */
    String sql() {
        String column = Sql.identifier(this.column);
        return switch (match) {
            // IS / IS NOT rather than = / != so a row written before this column existed still
            // compares as "not equal to x" instead of dropping out of the result entirely.
            case EQUALS -> column + " IS ?";
            case NOT_EQUALS -> column + " IS NOT ?";
            case LESS -> column + " < ?";
            case LESS_OR_EQUAL -> column + " <= ?";
            case GREATER -> column + " > ?";
            case GREATER_OR_EQUAL -> column + " >= ?";
            case CONTAINS, STARTS_WITH, ENDS_WITH -> column + " LIKE ? ESCAPE '\\'";
            // A column that was never written and one holding "" are both "empty" to a graph;
            // distinguishing them here would expose a SQL distinction the canvas never makes.
            case IS_EMPTY -> "(" + column + " IS NULL OR " + column + " = '')";
            case IS_NOT_EMPTY -> "(" + column + " IS NOT NULL AND " + column + " != '')";
        };
    }

    /** The values to bind for {@link #sql()}'s placeholders, in order — empty for the emptiness tests. */
    List<Object> bindings() {
        return switch (match) {
            case IS_EMPTY, IS_NOT_EMPTY -> List.of();
            case CONTAINS -> List.of("%" + Sql.escapeLike(String.valueOf(value)) + "%");
            case STARTS_WITH -> List.of(Sql.escapeLike(String.valueOf(value)) + "%");
            case ENDS_WITH -> List.of("%" + Sql.escapeLike(String.valueOf(value)));
            default -> List.of(value);
        };
    }
}
