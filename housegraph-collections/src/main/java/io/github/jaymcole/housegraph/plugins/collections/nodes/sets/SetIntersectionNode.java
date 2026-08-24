package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Only what is in both sets. Members come out in <b>A</b>'s order, and as A's objects — which
 * matters when the two sides hold the same value in different types, since it fixes which one
 * flows on.
 * <p>
 * This is the "and" of the three set nodes — see <b>Set Union</b> for "either" and <b>Set
 * Difference</b> for "one but not the other". The question it answers is usually "which of these
 * are still relevant?": yesterday's cameras against today's, or a list of people against the ones
 * with permission.
 * <p>
 * <b>Count</b> is the size of the result, and <b>Is Empty</b> is the shape that gets wired most —
 * "did these two have anything in common at all?" — ready for the host's <b>If (Boolean)</b>.
 * <p>
 * <b>Both sides are required</b>, unlike <b>Set Union</b>'s and <b>Set Difference</b>'s, where an
 * unwired side still means something ("whichever I got", "everything is new"). An intersection
 * with nothing can only ever be empty, so leaving a side unwired is a wiring mistake and the
 * canvas says so rather than quietly producing an empty set forever. Run it that way anyway and
 * the result is empty. Membership is compared by {@link Sets text form}, so a {@code 3} in A
 * matches a {@code "3"} in B.
 */
@Display.Name("Set Intersection")
@Display.Description("Only the members that are in both sets.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"intersection", "set", "both", "common", "shared", "and", "overlap", "in both"})
@Node.Type("collections.SetIntersectionNode")
public class SetIntersectionNode extends BaseNode {

    private final NodeVariable<Set<?>> a = new NodeVariable<>("A", Sets.TYPE).required();
    private final NodeVariable<Set<?>> b = new NodeVariable<>("B", Sets.TYPE).required();

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> empty = new NodeVariable<>("Is Empty", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        // Index B's text forms once and test A against it, rather than asking Sets.contains per
        // member - that would be a scan inside a scan. Adding straight to the LinkedHashSet is
        // safe because A is already a set: no two of its members share a text form.
        Set<String> inB = Sets.keysOf(Sets.copyOf(b.getValue()));
        Set<Object> shared = new LinkedHashSet<>();
        for (Object member : Sets.copyOf(a.getValue())) {
            if (inB.contains(Lists.key(member))) {
                shared.add(member);
            }
        }
        result.setValue(Sets.frozen(shared));
        count.setValue(shared.size());
        empty.setValue(shared.isEmpty());
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
        addOutput(empty);
    }
}
