package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.plugins.database.Criterion;
import io.github.jaymcole.housegraph.plugins.database.Database;

import java.util.List;

/**
 * The checks every node in this library performs before touching a database, in one place so they
 * say the same thing however they are reached.
 * <p>
 * All of them fail loudly rather than treating the missing input as an empty result. For a database that is
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

    /**
     * The conditions, refusing an empty set for the two nodes that change data.
     * <p>
     * SQL reads a missing {@code WHERE} as "every row", which is the correct reading and the wrong
     * default here. A graph's conditions are typed and wired by hand, so an empty set is far more
     * often a half-built node than a decision — and the node runs on a timer, at three in the
     * morning, against a table holding six months of history, with no undo. Making the destructive
     * reading unreachable by accident costs one deliberate condition when someone really does mean
     * every row.
     */
    static List<Criterion> requireConditions(List<Criterion> criteria, String node, String verb, String table) {
        if (criteria.isEmpty()) {
            return fail(node + " has no conditions, so it would " + verb + " every row in \"" + table
                    + "\". Add a condition. To mean every row deliberately, say so with one - "
                    + "Column \"id\", Test \">\", Value 0.");
        }
        return criteria;
    }

    private static <T> T fail(String message) {
        throw new IllegalStateException(message);
    }
}
