/**
 * Nodes for storing many records locally. They back onto the sibling {@code database} package's
 * {@link io.github.jaymcole.housegraph.plugins.database.Database}, which states the rules — columns
 * that appear as you use them, values that keep their types, a missing value as an absent map entry
 * — every node here follows.
 * <p>
 * <b>The connection lives on one node.</b> A <b>Database</b> node produces the handle and the others
 * take it as a value, the same shape {@code housegraph-store}'s <b>Data Store</b> and <b>Stored
 * Value</b> use. That is what keeps one database one database: a node that resolved its own path
 * would be a second, invisible place for a graph's records to live, and the two would drift the
 * first time anyone looked.
 * <p>
 * <b>A row is a {@code Map} and a result set is a {@code List} of them</b>, rather than types this
 * library invented. Everything in {@code housegraph-collections} — <b>Build Map</b>, <b>Map Get</b>,
 * <b>Get Item</b>, <b>List Count</b>, <b>Format Each</b> — therefore already works on what comes out
 * of here, which is worth more than a tidier {@code Row} type would be.
 * <p>
 * <b>These are action nodes, not control nodes.</b> <b>Database</b> is the named exception the
 * control-versus-action rule allows — a resource node owning a real connection lifecycle — and the
 * rest have flow inputs and do one thing per firing. Nothing here owns a timer: a reading recorded
 * every minute is a repeating trigger wired into <b>Insert Row</b>, which is the whole point of the
 * split.
 */
package io.github.jaymcole.housegraph.plugins.database.nodes;
