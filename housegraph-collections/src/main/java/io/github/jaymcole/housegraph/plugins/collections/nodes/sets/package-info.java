/**
 * Nodes for the collection that answers one question well: <b>is this in there?</b> They back onto
 * {@link io.github.jaymcole.housegraph.plugins.collections.Sets}, which states what a set here
 * actually is — insertion-ordered, holding the first-seen original object for each member, and
 * comparing members by text form rather than by {@code equals}.
 *
 * <h2>The shape of the package</h2>
 * <ul>
 *   <li><b>In:</b> <b>To Set</b>, from a list.</li>
 *   <li><b>Out:</b> <b>Set to List</b>, back to a list — which the host's <b>For Each</b> and this
 *       library's <b>Join List</b> both need, since neither takes a set.</li>
 *   <li><b>Asking:</b> <b>Set Contains</b>.</li>
 *   <li><b>Changing:</b> <b>Set Add</b>, <b>Set Remove</b>.</li>
 *   <li><b>Comparing two:</b> <b>Set Union</b>, <b>Set Intersection</b>, <b>Set Difference</b>.</li>
 * </ul>
 *
 * <h2>Why a set at all, when Distinct already exists</h2>
 *
 * <b>Distinct</b> deduplicates a list and hands back a list; that is the right node when what
 * comes next is iterating or rendering. A set is worth the conversion when what comes next is
 * <em>comparing</em>: "which cameras saw something in both windows", "who is on the list now that
 * wasn't an hour ago". Those are one node here and a nest of <b>For Each</b> and <b>List
 * Contains</b> otherwise.
 *
 * <h2>Three nodes that are deliberately absent</h2>
 *
 * <b>There is no "Build Set"</b> with growing wired slots, the way <b>Build List</b> and <b>Build
 * Map</b> have. <b>Build List</b> into <b>To Set</b> is that node, out of two that already exist,
 * and — unlike the map case, where the slots have two halves to keep in step — the composed form
 * loses nothing.
 * <p>
 * <b>There is no "Set Size".</b> <b>Set to List</b> carries <b>Count</b> and <b>Is Empty</b>
 * beside the list, and a graph asking how big a set is nearly always wants the members next.
 * <p>
 * <b>There is no "Compare Sets"</b> answering equal / subset / superset, because <b>Set
 * Difference</b> already does, in the form a graph can act on: the two sets are equal when
 * <b>Changed</b> is false, A is contained in B when <b>Only in A</b> is empty, and B is contained
 * in A when <b>Only in B</b> is empty. A node emitting those three booleans would add a second way
 * to ask a question already answered — and it would answer it less usefully, since Difference says
 * <em>which</em> members differ and not merely that some do.
 *
 * <h2>What they share with the rest of the library</h2>
 *
 * An unwired Set input reads as empty rather than failing; nothing is edited in place, so every
 * node that changes a set publishes a new, unmodifiable one; an Item field is text where the node
 * is looking something <em>up</em> and {@code Object} where it is a value to <em>keep</em>; and
 * matching is forgiving about type, so a typed {@code "3"} finds a member that arrived as
 * {@code 3}. See the parent package for the rules that span {@code lists}, {@code maps} and
 * {@code sets} together.
 */
package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;
