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
 * One map laid over another: every entry from both, with <b>Second</b>'s value winning wherever
 * both sides have a matching key ({@link Lists#sameValue forgivingly}). Either side may be
 * unwired, in which case the other passes through unchanged — so this doubles as "whichever of
 * these two I actually got", the same as {@code ConcatListsNode}.
 * <p>
 * Two inputs rather than a growing set of them, for the same reason {@code ConcatListsNode} has
 * two: three maps is two of these, and the fixed shape keeps the node readable on canvas.
 */
@Display.Name("Merge Maps")
@Display.Description("One map laid over another, with the second's values winning on conflicts.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"merge", "combine", "overlay", "map", "dictionary", "union", "join"})
@Node.Type("collections.MergeMapsNode")
public class MergeMapsNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> first = new NodeVariable<>("First", Maps.TYPE);
    private final NodeVariable<Map<?, ?>> second = new NodeVariable<>("Second", Maps.TYPE);

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        Map<Object, Object> merged = Maps.mutableCopyOf(first.getValue());
        for (Map.Entry<Object, Object> entry : Maps.copyOf(second.getValue()).entrySet()) {
            Object existingKey = null;
            for (Object candidate : merged.keySet()) {
                if (Lists.sameValue(candidate, entry.getKey())) {
                    existingKey = candidate;
                    break;
                }
            }
            merged.put(existingKey != null ? existingKey : entry.getKey(), entry.getValue());
        }
        result.setValue(Maps.frozen(merged));
    }

    @Override
    public void configureInputs() {
        addInput(first);
        addInput(second);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
