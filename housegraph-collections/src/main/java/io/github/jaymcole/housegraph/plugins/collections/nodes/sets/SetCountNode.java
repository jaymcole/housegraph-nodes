package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.Set;

/**
 * How many members a set has, plus an <b>Is Empty</b> flag — the same pairing {@code ListCountNode}
 * reports for lists. Wire Is Empty into the host's <b>If (Boolean)</b> to branch on it.
 * <p>
 * A set that was never wired in counts as empty rather than as an error — see {@link Sets#copyOf}.
 */
@Display.Name("Set Count")
@Display.Description("How many members a set has, and whether it's empty.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"count", "size", "length", "set", "empty", "how many"})
@Node.Type("collections.SetCountNode")
public class SetCountNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE).required();

    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> empty = new NodeVariable<>("Is Empty", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        int size = Sets.copyOf(set.getValue()).size();
        count.setValue(size);
        empty.setValue(size == 0);
    }

    @Override
    public void configureInputs() {
        addInput(set);
    }

    @Override
    public void configureOutputs() {
        addOutput(count);
        addOutput(empty);
    }
}
