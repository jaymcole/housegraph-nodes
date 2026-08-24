package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Everything in either set. <b>A</b>'s members first, in their order, then whatever <b>B</b> adds
 * that A did not already hold.
 * <p>
 * This is the "and also" of the three set nodes — see <b>Set Intersection</b> for "in both" and
 * <b>Set Difference</b> for "in one but not the other". They are three named nodes rather than one
 * node with a mode field, unlike <b>Filter by Text</b> and its authored mode: a mode field would
 * put the entire meaning of the node in a string you have to click to read, where three names say
 * it on the canvas, and search finds "union" without anyone knowing this package exists.
 * <p>
 * <b>Count</b> is the size of the result. Either set may be unwired, which reads as empty, so a
 * union with nothing is just the other set. Membership is compared by {@link Sets text form}
 * throughout, so a {@code 3} in A and a {@code "3"} in B are one member — and the one that
 * survives is A's, since A goes in first.
 */
@Display.Name("Set Union")
@Display.Description("Everything in either set.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"union", "set", "combine", "merge", "either", "or", "both", "all", "plus"})
@Node.Type("collections.SetUnionNode")
public class SetUnionNode extends BaseNode {

    private final NodeVariable<Set<?>> a = new NodeVariable<>("A", Sets.TYPE);
    private final NodeVariable<Set<?>> b = new NodeVariable<>("B", Sets.TYPE);

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        // A then B through one pass of mutableCopyOf, rather than adding B's members one at a
        // time: that helper already drops repeats by text form and keeps the first of each, which
        // is exactly union with A winning - and it indexes as it goes instead of rescanning the
        // growing set per member.
        List<Object> combined = new ArrayList<>(Sets.copyOf(a.getValue()));
        combined.addAll(Sets.copyOf(b.getValue()));
        Set<Object> members = Sets.mutableCopyOf(combined);
        result.setValue(Sets.frozen(members));
        count.setValue(members.size());
    }

    @Override
    public void configureInputs() {
        addInput(a);
        addInput(b);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(count);
    }
}
