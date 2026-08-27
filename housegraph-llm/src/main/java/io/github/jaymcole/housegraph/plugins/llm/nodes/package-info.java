/**
 * The nodes that run a language model on this machine, and the ones that prompt it. Back onto the
 * sibling {@code llm} package, which holds the protocols
 * ({@link io.github.jaymcole.housegraph.plugins.llm.LlmApi}), the one HTTP call that prompts
 * ({@link io.github.jaymcole.housegraph.plugins.llm.LocalLlmClient}), the two that come before it
 * ({@link io.github.jaymcole.housegraph.plugins.llm.LlmModels}) and the child process behind them
 * ({@link io.github.jaymcole.housegraph.plugins.llm.LlmServerProcess}).
 * <p>
 * <b>Everything a local LLM needs is a node here.</b> That is the point of the library: a graph on
 * an unattended machine should not depend on somebody having typed {@code ollama serve} and
 * {@code ollama pull llama3.2} into a terminal first, and then remembering to do it again after a
 * reboot. <b>Local LLM Server</b> starts the server, <b>Pull Model</b> puts a model in it,
 * <b>LLM Server Status</b> answers whether it is up, and <b>Local LLM</b> prompts it.
 * <p>
 * <b>One of the four is a resource node; the other three are action nodes.</b> That is the split
 * this repository's {@code CLAUDE.md} argues for, including its named exception:
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.plugins.llm.nodes.LlmServerNode} owns a real process
 *       lifecycle, so Start/Stop and state genuinely belong to it — the same case as a web server
 *       or a database connection. It is the exception, not precedent.</li>
 *   <li>The other three have a flow input, a flow output, and work in between that reaches outside
 *       the graph. Nothing in them decides <em>when</em> to run: wire a Repeating Trigger, a Daily
 *       Trigger, a Discord command or a web hook into the flow input and the same node serves all
 *       four, instead of growing a timer that only one of them would want.</li>
 * </ul>
 * <b>And the three are actions rather than data nodes</b>, unlike the Text library next door,
 * because each of them costs something real and is not the same twice — a generation costs seconds
 * of a machine's GPU, a pull costs gigabytes of somebody's connection. A pure data node runs
 * whenever something pulls its value; a flow-driven one runs when a run reaches it, once, which is
 * what an expensive and non-repeatable call should do.
 */
package io.github.jaymcole.housegraph.plugins.llm.nodes;
