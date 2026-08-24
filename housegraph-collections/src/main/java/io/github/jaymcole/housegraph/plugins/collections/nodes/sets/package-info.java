/**
 * Nodes for building sets and combining them with union, intersection and difference. They back
 * onto the sibling {@code collections} package's
 * {@link io.github.jaymcole.housegraph.plugins.collections.Sets}, which states the
 * duplicate-collapsing rule every node here follows — the same forgiving, erasure-driven identity
 * {@link io.github.jaymcole.housegraph.plugins.collections.Lists} states for the list nodes this
 * package sits beside, and the one {@code DistinctListNode} already uses for lists.
 * <p>
 * <b>Everything here is a pure data node with no flow ports</b>, for the same reason the sibling
 * {@link io.github.jaymcole.housegraph.plugins.collections.nodes} package's list nodes are: pulled
 * when something downstream needs the output, and nothing here branches on flow. A node that would
 * ("was it already a member?") emits a {@code Boolean} instead, to be wired into the host's <b>If
 * (Boolean)</b>.
 * <p>
 * This package is a sibling of {@code nodes} and {@code nodes.maps}, not a replacement for either —
 * subfolders under {@code nodes} group this library's growing node count by the collection type
 * each node operates on, so a person adding a node knows where it goes and a person looking for one
 * knows where to look.
 */
package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;
