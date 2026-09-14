/**
 * The Robinhood nodes: one resource node that owns the login
 * ({@link io.github.jaymcole.housegraph.plugins.robinhood.nodes.RobinhoodAccountNode}), a reference
 * node that points at it from elsewhere on the canvas, and seven action nodes that each do one
 * thing with it — quote a symbol, read the account, list holdings, place an order, check it, cancel
 * it, list recent ones.
 * <p>
 * <b>Nothing here schedules anything.</b> Every action node has a flow-in and runs once when
 * something upstream says so, which is what lets one repeating trigger drive a quote and a
 * different one drive an order check. The account node's Connect/Disconnect are the named exception
 * to that rule — a login is a connection lifecycle, not a schedule. See {@code CLAUDE.md} and
 * {@code docs/shared/node-library-rules.md}.
 */
package io.github.jaymcole.housegraph.plugins.robinhood.nodes;
