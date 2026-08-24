/**
 * The library's nodes, in one subpackage per collection: {@code lists}, {@code maps} and
 * {@code sets}. Each subpackage's own {@code package-info} states the conventions its nodes
 * follow; the rules they all share are below.
 * <p>
 * <b>The subpackages are not only for reading the source.</b> {@code NodeRegistry} scans this
 * package recursively and derives a node's Add-Node menu category from its path <em>below the
 * scan root</em>, nested under the library's category prefix — so a class in {@code nodes.maps}
 * appears under <b>collections &rarr; maps</b>, and is reachable in search as
 * {@code cat:collections.maps}. The subpackage name is user-visible surface, which is why
 * {@code build.gradle} declares the single root package {@code …collections.nodes} rather than
 * listing the three subpackages: naming each one as its own scan root would make every node's
 * category relative to <em>it</em>, collapsing all three back into one flat <b>collections</b>
 * menu.
 * <p>
 * <b>Everything here is a pure data node with no flow ports</b> — pulled when something downstream
 * needs its output, exactly like the host's constants and converters — with two deliberate
 * exceptions, {@link io.github.jaymcole.housegraph.plugins.collections.nodes.lists.CollectItemsNode}
 * and {@link io.github.jaymcole.housegraph.plugins.collections.nodes.maps.CollectEntriesNode},
 * whose whole purpose is to accumulate across separate firings and which therefore need flow
 * inputs to tell those firings apart.
 * <p>
 * That is why no node here branches on flow. A node that would ("was the key found?") emits a
 * {@code Boolean} instead, to be wired into the host's <b>If (Boolean)</b> — the composable form
 * of the same graph, and the one the control-versus-action rule in {@code docs/shared/
 * node-library-rules.md} asks for.
 * <p>
 * <b>The loop-shaped nodes a collection library might be expected to carry are absent on
 * purpose.</b> Iteration is the host's <b>For Each</b>; a general map/filter driven by a subgraph
 * body would be a second control node of the same kind, so filtering here is done by parameter
 * (see {@link io.github.jaymcole.housegraph.plugins.collections.nodes.lists.FilterByTextNode} and
 * its siblings) rather than by callback.
 * <p>
 * <b>For Each takes a {@code List}</b>, and only a list. That is the single most load-bearing fact
 * about the {@code maps} and {@code sets} packages: every collection they produce has a documented
 * way back to a list — <b>Map Entries</b> for a map, <b>Set to List</b> for a set — so that
 * nothing built here is a dead end.
 * <p>
 * <b>Identity is one rule across all three.</b> A data anchor's type is a bare {@code Class}, so a
 * list, map or set port's element type is erased and any of them may be wired into any other's
 * input. Every node here therefore compares values by
 * {@link io.github.jaymcole.housegraph.plugins.collections.Lists#key their text form} rather than
 * by {@code equals} alone, so a {@code "3"} typed into an Item or Key field finds the {@code 3}
 * some upstream node emitted. {@link io.github.jaymcole.housegraph.plugins.collections.Maps} and
 * {@link io.github.jaymcole.housegraph.plugins.collections.Sets} spell out what that means for a
 * map's keys and a set's members.
 */
package io.github.jaymcole.housegraph.plugins.collections.nodes;
