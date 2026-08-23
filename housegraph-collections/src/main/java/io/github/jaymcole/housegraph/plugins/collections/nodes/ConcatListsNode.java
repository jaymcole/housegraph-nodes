package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;

/**
 * One list after another. Either side may be unwired, in which case the other passes through
 * unchanged — so this doubles as "whichever of these two I actually got".
 * <p>
 * Two inputs rather than a growing set of them: three lists is two of these, and the fixed shape
 * keeps the node readable on canvas. <b>Flatten</b> is the node for the many-lists case.
 */
@Display.Name("Concat Lists")
@Display.Description("One list followed by another.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"concat", "concatenate", "join", "append", "merge", "combine", "lists", "plus"})
@Node.Type("collections.ConcatListsNode")
public class ConcatListsNode extends BaseNode {

    private final NodeVariable<List<?>> first = new NodeVariable<>("First", Lists.TYPE);
    private final NodeVariable<List<?>> second = new NodeVariable<>("Second", Lists.TYPE);

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> combined = Lists.mutableCopyOf(first.getValue());
        combined.addAll(Lists.copyOf(second.getValue()));
        result.setValue(Lists.frozen(combined));
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
