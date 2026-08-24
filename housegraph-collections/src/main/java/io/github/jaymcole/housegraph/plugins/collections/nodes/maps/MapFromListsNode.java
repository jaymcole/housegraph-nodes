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
 * Zips two lists into a map, pairing them by position: the first key with the first value, and so
 * on. This is the exact inverse of <b>Map Entries</b>, and the pair of them is what lets a graph
 * take a map apart, rework the pieces with the list nodes, and put it back together.
 * <p>
 * The obvious source is <b>Split Text</b> twice over — a line of names and a line of numbers
 * becoming a lookup table — or a <b>For Each</b> that collected keys and values into two
 * <b>Collect Items</b> nodes.
 * <p>
 * <b>Lists of different lengths stop at the shorter one.</b> Failing instead would be the wrong
 * call: two lists drawn from the same source disagreeing by one is a routine off-by-one somewhere
 * upstream, and a node that says so on an output is easier to debug than one that takes the graph
 * down.
 * <p>
 * <b>Dropped</b> is every entry that went in and did not come out as a map entry — the unpaired
 * tail of the longer list, plus any pair whose key was blank or whose value was null (see
 * {@link Maps#put}). A key that <em>repeats</em> is not a drop: it keeps the last value, matching
 * what <b>Map Put</b> means by putting, so a map smaller than Dropped implies is a map with
 * repeated keys in it.
 */
@Display.Name("Map from Lists")
@Display.Description("Pairs a list of keys with a list of values, by position, into a map.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"map", "zip", "pair", "lists", "keys", "values", "build", "combine", "from"})
@Node.Type("collections.MapFromListsNode")
public class MapFromListsNode extends BaseNode {

    private final NodeVariable<List<?>> keys = new NodeVariable<>("Keys", Lists.TYPE).required();
    private final NodeVariable<List<?>> values = new NodeVariable<>("Values", Lists.TYPE).required();

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Integer> dropped = new NodeVariable<>("Dropped", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> keyList = Lists.copyOf(keys.getValue());
        List<Object> valueList = Lists.copyOf(values.getValue());
        int paired = Math.min(keyList.size(), valueList.size());

        Map<String, Object> entries = new LinkedHashMap<>();
        int unpaired = Math.max(keyList.size(), valueList.size()) - paired;
        for (int i = 0; i < paired; i++) {
            if (!Maps.put(entries, keyList.get(i), valueList.get(i))) {
                unpaired++;
            }
        }
        result.setValue(Maps.frozen(entries));
        dropped.setValue(unpaired);
    }

    @Override
    public void configureInputs() {
        addInput(keys);
        addInput(values);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(dropped);
    }
}
