/**
 * The Alpaca nodes: one resource node that holds the API keys
 * ({@link io.github.jaymcole.housegraph.plugins.alpaca.nodes.AlpacaAccountNode}), a reference node
 * that points at it from elsewhere on the canvas, and — in the three subpackages below — the action
 * nodes that each do one thing with it.
 * <p>
 * <b>The subpackages are the Add Node menu.</b> HouseGraph derives a node's category from its
 * package path below the scanned root, so {@code .market} holds the read-only market-data nodes,
 * {@code .portfolio} what the account holds, and {@code .orders} everything to do with placing and
 * chasing an order. The two resource nodes sit here, at the top of that menu, because they are what
 * everything else is wired to.
 * <p>
 * <b>Nothing here schedules anything.</b> Every action node has a flow-in and runs once when
 * something upstream says so, which is what lets one repeating trigger drive a quote and a different
 * one drive an order check. The account node's Connect/Disconnect are the named exception to that
 * rule — a set of credentials is a connection lifecycle, not a schedule. See {@code CLAUDE.md} and
 * {@code docs/shared/node-library-rules.md}.
 */
package io.github.jaymcole.housegraph.plugins.alpaca.nodes;
