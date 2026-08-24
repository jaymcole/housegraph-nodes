package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts how often each distinct entry appears in a list, as a map of entry to count. Between a
 * motion sensor and a daily summary this is the node that turns a log of camera names into "front
 * door 12, driveway 3".
 * <p>
 * It is the bridge the two packages needed: every other map node takes a map from somewhere, and
 * this is somewhere. <b>Distinct</b> answers "what different things are in here?"; this answers
 * "and how many of each?" in one pass.
 * <p>
 * Entries are counted by {@link Lists#key text form}, the same forgiving identity <b>Distinct</b>
 * and <b>List Contains</b> use — so a {@code 3} and a {@code "3"} are one thing, which is the only
 * answer that makes sense when nothing downstream could tell them apart. Keys appear in the order
 * each entry was <em>first</em> seen, so the map reads as a running order rather than a
 * rearrangement; sort it afterwards if you want it by name or by size.
 * <p>
 * <b>Counts come out as {@link Integer}</b>, so <b>Map Entries</b> feeds the Values straight into
 * <b>List Statistics</b> — the total there is exactly the number of entries this
 * counted. Null and blank entries are skipped rather than tallied under the word "null" or under
 * an empty key, so that total can be smaller than the list's length. <b>Distinct
 * Count</b> is how many keys the map ended up with, which is the same number <b>Distinct</b> would
 * have left in a list.
 */
@Display.Name("Tally")
@Display.Description("How often each distinct entry appears in a list, as a map.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"tally", "count", "frequency", "histogram", "group", "occurrences", "how many", "map", "summarise"})
@Node.Type("collections.TallyNode")
public class TallyNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<Map<?, ?>> counts = new NodeVariable<>("Counts", Maps.TYPE);
    private final NodeVariable<Integer> distinctCount = new NodeVariable<>("Distinct Count", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> tallies = new LinkedHashMap<>();
        for (Object entry : Lists.copyOf(list.getValue())) {
            String key = Maps.key(entry);
            if (key == null) {
                continue;
            }
            tallies.merge(key, 1, (existing, one) -> (Integer) existing + 1);
        }
        counts.setValue(Maps.frozen(tallies));
        distinctCount.setValue(tallies.size());
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(counts);
        addOutput(distinctCount);
    }
}
