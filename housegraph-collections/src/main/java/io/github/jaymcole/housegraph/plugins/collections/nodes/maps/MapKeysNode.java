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
 * A map's keys, as a list in the order they were put — the same order {@code Map Values} reports
 * its values in, so the two line up index-for-index and a graph that needs both together can zip
 * them with a For Each over one while reading the other by index.
 */
@Display.Name("Map Keys")
@Display.Description("A map's keys, as a list in insertion order.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"keys", "map", "dictionary", "list"})
@Node.Type("collections.MapKeysNode")
public class MapKeysNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();

    private final NodeVariable<List<?>> keys = new NodeVariable<>("Keys", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> result = new ArrayList<>(Maps.copyOf(map.getValue()).keySet());
        keys.setValue(Lists.frozen(result));
    }

    @Override
    public void configureInputs() {
        addInput(map);
    }

    @Override
    public void configureOutputs() {
        addOutput(keys);
    }
}
