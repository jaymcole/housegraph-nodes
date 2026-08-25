package io.github.jaymcole.housegraph.plugins.database;

/**
 * A database operation that failed, as an unchecked exception carrying a message aimed at the
 * person looking at the canvas rather than at a JDBC stack trace.
 * <p>
 * Node {@code process()} methods report failure by throwing (the engine catches it, marks the node
 * failed and logs it), so every {@link java.sql.SQLException} this library provokes is wrapped here
 * with what was being attempted — the table, the column, the operation — because "[SQLITE_ERROR]
 * SQL error or missing database" on its own names nothing the user wired.
 */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(String message) {
        super(message);
    }
}
