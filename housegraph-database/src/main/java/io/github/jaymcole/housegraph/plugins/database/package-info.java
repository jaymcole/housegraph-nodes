/**
 * The SQLite handling this library's {@link io.github.jaymcole.housegraph.plugins.database.nodes
 * nodes} share, kept out of them so every rule below is testable against a temporary file rather
 * than a running graph.
 * <p>
 * <b>Why a database at all.</b> {@code housegraph-store} holds one JSON document per named store,
 * which is exactly right for one remembered value and wrong for many records: its unit of read and
 * write is the whole dataset, so appending the thousandth reading rewrites the other 999, and
 * "readings from the last hour" has to be done by pulling everything into the graph. That is not
 * something a cleverer key syntax fixes — it needs a storage engine underneath, which is this
 * library.
 * <p>
 * <b>Columns appear as you use them.</b> There is no schema to declare up front:
 * {@link io.github.jaymcole.housegraph.plugins.database.Database#insert} adds a column for any key
 * it has not seen before, in the same transaction as the row. This is affordable only because
 * SQLite is <em>dynamically typed</em> — a column's declared type is an affinity rather than a
 * constraint, and {@code ALTER TABLE … ADD COLUMN} is a metadata-only change whatever the row
 * count — so "I also want to record humidity now" costs one more wired pair and no migration.
 * See {@code docs/design/local-database-storage.md} for the whole of that argument, including how
 * the destructive schema changes (rename, drop) are meant to be handled when they arrive.
 * <p>
 * <b>Nothing here is a control node.</b> The connection lives on a resource node
 * ({@code DatabaseNode}) and everything else takes it as a value, the same shape
 * {@code housegraph-store} uses. No node in this library owns a timer: a row written every minute
 * is a repeating trigger wired into <b>Insert Row</b>.
 */
package io.github.jaymcole.housegraph.plugins.database;
