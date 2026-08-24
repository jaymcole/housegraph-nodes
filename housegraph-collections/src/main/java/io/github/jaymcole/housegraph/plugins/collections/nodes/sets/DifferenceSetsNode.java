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
 * <b>First</b> with every member of <b>Second</b> taken out — what's in the first set but not the
 * second. Order matters here, unlike <b>Union</b> and <b>Intersect</b>: swapping the two inputs
 * generally gives a different answer, which is the whole point of a difference rather than an
 * intersection.
 * <p>
 * An unwired <b>Second</b> leaves <b>First</b> unchanged (nothing to take out); an unwired
 * <b>First</b> yields an empty set (nothing to take anything out of).
 */
@Display.Name("Difference")
@Display.Description("What's in the first set but not the second.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"difference", "subtract", "minus", "except", "without", "sets"})
@Node.Type("collections.DifferenceSetsNode")
public class DifferenceSetsNode extends BaseNode {

    private final NodeVariable<Set<?>> first = new NodeVariable<>("First", Sets.TYPE);
    private final NodeVariable<Set<?>> second = new NodeVariable<>("Second", Sets.TYPE);

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        Set<?> subtract = Sets.copyOf(second.getValue());
        LinkedHashSet<Object> remaining = new LinkedHashSet<>();
        for (Object candidate : Sets.copyOf(first.getValue())) {
            if (!Sets.contains(subtract, candidate)) {
                remaining.add(candidate);
            }
        }
        result.setValue(Sets.frozen(remaining));
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
