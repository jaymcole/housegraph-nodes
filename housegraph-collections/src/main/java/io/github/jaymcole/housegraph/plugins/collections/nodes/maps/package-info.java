/**
 * Nodes for building, reading and reshaping maps — the keyed, unordered-by-nature collection, kept
 * in insertion order here so its text form doesn't rearrange itself between runs. They back onto
 * {@link io.github.jaymcole.housegraph.plugins.collections.Maps}, which states the rules every node
 * here follows and — importantly — <b>why a map's keys are text</b>.
 *
 * <h2>The shape of the package</h2>
 * <ul>
 *   <li><b>In:</b> <b>Build Map</b> from wired pairs, <b>Map from Lists</b> from two lists,
 *       <b>Tally</b> from one list, <b>Collect Entries</b> from a loop.</li>
 *   <li><b>Out:</b> <b>Map Entries</b> to two lists, <b>Join Map</b> to text.</li>
 *   <li><b>Along the way:</b> <b>Map Get</b>, <b>Map Put</b>, <b>Map Remove</b>, <b>Merge
 *       Maps</b>.</li>
 * </ul>
 * Every map has a way back out to a list, and that is not decoration: the host's <b>For Each</b>
 * iterates a list and only a list, so <b>Map Entries</b> is how a map gets looped over at all.
 *
 * <h2>Two nodes that are deliberately absent</h2>
 *
 * <b>There is no "Map Contains Key".</b> <b>Map Get</b> already answers it on its <b>Found</b>
 * output, in the same pass that fetches the value — and because no map here stores a null value,
 * Found means "the key is there" and nothing else. A second node would be a second way to ask one
 * question, and the two would eventually disagree about a corner case.
 * <p>
 * <b>There is no "Map Size".</b> <b>Map Entries</b> carries <b>Count</b> and <b>Is Empty</b>
 * beside the lists, for the reason <b>List Statistics</b> gives: they are one pass over the same
 * map, and a graph asking for a count usually wants the contents too.
 *
 * <h2>What they share with the rest of the library</h2>
 *
 * An unwired Map input reads as empty rather than failing; nothing is edited in place, so every
 * node that changes a map publishes a new, unmodifiable one; a Key field is text, because that is
 * the only kind of field a person can type into; and lookups are forgiving about type, so a typed
 * {@code "3"} finds an entry stored under a {@code 3}. See the parent package for the rules that
 * span {@code lists}, {@code maps} and {@code sets} together.
 */
package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;
