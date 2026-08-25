/**
 * Nodes that convert between an epoch-millisecond timestamp and its calendar date/time fields.
 * <p>
 * <b>Every node here is a pure data node</b> — no flow ports. The engine resolves data by pulling
 * it through data edges, so a conversion runs when something downstream needs its value and needs
 * no flow wired through it. None of them touch the outside world, so there is no outcome to report
 * on a flow output and nothing that would justify the extra wire.
 * <p>
 * <b>Fields are read in the system's local time zone</b> ({@link java.time.ZoneId#systemDefault()}),
 * matching the convention {@code housegraph-schedule}'s Daily Trigger already uses for wall-clock
 * time — a house's automation graph should read "3 PM" the way the house does, not in UTC.
 */
package io.github.jaymcole.housegraph.plugins.datetime.nodes;
