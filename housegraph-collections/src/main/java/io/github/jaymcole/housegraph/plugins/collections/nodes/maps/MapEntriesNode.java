package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Takes a map apart into two parallel lists — <b>Keys</b> and <b>Values</b> — plus <b>Count</b> and
 * <b>Is Empty</b>. <b>This is the way out of a map</b>: the host's <b>For Each</b> iterates a list
 * and only a list, so without this a map would be a dead end. Loop over Keys and read each value
 * back with <b>Map Get</b>, or loop over Values when the keys don't matter.
 * <p>
 * <b>The two lists line up: {@code Keys[i]} is the key of {@code Values[i]}</b>, and that guarantee
 * is the reason this is one node rather than a Map Keys and a Map Values. Two separate nodes could
 * only promise it by accident, and a graph that indexes one list by a position found in the other
 * would break the first time they disagreed. Both follow the map's own order, which is the order
 * entries went in.
 * <p>
 * <b>Keys come out as text</b>, because that is what a map stores here — see {@link Maps} for why.
 * Nothing downstream need care: {@link Lists#number} reads {@code "3"} as a number, <b>Sort
 * List</b> orders such keys numerically, and <b>List Contains</b> matches them against either
 * form. Values come out as whatever objects went in.
 * <p>
 * Four outputs rather than four nodes, because they are one pass over the same map and a graph
 * asking for the keys nearly always wants the count beside them — the same reasoning as <b>List
 * Statistics</b>. An unwired map reads as empty rather than as an error.
 */
@Display.Name("Map Entries")
@Display.Description("A map's keys and values as two matching lists, with a count.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"map", "entries", "keys", "values", "count", "size", "empty", "split", "pairs", "iterate"})
@Node.Type("collections.MapEntriesNode")
public class MapEntriesNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();

    private final NodeVariable<List<?>> keys = new NodeVariable<>("Keys", Lists.TYPE);
    private final NodeVariable<List<?>> values = new NodeVariable<>("Values", Lists.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> empty = new NodeVariable<>("Is Empty", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> entries = Maps.copyOf(map.getValue());
        List<Object> keyList = new ArrayList<>(entries.size());
        List<Object> valueList = new ArrayList<>(entries.size());
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            keyList.add(entry.getKey());
            valueList.add(entry.getValue());
        }
        keys.setValue(Lists.frozen(keyList));
        values.setValue(Lists.frozen(valueList));
        count.setValue(entries.size());
        empty.setValue(entries.isEmpty());
    }

    @Override
    public void configureInputs() {
        addInput(map);
    }

    @Override
    public void configureOutputs() {
        addOutput(keys);
        addOutput(values);
        addOutput(count);
        addOutput(empty);
    }
}
