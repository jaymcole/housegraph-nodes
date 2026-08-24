package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Map;

/**
 * How many entries a map has, plus an <b>Is Empty</b> flag — the same pairing {@code ListCountNode}
 * reports for lists, and for the same reason: the question that gets asked more often than the
 * number itself. Wire Is Empty into the host's <b>If (Boolean)</b> to branch on it.
 * <p>
 * A map that was never wired in counts as empty rather than as an error — see {@link Maps#copyOf}.
 */
@Display.Name("Map Count")
@Display.Description("How many entries a map has, and whether it's empty.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"count", "size", "length", "map", "dictionary", "empty", "how many"})
@Node.Type("collections.MapCountNode")
public class MapCountNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();

    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> empty = new NodeVariable<>("Is Empty", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        int size = Maps.copyOf(map.getValue()).size();
        count.setValue(size);
        empty.setValue(size == 0);
    }

    @Override
    public void configureInputs() {
        addInput(map);
    }

    @Override
    public void configureOutputs() {
        addOutput(count);
        addOutput(empty);
    }
}
