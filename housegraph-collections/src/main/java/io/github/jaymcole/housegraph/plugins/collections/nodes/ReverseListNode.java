package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.Collections;
import java.util.List;

/**
 * The same entries, last to first. Chained after <b>Sort List</b> it is how you sort descending —
 * which is why Sort carries no direction flag of its own.
 * <p>
 * Like every transform here it returns a new list and leaves the one it was given untouched: a
 * list value can be pulled by several downstream nodes in the same run, so a node that reversed in
 * place would quietly change what its siblings see.
 */
@Display.Name("Reverse List")
@Display.Description("The same entries in the opposite order.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"reverse", "backwards", "flip", "invert", "order", "descending", "list"})
@Node.Type("collections.ReverseListNode")
public class ReverseListNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> reversed = Lists.mutableCopyOf(list.getValue());
        Collections.reverse(reversed);
        result.setValue(Lists.frozen(reversed));
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
