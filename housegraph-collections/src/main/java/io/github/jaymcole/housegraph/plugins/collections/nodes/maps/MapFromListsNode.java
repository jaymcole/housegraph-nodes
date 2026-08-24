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
 * Zips two parallel lists into a map, pairing each key with the value at the same position. This
 * is the inverse of <b>Map Keys</b> / <b>Map Values</b>: those two split a map into a pair of
 * lists, this rejoins them.
 * <p>
 * Pairing stops at the shorter list — an extra key with nothing under it, or an extra value with
 * nothing to name it, cannot become an entry, so both are silently ignored rather than failing the
 * whole node. <b>Pairs</b> reports how many entries were actually formed, which is how a graph
 * tells "the lists matched" from "one ran short" without comparing lengths itself.
 * <p>
 * Where two keys collide ({@link Lists#sameValue forgivingly}), the later pair's value wins, the
 * same rule <b>Put</b> and <b>Merge Maps</b> use.
 */
@Display.Name("Map From Lists")
@Display.Description("Zips a list of keys and a list of values into one map.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"map", "zip", "from", "lists", "keys", "values", "dictionary", "build"})
@Node.Type("collections.MapFromListsNode")
public class MapFromListsNode extends BaseNode {

    private final NodeVariable<List<?>> keys = new NodeVariable<>("Keys", Lists.TYPE).required();
    private final NodeVariable<List<?>> values = new NodeVariable<>("Values", Lists.TYPE).required();

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Integer> pairs = new NodeVariable<>("Pairs", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> keyEntries = Lists.copyOf(keys.getValue());
        List<Object> valueEntries = Lists.copyOf(values.getValue());
        int count = Math.min(keyEntries.size(), valueEntries.size());

        Map<Object, Object> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            entries.put(keyEntries.get(i), valueEntries.get(i));
        }
        result.setValue(Maps.frozen(entries));
        pairs.setValue(entries.size());
    }

    @Override
    public void configureInputs() {
        addInput(keys);
        addInput(values);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(pairs);
    }
}
