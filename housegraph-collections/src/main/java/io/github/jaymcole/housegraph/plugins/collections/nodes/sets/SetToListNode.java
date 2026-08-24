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
 * Turns a set back into a list, in the order its members went in. <b>This is the way out of this
 * package</b>, and it is not optional scaffolding: the host's <b>For Each</b> iterates a list and
 * only a list, and <b>Join List</b> renders a list and only a list, so without this node a set
 * would be a dead end.
 * <p>
 * <b>Count</b> and <b>Is Empty</b> ride along, which is why there is no separate "Set Size" node —
 * they are free here, and a graph asking how big a set is usually wants to do something with the
 * members next. They are the same pair <b>List Count</b> offers, for the same reason: the yes/no
 * question gets asked more often than the number.
 * <p>
 * Members come out as the objects that went in, not as text — see {@link Sets} for why a set keeps
 * its members typed where a map normalises its keys — so a set of numbers feeds <b>List
 * Statistics</b> directly.
 */
@Display.Name("Set to List")
@Display.Description("A set as a list, in insertion order, with a count.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"set", "to list", "list", "convert", "members", "count", "size", "empty", "iterate"})
@Node.Type("collections.SetToListNode")
public class SetToListNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> empty = new NodeVariable<>("Is Empty", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Set<Object> members = Sets.copyOf(set.getValue());
        result.setValue(Lists.frozen(new ArrayList<>(members)));
        count.setValue(members.size());
        empty.setValue(members.isEmpty());
    }

    @Override
    public void configureInputs() {
        addInput(set);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(count);
        addOutput(empty);
    }
}
