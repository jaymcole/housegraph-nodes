package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;

/**
 * How many entries a list has, plus an <b>Is Empty</b> flag for the question that gets asked about
 * a count more often than the number itself ("did the camera see anything?"). Wire Is Empty into
 * the host's <b>If (Boolean)</b> to branch on it.
 * <p>
 * A list that was never wired in counts as empty rather than as an error — see {@link Lists#copyOf}
 * for why absent and empty are one case throughout this library.
 */
@Display.Name("List Count")
@Display.Description("How many entries a list has, and whether it's empty.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"count", "size", "length", "list", "empty", "how many"})
@Node.Type("collections.ListCountNode")
public class ListCountNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> empty = new NodeVariable<>("Is Empty", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        int size = Lists.copyOf(list.getValue()).size();
        count.setValue(size);
        empty.setValue(size == 0);
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(count);
        addOutput(empty);
    }
}
