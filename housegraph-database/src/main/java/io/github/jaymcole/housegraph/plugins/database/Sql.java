package io.github.jaymcole.housegraph.plugins.database;

/**
 * Turning names a user typed into SQL that means what they typed. Table and column names reach this
 * library from text fields on the canvas and from the keys of whatever map a graph built, so they
 * are <em>data</em>, and they cannot be concatenated into a statement as-is.
 * <p>
 * <b>Identifiers are quoted, not validated against a pattern.</b> A double-quoted identifier lets a
 * column be called {@code Room Temperature} or {@code °C}, which is what someone naming a column
 * from a Discord message or a spreadsheet header will produce. Rejecting those would push people
 * into sanitising names themselves, badly. The one thing quoting cannot survive is an embedded NUL,
 * which {@link #identifier} refuses outright.
 * <p>
 * <b>Values are never quoted here at all</b> — they are bound as parameters (see {@link Rows#bind}).
 * A library whose tables can be filled from a chat message must have exactly one way to get a value
 * into a statement, and it must be the parameterised one.
 */
public final class Sql {

    private Sql() {
    }

    /**
     * One identifier, double-quoted for SQL, with any embedded quote doubled.
     *
     * @param name the table or column name as the user typed or produced it
     * @return the name as a quoted SQL identifier
     * @throws DatabaseException if the name is null, blank, or contains a NUL character
     */
    public static String identifier(String name) {
        if (name == null || name.isBlank()) {
            throw new DatabaseException("A table or column name is required, but this one is blank");
        }
        if (name.indexOf('\0') >= 0) {
            throw new DatabaseException("The name \"" + name + "\" contains a NUL character, which SQL cannot quote");
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * A literal made safe for a {@code LIKE} pattern, so a search for {@code 50%} finds rows
     * containing "50%" rather than every row containing "50". The escape character is a backslash,
     * which every {@code LIKE} this library builds declares with {@code ESCAPE '\'} — SQLite has no
     * default escape character, so a pattern with a backslash and no ESCAPE clause would treat it
     * as an ordinary character and the escaping would silently do nothing.
     *
     * @param value the text the user is searching for
     * @return the same text with {@code \}, {@code %} and {@code _} escaped
     */
    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
