package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.plugins.database.Database;

/**
 * The two checks every node in this library performs before touching a database, in one place so
 * they say the same thing twice.
 * <p>
 * Both fail loudly rather than treating the missing input as an empty result. For a database that is
 * the important direction: "no rows" and "you never wired the database in" look identical to a
 * graph, so a node that answered the first when it meant the second would let a graph that lost its
 * storage carry on as though the house had simply been quiet.
 */
final class Inputs {

    private Inputs() {
    }

    /** The wired database, or a failure naming the node. */
    static Database requireDatabase(Database database, String node) {
        if (database == null) {
            return fail("No database is wired into " + node + ". Add a Database node and wire its "
                    + "Database output into this one.");
        }
        return database;
    }

    /** The table name, trimmed, or a failure naming the node. */
    static String requireTable(String table, String node) {
        if (table == null || table.isBlank()) {
            return fail(node + " has no Table, so it names nothing to read or write.");
        }
        return table.trim();
    }

    private static <T> T fail(String message) {
        throw new IllegalStateException(message);
    }
}
