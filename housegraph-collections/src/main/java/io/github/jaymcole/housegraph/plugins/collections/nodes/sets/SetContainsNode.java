package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.Set;

/**
 * Whether a set holds a given value. Wire <b>Found</b> into the host's <b>If (Boolean)</b> to
 * branch on it.
 * <p>
 * There is no Occurrences output here the way {@code ListContainsNode} has one for lists — a set
 * has at most one member matching any given value by definition, so a count would only ever be 0
 * or 1 and Found already says which.
 * <p>
 * Matching is the same {@link Lists#sameValue forgiving} comparison every lookup in this library
 * uses, and <b>Item</b> is a text input for the same reason {@code ListContainsNode}'s is.
 */
@Display.Name("Set Contains")
@Display.Description("Whether a set holds a given value.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"contains", "has", "includes", "member", "search", "find", "set"})
@Node.Type("collections.SetContainsNode")
public class SetContainsNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        found.setValue(Sets.contains(set.getValue(), item.getValue()));
    }

    @Override
    public void configureInputs() {
        addInput(set);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(found);
    }
}
