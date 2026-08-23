/**
 * Nodes that build, reshape, test and parse text. Back onto the sibling {@code string} package,
 * which holds the rules they share ({@link io.github.jaymcole.housegraph.plugins.string.Texts}),
 * the template grammar ({@link io.github.jaymcole.housegraph.plugins.string.Template}) and the
 * authored modes they take.
 * <p>
 * <b>Every node here is a pure data node</b> — no flow ports. The engine resolves data by pulling
 * it through data edges (see {@code NodeGraph}), so a text transform runs when something
 * downstream needs its value and needs no flow wired through it. None of them touch the outside
 * world, so there is no outcome to report on a flow output and nothing that would justify the
 * extra wire.
 * <p>
 * <b>None of them branch, either.</b> Compare Text, Regex Match and the parse nodes emit a
 * {@code Boolean} for the host's built-in <b>If Bool</b> to branch on, rather than growing flow
 * outputs of their own — a node here is data-oriented, and deciding what runs next is the control
 * library's job.
 */
package io.github.jaymcole.housegraph.plugins.string.nodes;
