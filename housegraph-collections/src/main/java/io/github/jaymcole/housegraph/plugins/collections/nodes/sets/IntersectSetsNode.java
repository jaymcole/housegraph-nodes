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
 * Only the members <b>First</b> and <b>Second</b> have in common. An unwired side is treated as
 * empty, so the result is empty rather than the other side passing through — unlike <b>Union</b>
 * and <b>Merge Maps</b>, "whichever one I actually got" is not a sensible reading of "in common"
 * when there's only one to compare.
 * <p>
 * The kept members are <b>First</b>'s own values, not <b>Second</b>'s, for whichever pair matches
 * {@link io.github.jaymcole.housegraph.plugins.collections.Lists#sameValue forgivingly} — an
 * arbitrary but deterministic choice between two values a graph has already decided count as the
 * same.
 */
@Display.Name("Intersect")
@Display.Description("Only the members two sets have in common.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"intersect", "intersection", "common", "both", "sets", "and"})
@Node.Type("collections.IntersectSetsNode")
public class IntersectSetsNode extends BaseNode {

    private final NodeVariable<Set<?>> first = new NodeVariable<>("First", Sets.TYPE);
    private final NodeVariable<Set<?>> second = new NodeVariable<>("Second", Sets.TYPE);

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        Set<?> other = Sets.copyOf(second.getValue());
        LinkedHashSet<Object> common = new LinkedHashSet<>();
        for (Object candidate : Sets.copyOf(first.getValue())) {
            if (Sets.contains(other, candidate)) {
                common.add(candidate);
            }
        }
        result.setValue(Sets.frozen(common));
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
