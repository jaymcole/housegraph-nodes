/**
 * Nodes for building, inspecting, filtering and transforming lists — the ordered, repeats-allowed
 * collection, and the one the host's <b>For Each</b> iterates. They back onto
 * {@link io.github.jaymcole.housegraph.plugins.collections.Lists}, which states the
 * element-handling rules every node here follows.
 * <p>
 * The conventions worth knowing before wiring one up:
 * <ul>
 *   <li><b>Absent and empty are the same case.</b> An unwired List input reads as an empty list, so
 *       a node reports zero rather than failing.</li>
 *   <li><b>Nothing is edited in place.</b> Every node that changes a list publishes a new,
 *       unmodifiable one and leaves its input alone.</li>
 *   <li><b>A negative index counts back from the end</b> ({@code -1} is the last entry), so "the
 *       most recent one" needs no Count node.</li>
 *   <li><b>An Item field is text</b> where the node is looking something <em>up</em>, and
 *       {@code Object} where it is a value to <em>keep</em> — see <b>Append Item</b> for why the
 *       two differ.</li>
 * </ul>
 * <p>
 * See the parent package for the rules this shares with {@code maps} and {@code sets}: no flow
 * branching, no loop-shaped nodes, and one forgiving identity rule throughout.
 */
package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;
