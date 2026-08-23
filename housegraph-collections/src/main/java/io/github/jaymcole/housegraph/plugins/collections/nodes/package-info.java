/**
 * Nodes for building, inspecting, filtering and transforming lists. They back onto the sibling
 * {@code collections} package's {@link io.github.jaymcole.housegraph.plugins.collections.Lists},
 * which states the element-handling rules every node here follows.
 * <p>
 * <b>Everything here is a pure data node with no flow ports</b> — pulled when something downstream
 * needs its output, exactly like the host's constants and converters — with one deliberate
 * exception: {@link io.github.jaymcole.housegraph.plugins.collections.nodes.CollectItemsNode},
 * whose whole purpose is to accumulate across separate firings and which therefore needs Add and
 * Clear flow inputs to tell those firings apart.
 * <p>
 * That is why no node here branches on flow. A node that would ("was the item found?") emits a
 * {@code Boolean} instead, to be wired into the host's <b>If (Boolean)</b> — the composable form
 * of the same graph, and the one the control-versus-action rule in {@code docs/shared/
 * node-library-rules.md} asks for.
 * <p>
 * <b>The two loop-shaped nodes a list library might be expected to carry are absent on purpose.</b>
 * Iteration is the host's <b>For Each</b>; a general map/filter driven by a subgraph body would be
 * a second control node of the same kind, so filtering here is done by parameter (see
 * {@link io.github.jaymcole.housegraph.plugins.collections.nodes.FilterByTextNode} and its
 * siblings) rather than by callback.
 */
package io.github.jaymcole.housegraph.plugins.collections.nodes;
