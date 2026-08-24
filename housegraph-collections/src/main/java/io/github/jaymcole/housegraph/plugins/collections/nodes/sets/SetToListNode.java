package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A set's members, as a list in the order they were added. This is what makes a set usable with
 * the rest of this library, and with the host's <b>For Each</b>: sorting, joining, formatting and
 * looping are all list operations, so a graph that built a set to get its de-duplication for free
 * comes back here to do anything else with the result.
 */
@Display.Name("To List")
@Display.Description("A set's members, as a list in the order they were added.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"list", "set", "convert", "members", "entries"})
@Node.Type("collections.SetToListNode")
public class SetToListNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        result.setValue(Lists.frozen(new ArrayList<>(Sets.copyOf(set.getValue()))));
    }

    @Override
    public void configureInputs() {
        addInput(set);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
