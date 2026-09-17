/**
 * Nodes that read, compare, and convert an epoch-millisecond timestamp.
 * <p>
 * <b>Most nodes here are pure data nodes</b> — no flow ports. The engine resolves data by pulling
 * it through data edges, so a conversion runs when something downstream needs its value and needs
 * no flow wired through it. {@link CurrentDateTimeNode} and {@link MillisToDateTimeNode} are both
 * this shape: neither touches the outside world, so there is no outcome to report on a flow output
 * and nothing that would justify the extra wire.
 * <p>
 * <b>{@link OccurredOnDateNode} is the exception</b>: it branches, so it earns flow ports the way
 * {@code housegraph-database}'s Find Rows does — Yes/No report the outcome of that one comparison,
 * plus a same-question Occurred data output for wiring into something other than flow.
 * <p>
 * <b>Fields are read in the system's local time zone</b> ({@link java.time.ZoneId#systemDefault()}),
 * matching the convention {@code housegraph-schedule}'s Daily Trigger already uses for wall-clock
 * time — a house's automation graph should read "3 PM" the way the house does, not in UTC.
 */
package io.github.jaymcole.housegraph.plugins.datetime.nodes;
