package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.List;
import java.util.Set;

/**
 * A list with its repeats collapsed into a set. This is the same collapsing
 * {@code DistinctListNode} does — first occurrence kept, by {@link Lists#key text-form} identity —
 * the difference being the output's type: a {@link Set}, for a graph that goes on to use set
 * algebra (<b>Union</b>, <b>Intersect</b>, <b>Difference</b>) rather than list operations.
 * <p>
 * <b>Removed</b> reports how many entries this dropped, the same output {@code Distinct} reports
 * for the same reason: it is how a graph tells "already had no repeats" from "nothing was there to
 * begin with".
 */
@Display.Name("To Set")
@Display.Description("A list with its repeated entries collapsed into a set.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"set", "list", "distinct", "unique", "dedupe", "duplicates", "convert"})
@Node.Type("collections.SetFromListNode")
public class SetFromListNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Integer> removed = new NodeVariable<>("Removed", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.copyOf(list.getValue());
        Set<Object> members = Sets.fromList(entries);
        result.setValue(members);
        removed.setValue(entries.size() - members.size());
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(removed);
    }
}
