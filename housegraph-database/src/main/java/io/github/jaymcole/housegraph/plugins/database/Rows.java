package io.github.jaymcole.housegraph.plugins.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a row is on each side of the boundary: a {@code Map} on the canvas, a bound statement in
 * SQLite, and back again. Every type decision this library makes is here.
 *
 * <h2>Values keep their types</h2>
 *
 * This is the one place this library deliberately does <em>not</em> copy {@code housegraph-store},
 * whose {@code Documents} stores everything as text. That rule is right for a remembered value,
 * which is nearly always about to be pasted into a message; it is wrong for a column, which is about
 * to be sorted, compared and summed. A number wired in is stored as a number and comes back as one,
 * so {@code ORDER BY} orders numerically rather than alphabetically ({@code "10"} before
 * {@code "9"}).
 * <p>
 * Concretely: integral numbers bind and read back as {@code Long}, fractional ones as
 * {@code Double}, text as {@code String}. <b>A boolean stores as 1 or 0</b> and reads back as a
 * {@code Long}, because SQLite has no boolean type and 1/0 is what every SQL tool that might open
 * this file expects. <b>Anything else stores as its text form</b> — the same {@code String.valueOf}
 * fallback {@code Documents} uses — which is the honest thing to do with an object whose only
 * portable representation is how it prints.
 *
 * <h2>A missing value is an absent entry, not a null</h2>
 *
 * A column holding SQL NULL is left <em>out</em> of the row map rather than mapped to null. That
 * makes <b>Map Get</b>'s found/not-found answer the question <b>Stored Value</b>'s <b>Found</b>
 * output answers, and for the same reason: "recorded nothing" and "recorded the empty string" have
 * to stay distinguishable, and a graph should never be handed a null it has to defend against.
 * <p>
 * Rows come back unmodifiable, for the reason the collections library freezes its own output: a map
 * that flowed down two edges and was mutated by one of them would corrupt the other.
 */
public final class Rows {

    /**
     * The type a row port declares. The cast is the same laundering {@code housegraph-collections}'
     * {@code Maps.TYPE} performs — a {@code NodeVariable}'s type is a {@code Class<T>} and
     * {@code Map.class} is a raw {@code Class<Map>} — and it matters that it is the <em>same</em>
     * erased class, because that is what lets a map built by <b>Build Map</b> wire into a row input.
     */
    @SuppressWarnings("unchecked")
    public static final Class<Map<?, ?>> ROW_TYPE = (Class<Map<?, ?>>) (Class<?>) Map.class;

    /** The type a result-set port declares; erased to {@code List} for the reason above. */
    @SuppressWarnings("unchecked")
    public static final Class<List<?>> ROWS_TYPE = (Class<List<?>>) (Class<?>) List.class;

    private Rows() {
    }

    /**
     * Binds one value into a prepared statement, applying the type rules above.
     *
     * @param statement the statement being filled in
     * @param index     the 1-based parameter index
     * @param value     the value from the graph, possibly null
     * @throws SQLException if the driver rejects the binding
     */
    public static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        switch (value) {
            case null -> statement.setNull(index, Types.NULL);
            case String text -> statement.setString(index, text);
            case Boolean flag -> statement.setLong(index, flag ? 1 : 0);
            case Integer number -> statement.setLong(index, number);
            case Long number -> statement.setLong(index, number);
            case Short number -> statement.setLong(index, number);
            case Byte number -> statement.setLong(index, number);
            case Float number -> statement.setDouble(index, number);
            case Double number -> statement.setDouble(index, number);
            case Number number -> statement.setDouble(index, number.doubleValue());
            default -> statement.setString(index, String.valueOf(value));
        }
    }

    /**
     * The result set's current row as an unmodifiable map, with NULL columns left out.
     *
     * @param results a result set positioned on a row
     * @return the row, keyed by column name, in the order the columns were selected
     * @throws SQLException if the row can't be read
     */
    public static Map<String, Object> read(ResultSet results) throws SQLException {
        ResultSetMetaData metadata = results.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            Object value = normalise(results.getObject(column));
            if (value != null) {
                row.put(metadata.getColumnLabel(column), value);
            }
        }
        return Collections.unmodifiableMap(row);
    }

    /**
     * One value on the way out, narrowed to the three types the graph sees. The driver may hand back
     * an {@code Integer} for a small number and a {@code Long} for a large one purely on magnitude;
     * flattening both to {@code Long} means a graph comparing two ids is not quietly comparing an
     * {@code Integer} against a {@code Long}.
     */
    private static Object normalise(Object value) {
        return switch (value) {
            // A pattern switch throws on a null selector unless it says otherwise, and a NULL column
            // is the ordinary case here — it is what every row written before a column existed holds.
            case null -> null;
            case Integer number -> number.longValue();
            case Short number -> number.longValue();
            case Byte number -> number.longValue();
            case Float number -> number.doubleValue();
            default -> value;
        };
    }
}
