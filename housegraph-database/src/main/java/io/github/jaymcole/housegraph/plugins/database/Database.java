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
                                 + " AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
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
