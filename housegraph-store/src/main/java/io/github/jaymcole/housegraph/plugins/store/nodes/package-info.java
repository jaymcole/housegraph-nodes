/**
 * Nodes for remembering something between runs. They back onto the sibling {@code store} package's
 * {@link io.github.jaymcole.housegraph.plugins.store.Documents}, which states the entry-handling
 * rules — text in, text out, flat keys — every node here follows.
 * <p>
 * <b>Nothing here opens a file.</b> The store is the host's: a <b>Data Store</b> node produces the
 * {@code JsonDocumentStore} and these nodes take it as a value, the same way the web server node
 * does to serve one over {@code /api/data}. That is what keeps one document one document — a
 * library that resolved its own path under {@code AppDirectories} would be a second, invisible
 * place for a graph's state to live, and the two would drift the first time anyone looked.
 * <p>
 * <b>These are action nodes, not control nodes.</b> They have flow inputs because a write has to
 * happen at a moment in the graph rather than whenever something reads it — the distinction
 * {@link io.github.jaymcole.housegraph.plugins.store.nodes.StoredValueNode} draws between being
 * triggered and being pulled. They branch on nothing and schedule nothing: a node that decided
 * <em>when</em> to remember something would be the fused shape the control-versus-action rule in
 * {@code docs/shared/node-library-rules.md} asks you to split.
 */
package io.github.jaymcole.housegraph.plugins.store.nodes;
