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
 * A map's values, as a list in the order they were put — see {@code Map Keys} for the pairing this
 * is designed for.
 */
@Display.Name("Map Values")
@Display.Description("A map's values, as a list in insertion order.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"values", "map", "dictionary", "list"})
@Node.Type("collections.MapValuesNode")
public class MapValuesNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();

    private final NodeVariable<List<?>> values = new NodeVariable<>("Values", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> result = new ArrayList<>(Maps.copyOf(map.getValue()).values());
        values.setValue(Lists.frozen(result));
    }

    @Override
    public void configureInputs() {
        addInput(map);
    }

    @Override
    public void configureOutputs() {
        addOutput(values);
    }
}
