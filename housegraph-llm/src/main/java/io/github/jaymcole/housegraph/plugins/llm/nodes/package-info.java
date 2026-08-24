/**
 * The nodes that prompt a language model running on this machine. Back onto the sibling
 * {@code llm} package, which holds the protocols
 * ({@link io.github.jaymcole.housegraph.plugins.llm.LlmApi}) and the one HTTP call
 * ({@link io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient}) they share.
 * <p>
 * <b>Every node here is an action node</b> — a flow input, a flow output, and work in between
 * that reaches outside the graph. That is deliberate, and the split this repository's
 * {@code CLAUDE.md} argues for: nothing here decides <em>when</em> to run. Wire a Repeating
 * Trigger, a Daily Trigger, a Discord command or a web hook into the flow input and the same node
 * serves all four, instead of growing a timer that only one of them would want.
 * <p>
 * <b>And they are actions rather than data nodes</b>, unlike the Text library next door, because a
 * generation costs seconds of a machine's GPU and is not the same twice. A pure data node runs
 * whenever something pulls its value; a flow-driven one runs when a run reaches it, once, which is
 * what an expensive and non-repeatable call should do.
 */
package io.github.jaymcole.housegraph.plugins.llm.nodes;
