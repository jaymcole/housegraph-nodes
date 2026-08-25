package io.github.jaymcole.housegraph.plugins.database;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One SQLite database file, and every operation this library performs on it. Obtained from
 * {@link Databases#forFile}, never constructed directly, so two nodes naming the same database
 * share one of these — see that class for why that has to be true.
 *
 * <h2>Columns appear as you use them</h2>
 *
 * {@link #insert} adds a column for any key it has not seen, in the same transaction as the row.
 * There is nothing to declare, and no migration to run when a graph starts recording one more
 * thing: existing rows simply have no value in the new column, and read back without that entry
 * (see {@link Rows}). Two properties of SQLite make that affordable rather than reckless —
 * {@code ALTER TABLE … ADD COLUMN} is a metadata-only change whatever the row count, and a column's
 * declared type is an <em>affinity</em> rather than a constraint, so a column added for one kind of
 * value cannot later be "the wrong type" for another. Columns are therefore declared with no type
 * at all, which is the honest description of what they hold.
 * <p>
 * This is also why nothing here rewrites a table. Renames, drops and type changes are the schema
 * changes that need a real migration, they are rare, and they destroy data when they are wrong —
 * so they belong to an explicit editor action with a file copy behind it, not to anything that can
 * run on a timer at 3am. See {@code docs/design/local-database-storage.md}.
 *
 * <h2>Two columns are always there</h2>
 *
 * Every table this library creates has an {@code id} and a {@code created_at}. {@code id} is a
 * SQLite {@code INTEGER PRIMARY KEY AUTOINCREMENT}, returned by {@link #insert}, so a row can be
 * addressed exactly later without a query that might match two; {@code AUTOINCREMENT} rather than a
 * plain rowid alias so an id is never reused after a delete, because an id that means one row today
 * and a different row next month is the kind of bug nobody finds. {@code created_at} is epoch
 * milliseconds, filled in when a row does not supply its own — the column every home graph turns out
 * to want and nobody remembers to wire.
 *
 * <h2>Threading</h2>
 *
 * One connection, and every method here synchronised on this object. A graph can reach the same
 * database from several nodes at once ({@code ExecutionPolicy.PARALLEL}, two Discord commands
 * landing together), and the shared-instance rule means those are all this one object. Unlike the
 * JSON document store, that is not the last line of defence: the file is opened in WAL mode with a
 * busy timeout, so another process holding the file — a graph in a second HouseGraph, a database
 * browser someone has open — is waited for rather than clobbered.
 */
public final class Database {

    /** The primary key every table gets; {@link #insert} returns the value written to it. */
    public static final String ID_COLUMN = "id";

    /** Epoch milliseconds, filled in on insert when the row does not supply its own. */
    public static final String CREATED_AT_COLUMN = "created_at";

    /** Prefix of this library's own bookkeeping tables, hidden from {@link #tables()}. */
    private static final String META_TABLE_PREFIX = "_housegraph_";

    /** The table recording what schema changes have been applied, and when. */
    private static final String MIGRATIONS_TABLE = META_TABLE_PREFIX + "migrations";

    /**
     * This library's on-disk layout version, stamped into {@code PRAGMA user_version}. It lives in
     * the file rather than in the graph save on purpose: the file outlives any node, is opened by
     * more than one graph, and can be edited from outside — so a version held in a save would
     * disagree with reality the first time any of that happened. Nothing reads it yet; it is here so
     * that a future layout change has somewhere to look before it touches anyone's data.
     */
    private static final int LAYOUT_VERSION = 1;

    /** Columns this library relies on, which it therefore will not let the editor rename or drop. */
    private static final Set<String> STRUCTURAL_COLUMNS = Set.of(ID_COLUMN, CREATED_AT_COLUMN);

    /** How long to wait for another connection's write lock before failing (milliseconds). */
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path file;

    /**
     * Columns known per table, so the ordinary insert costs no schema query. Only ever consulted to
     * decide that nothing needs adding; the moment a key looks new the table is re-read from
     * {@code PRAGMA table_info} before anything is altered, so a column added from outside this
     * process (a database browser, another HouseGraph) cannot make this cache wrong in a way that
     * matters.
     */
    private final Map<String, Set<String>> knownColumns = new HashMap<>();

    private Connection connection;

    /** Package-private: {@link Databases#forFile} is the way to get one. */
    Database(Path file) {
        this.file = file;
    }

    /** The database file on disk. Its {@code -wal} and {@code -shm} siblings are SQLite's working files. */
    public Path file() {
        return file;
    }

    /**
     * Appends a row, creating the table and any missing columns as part of the same transaction.
     * <p>
     * Keys are the column names. A blank key is an unfilled field rather than a column, and a null
     * value is "nothing to record" rather than a stored null — both are skipped, so a half-built
     * <b>Build Map</b> upstream cannot put a mystery column in the table.
     *
     * @param table the table name, created on first insert
     * @param row   the row's values by column name
     * @return the {@code id} of the row written
     * @throws DatabaseException if the row can't be written
     */
    public long insert(String table, Map<?, ?> row) {
        Map<String, Object> values = columnValues(row);
        values.putIfAbsent(CREATED_AT_COLUMN, System.currentTimeMillis());
        synchronized (this) {
            Connection connection = connection();
            return inTransaction(connection, () -> {
                createTable(connection, table);
                addMissingColumns(connection, table, values.keySet());
                return insertRow(connection, table, values);
            }, "write a row to \"" + table + "\"");
        }
    }

    /**
     * The rows matching every criterion, in the given order.
     * <p>
     * <b>A table nobody has written to yet is empty, not an error</b> — the same forgiveness
     * {@code JsonDocumentStore} extends to a missing file, and for the same reason: on a graph's
     * first run, "nothing there yet" is the normal state rather than a problem to stop on.
     *
     * @param table    the table to read
     * @param criteria the conditions, ANDed together; empty matches every row
     * @param sort     the ordering, or null for none (see {@link Sort#parse})
     * @param limit    the most rows to return, or 0 or less for all of them
     * @return the matching rows, unmodifiable, oldest-to-newest only if {@code sort} says so
     * @throws DatabaseException if the query can't be run
     */
    public List<Map<String, Object>> find(String table, List<Criterion> criteria, Sort sort, int limit) {
        synchronized (this) {
            Connection connection = connection();
            if (!hasTable(table)) {
                return List.of();
            }
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(Sql.identifier(table));
            List<Object> bindings = appendWhere(sql, criteria);
            if (sort != null) {
                sql.append(" ORDER BY ").append(sort.sql());
            }
            if (limit > 0) {
                sql.append(" LIMIT ?");
                bindings.add((long) limit);
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bindAll(statement, bindings);
                try (ResultSet results = statement.executeQuery()) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (results.next()) {
                        rows.add(Rows.read(results));
                    }
                    return List.copyOf(rows);
                }
            } catch (SQLException e) {
                throw failure("read rows from \"" + table + "\"", e);
            }
        }
    }

    /**
     * Changes the given columns on every row matching the criteria, adding any column the table
     * doesn't have yet — the same inference {@link #insert} performs, so "record who last did this
     * chore" works on a table that has never had a {@code who} column.
     * <p>
     * <b>A null value changes nothing</b>, exactly as it stores nothing on insert: an unwired input
     * must not be able to overwrite stored data with a null. Set a column to the empty string to
     * clear it — that is what {@link Match#IS_EMPTY} treats as empty anyway.
     * <p>
     * <b>Empty criteria update every row.</b> That is what {@code UPDATE} without a {@code WHERE}
     * means, and it is the caller's business to be sure: the node refuses it (see the nodes package),
     * because a condition set that came out empty by accident is how a graph on a timer overwrites a
     * whole table.
     *
     * @param table    the table to change
     * @param criteria the conditions, ANDed together; empty matches every row
     * @param values   the new values by column name
     * @return how many rows changed
     * @throws DatabaseException if nothing is being set, or the update can't be run
     */
    public int update(String table, List<Criterion> criteria, Map<?, ?> values) {
        Map<String, Object> changes = columnValues(values);
        if (changes.isEmpty()) {
            throw new DatabaseException("There is nothing to set, so updating \"" + table + "\" would do nothing");
        }
        synchronized (this) {
            Connection connection = connection();
            if (!hasTable(table)) {
                return 0;
            }
            return inTransaction(connection, () -> {
                addMissingColumns(connection, table, changes.keySet());
                StringBuilder sql = new StringBuilder("UPDATE ").append(Sql.identifier(table)).append(" SET ");
                List<Object> bindings = new ArrayList<>();
                boolean first = true;
                for (Map.Entry<String, Object> change : changes.entrySet()) {
                    if (!first) {
                        sql.append(", ");
                    }
                    sql.append(Sql.identifier(change.getKey())).append(" = ?");
                    bindings.add(change.getValue());
                    first = false;
                }
                bindings.addAll(appendWhere(sql, criteria));
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    bindAll(statement, bindings);
                    return statement.executeUpdate();
                }
            }, "update rows in \"" + table + "\"");
        }
    }

    /**
     * Removes every row matching the criteria.
     * <p>
     * <b>Empty criteria delete every row</b>, which is what {@code DELETE} without a {@code WHERE}
     * means. The node refuses that — see {@link #update} for why — and this does not, because the SQL
     * nodes exist for the deliberate case.
     *
     * @param table    the table to delete from
     * @param criteria the conditions, ANDed together; empty matches every row
     * @return how many rows went
     * @throws DatabaseException if the delete can't be run
     */
    public int delete(String table, List<Criterion> criteria) {
        synchronized (this) {
            Connection connection = connection();
            if (!hasTable(table)) {
                return 0;
            }
            StringBuilder sql = new StringBuilder("DELETE FROM ").append(Sql.identifier(table));
            List<Object> bindings = appendWhere(sql, criteria);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bindAll(statement, bindings);
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw failure("delete rows from \"" + table + "\"", e);
            }
        }
    }

    /**
     * How many rows the table holds, or 0 when it doesn't exist yet.
     *
     * @param table the table to count
     * @return the row count
     * @throws DatabaseException if the count can't be run
     */
    public long rowCount(String table) {
        synchronized (this) {
            Connection connection = connection();
            if (!hasTable(table)) {
                return 0;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + Sql.identifier(table))) {
                return results.next() ? results.getLong(1) : 0;
            } catch (SQLException e) {
                throw failure("count the rows in \"" + table + "\"", e);
            }
        }
    }

    /**
     * Every table in the database, in name order. SQLite's own bookkeeping tables are left out.
     *
     * @return the table names
     */
    public List<String> tables() {
        synchronized (this) {
            Connection connection = connection();
            try (Statement statement = connection.createStatement();
                 ResultSet results = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table'"
                                 + " AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '"
                                 + META_TABLE_PREFIX + "%' ORDER BY name")) {
                List<String> tables = new ArrayList<>();
                while (results.next()) {
                    tables.add(results.getString(1));
                }
                return List.copyOf(tables);
            } catch (SQLException e) {
                throw failure("list the tables", e);
            }
        }
    }

    /**
     * A table's columns, in the order they were added. Empty when the table doesn't exist.
     *
     * @param table the table to describe
     * @return the column names
     */
    public List<String> columns(String table) {
        synchronized (this) {
            return List.copyOf(readColumns(connection(), table));
        }
    }

    /** Whether the table exists. */
    public boolean hasTable(String table) {
        synchronized (this) {
            try (PreparedStatement statement = connection().prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
                statement.setString(1, table);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next();
                }
            } catch (SQLException e) {
                throw failure("check whether \"" + table + "\" exists", e);
            }
        }
    }

    // --- Schema changes -----------------------------------------------------------------------
    //
    // Everything above this line either adds a column or leaves the schema alone. Everything below
    // it destroys something, and is therefore meant to be reached from a person clicking in the
    // Database node's column editor rather than from anything a graph can run: a migration that
    // executed as a side effect of process() would re-apply itself on every tick, so one typo'd
    // column name would be dropped again and again, on a timer, with nobody watching.
    //
    // Each one copies the file first. See #backup.

    /**
     * How many rows have a value in this column — the blast radius of dropping it, and the number
     * the editor puts in front of someone before they do. Empty text counts as no value, for the
     * same reason {@link Match#IS_EMPTY} treats it as empty.
     *
     * @param table  the table
     * @param column the column
     * @return how many rows would lose something
     */
    public int valuesIn(String table, String column) {
        synchronized (this) {
            Connection connection = connection();
            if (!hasTable(table) || !readColumns(connection, table).contains(column)) {
                return 0;
            }
            String sql = "SELECT COUNT(*) FROM " + Sql.identifier(table)
                    + " WHERE " + Sql.identifier(column) + " IS NOT NULL AND " + Sql.identifier(column) + " != ''";
            try (Statement statement = connection.createStatement();
                 ResultSet results = statement.executeQuery(sql)) {
                return results.next() ? results.getInt(1) : 0;
            } catch (SQLException e) {
                throw failure("count the values in \"" + column + "\"", e);
            }
        }
    }

    /**
     * Renames a column, keeping every value in it.
     *
     * @param table the table
     * @param from  the column's current name
     * @param to    its new name
     * @return where the pre-change copy of the database was written
     * @throws DatabaseException if the column is structural, the new name is taken, or the rename fails
     */
    public Path renameColumn(String table, String from, String to) {
        String target = to == null ? "" : to.trim();
        synchronized (this) {
            requireChangeable(table, from);
            if (target.isBlank()) {
                throw new DatabaseException("A column needs a name to be renamed to");
            }
            if (readColumns(connection(), table).contains(target)) {
                throw new DatabaseException("\"" + table + "\" already has a column called \"" + target + "\"");
            }
            Path copy = backup();
            alter(table, "RENAME COLUMN " + Sql.identifier(from) + " TO " + Sql.identifier(target),
                    "rename " + from + " to " + target + " in " + table);
            return copy;
        }
    }

    /**
     * Drops a column, and every value in it. Ask {@link #valuesIn} first and show the answer to
     * whoever is about to do this.
     *
     * @param table  the table
     * @param column the column to drop
     * @return where the pre-change copy of the database was written
     * @throws DatabaseException if the column is structural, or SQLite refuses the drop
     */
    public Path dropColumn(String table, String column) {
        synchronized (this) {
            requireChangeable(table, column);
            Path copy = backup();
            // SQLite refuses to drop a column that a PRIMARY KEY, a UNIQUE constraint, an index, a
            // view or a trigger depends on, and says which. Nothing this library creates is in that
            // position, but a table someone made in a database browser can be, so the failure is
            // reported rather than worked around with a table rebuild - a rebuild that guessed at
            // constraints it did not create is a worse outcome than a clear refusal.
            alter(table, "DROP COLUMN " + Sql.identifier(column), "drop " + column + " from " + table);
            return copy;
        }
    }

    /**
     * A complete, consistent copy of the database, written beside it as
     * {@code <name>.backup-<timestamp>.db}.
     * <p>
     * {@code VACUUM INTO} rather than a file copy, because in WAL mode the {@code .db} file on its
     * own is not the current database — the most recent commits are still in the {@code -wal}, and
     * copying the one file without the other would produce a backup silently missing them. This is
     * also why the copy happens through SQLite rather than through {@link Files#copy}.
     *
     * @return the path written
     * @throws DatabaseException if the copy can't be made
     */
    public Path backup() {
        synchronized (this) {
            Connection connection = connection();
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
                    .format(Instant.now());
            String name = file.getFileName().toString().replaceFirst("\\.db$", "");
            Path copy = file.resolveSibling(name + ".backup-" + stamp + ".db");
            for (int attempt = 2; Files.exists(copy); attempt++) {
                copy = file.resolveSibling(name + ".backup-" + stamp + "-" + attempt + ".db");
            }
            // VACUUM cannot run inside a transaction, so this deliberately does not go through
            // inTransaction.
            try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, copy.toAbsolutePath().toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure("copy the database before changing its schema", e);
            }
            return copy;
        }
    }

    /**
     * What has been done to this database's schema, newest first, as lines for the editor to show.
     * Kept in the file next to the data it describes, for the reason {@link #LAYOUT_VERSION} is.
     *
     * @return the applied changes, newest first
     */
    public List<String> migrations() {
        synchronized (this) {
            Connection connection = connection();
            if (!hasTable(MIGRATIONS_TABLE)) {
                return List.of();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet results = statement.executeQuery("SELECT applied_at, change FROM "
                         + Sql.identifier(MIGRATIONS_TABLE) + " ORDER BY applied_at DESC, id DESC")) {
                List<String> lines = new ArrayList<>();
                DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(ZoneId.systemDefault());
                while (results.next()) {
                    lines.add(format.format(Instant.ofEpochMilli(results.getLong(1))) + "  " + results.getString(2));
                }
                return List.copyOf(lines);
            } catch (SQLException e) {
                throw failure("read the schema history", e);
            }
        }
    }

    /** Runs one ALTER TABLE and records it, as a single transaction. */
    private void alter(String table, String change, String description) {
        Connection connection = connection();
        inTransaction(connection, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + Sql.identifier(table) + " " + change);
            }
            record(connection, description);
            // The cache describes a schema that no longer exists.
            knownColumns.remove(table);
            return null;
        }, description);
    }

    /** Appends to the migration log, creating it (and stamping the layout version) on first use. */
    private void record(Connection connection, String description) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Sql.identifier(MIGRATIONS_TABLE) + " ("
                    + Sql.identifier(ID_COLUMN) + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "applied_at INTEGER, change TEXT)");
            statement.executeUpdate("PRAGMA user_version = " + LAYOUT_VERSION);
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO "
                + Sql.identifier(MIGRATIONS_TABLE) + " (applied_at, change) VALUES (?, ?)")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, description);
            statement.executeUpdate();
        }
    }

    /**
     * Refuses a change to a column this library depends on. {@code id} is how a row is addressed and
     * {@code created_at} is re-created by the next insert anyway, so renaming or dropping either
     * produces a database that looks changed and behaves as though it were not — the worst of the
     * available outcomes.
     */
    private void requireChangeable(String table, String column) {
        if (!hasTable(table)) {
            throw new DatabaseException("There is no table called \"" + table + "\" to change");
        }
        if (column == null || column.isBlank()) {
            throw new DatabaseException("No column was named");
        }
        if (STRUCTURAL_COLUMNS.contains(column)) {
            throw new DatabaseException("\"" + column + "\" is part of how every table here works and cannot be "
                    + "renamed or dropped: id is how a row is addressed, and created_at would be re-created by "
                    + "the next insert.");
        }
        if (!readColumns(connection(), table).contains(column)) {
            throw new DatabaseException("\"" + table + "\" has no column called \"" + column + "\"");
        }
    }

    /**
     * Closes the connection. <b>Not called when a node is removed</b> — the instance is shared
     * process-wide and outlives any one node, exactly as {@code DocumentStores}' instances do, so a
     * node closing it on removal would break every other node still using it. This exists for tests
     * and for a future explicit "close this database" action.
     */
    public void close() {
        synchronized (this) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    throw failure("close the database", e);
                } finally {
                    connection = null;
                    knownColumns.clear();
                }
            }
        }
    }

    // --- Statement building ------------------------------------------------------------------

    /** Appends {@code WHERE …} for the criteria, returning the values to bind in order. */
    private static List<Object> appendWhere(StringBuilder sql, List<Criterion> criteria) {
        List<Object> bindings = new ArrayList<>();
        if (criteria == null || criteria.isEmpty()) {
            return bindings;
        }
        sql.append(" WHERE ");
        for (int i = 0; i < criteria.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(criteria.get(i).sql());
            bindings.addAll(criteria.get(i).bindings());
        }
        return bindings;
    }

    private static void bindAll(PreparedStatement statement, List<Object> bindings) throws SQLException {
        for (int i = 0; i < bindings.size(); i++) {
            Rows.bind(statement, i + 1, bindings.get(i));
        }
    }

    /**
     * The row's usable entries, keyed by trimmed column name. See {@link #insert} for why blank keys
     * and null values are dropped rather than stored.
     */
    private static Map<String, Object> columnValues(Map<?, ?> row) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (row == null) {
            return values;
        }
        for (Map.Entry<?, ?> entry : row.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String column = String.valueOf(entry.getKey()).trim();
            if (!column.isEmpty()) {
                values.put(column, entry.getValue());
            }
        }
        return values;
    }

    // --- Schema ------------------------------------------------------------------------------

    private void createTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Sql.identifier(table) + " ("
                    + Sql.identifier(ID_COLUMN) + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    // Declared with no type, like every inferred column: the value's own storage
                    // class is what SQLite keeps, and a declared type here would only be an affinity
                    // pretending to be a promise.
                    + Sql.identifier(CREATED_AT_COLUMN) + ")");
        }
    }

    /** Adds any column the table doesn't have yet. Cheap when there are none: no query at all. */
    private void addMissingColumns(Connection connection, String table, Set<String> wanted) throws SQLException {
        Set<String> known = knownColumns.get(table);
        if (known != null && known.containsAll(wanted)) {
            return;
        }
        known = readColumns(connection, table);
        knownColumns.put(table, known);
        for (String column : wanted) {
            if (known.contains(column)) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + Sql.identifier(table)
                        + " ADD COLUMN " + Sql.identifier(column));
            }
            known.add(column);
        }
    }

    /** A table's columns as a mutable set, straight from SQLite rather than from the cache. */
    private Set<String> readColumns(Connection connection, String table) {
        Set<String> columns = new LinkedHashSet<>();
        // PRAGMA takes no bind parameters, so the table name goes in as a quoted identifier — which
        // is what Sql.identifier exists for.
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("PRAGMA table_info(" + Sql.identifier(table) + ")")) {
            while (results.next()) {
                columns.add(results.getString("name"));
            }
        } catch (SQLException e) {
            throw failure("read the columns of \"" + table + "\"", e);
        }
        return columns;
    }

    private long insertRow(Connection connection, String table, Map<String, Object> values) throws SQLException {
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        for (String column : values.keySet()) {
            if (columns.length() > 0) {
                columns.append(", ");
                placeholders.append(", ");
            }
            columns.append(Sql.identifier(column));
            placeholders.append('?');
        }
        String sql = "INSERT INTO " + Sql.identifier(table) + " (" + columns + ") VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindAll(statement, new ArrayList<>(values.values()));
            statement.executeUpdate();
        }
        // last_insert_rowid() rather than getGeneratedKeys(): it is connection-scoped, this method
        // holds the only connection and the instance monitor, and it is one fewer driver behaviour
        // to depend on.
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT last_insert_rowid()")) {
            return results.next() ? results.getLong(1) : 0;
        }
    }

    // --- Connection ---------------------------------------------------------------------------

    /**
     * The open connection, opening the file on first use.
     * <p>
     * The connection comes from {@link SQLiteDataSource} rather than {@code DriverManager}, which
     * resolves a {@code jdbc:sqlite:} URL against every driver registered in the class loader every
     * installed node library shares. Naming the driver this library bundled means a second library
     * bundling SQLite one day cannot silently answer for these connections.
     */
    private Connection connection() {
        if (connection != null) {
            return connection;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the folder for database " + file, e);
        }
        SQLiteConfig config = new SQLiteConfig();
        // Readers don't block the writer and the writer doesn't block readers — which is what makes
        // a graph node, a second HouseGraph and a database browser able to hold this file at once.
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // Safe under WAL (a crash cannot corrupt the database, it can only lose the last commits)
        // and much kinder to the SD card in a Raspberry Pi than a full fsync per write.
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            connection = source.getConnection();
        } catch (SQLException e) {
            throw failure("open the database " + file, e);
        }
        return connection;
    }

    /** Runs the work as one transaction, rolling back and reporting what was being attempted. */
    private <T> T inTransaction(Connection connection, SqlWork<T> work, String what) {
        try {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException e) {
            throw failure(what, e);
        }
    }

    private static DatabaseException failure(String what, SQLException cause) {
        return new DatabaseException("Could not " + what + ": " + cause.getMessage(), cause);
    }

    /** A unit of work against an open connection, allowed to throw {@link SQLException}. */
    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }
}
