package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Map;

/**
 * Whether a map has an entry under a given key. Wire <b>Found</b> into the host's <b>If
 * (Boolean)</b> to branch on it.
 * <p>
 * This answers "is it there at all", including a key whose stored value is null — which
 * {@code Get Value}'s own Found already tells you, but this reads clearer in a graph that only
 * cares about presence. Matching is the same {@link Lists#sameValue forgiving} comparison every
 * lookup in this library uses.
 */
@Display.Name("Map Contains Key")
@Display.Description("Whether a map has an entry under a given key.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"contains", "has", "key", "map", "dictionary", "member", "exists"})
@Node.Type("collections.MapContainsKeyNode")
public class MapContainsKeyNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true).required();

    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        found.setValue(Maps.containsKey(map.getValue(), key.getValue()));
    }

    @Override
    public void configureInputs() {
        addInput(map);
        addInput(key);
    }

    @Override
    public void configureOutputs() {
        addOutput(found);
    }
}
