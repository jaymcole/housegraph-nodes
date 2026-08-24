/**
 * Nodes for building, reading and combining maps. They back onto the sibling {@code collections}
 * package's {@link io.github.jaymcole.housegraph.plugins.collections.Maps}, which states the
 * key-lookup and copying rules every node here follows — the same forgiving, erasure-driven rules
 * {@link io.github.jaymcole.housegraph.plugins.collections.Lists} states for the list nodes this
 * package sits beside.
 * <p>
 * <b>Everything here is a pure data node with no flow ports</b>, for the same reason the sibling
 * {@link io.github.jaymcole.housegraph.plugins.collections.nodes} package's list nodes are: pulled
 * when something downstream needs the output, and nothing here branches on flow. A node that would
 * ("did that key exist?") emits a {@code Boolean} instead, to be wired into the host's <b>If
 * (Boolean)</b>.
 * <p>
 * This package is a sibling of {@code nodes} and {@code nodes.sets}, not a replacement for either —
 * subfolders under {@code nodes} group this library's growing node count by the collection type
 * each node operates on, so a person adding a node knows where it goes and a person looking for one
 * knows where to look.
 */
package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;
