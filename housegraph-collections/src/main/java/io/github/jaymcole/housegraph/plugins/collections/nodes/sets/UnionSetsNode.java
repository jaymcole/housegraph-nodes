package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Every member of either set, repeats collapsed. Either side may be unwired, in which case the
 * other passes through unchanged — the same "whichever of these two I actually got" behaviour
 * {@code ConcatListsNode} and {@code MergeMapsNode} share.
 * <p>
 * Two inputs rather than a growing set of them, for the same reason those two have two: three sets
 * is two of these, and the fixed shape keeps the node readable on canvas.
 */
@Display.Name("Union")
@Display.Description("Every member of either set, repeats collapsed.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"union", "combine", "merge", "sets", "together", "or"})
@Node.Type("collections.UnionSetsNode")
public class UnionSetsNode extends BaseNode {

    private final NodeVariable<Set<?>> first = new NodeVariable<>("First", Sets.TYPE);
    private final NodeVariable<Set<?>> second = new NodeVariable<>("Second", Sets.TYPE);

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        LinkedHashSet<Object> members = Sets.mutableCopyOf(first.getValue());
        for (Object candidate : Sets.copyOf(second.getValue())) {
            if (!Sets.contains(members, candidate)) {
                members.add(candidate);
            }
        }
        result.setValue(Sets.frozen(members));
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
