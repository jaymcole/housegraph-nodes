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
 * Whether a set holds a given member. Wire <b>Found</b> into the host's <b>If (Boolean)</b> to
 * branch on it — the shape this whole library uses instead of branching on its own flow ports.
 * <p>
 * <b>There is no Occurrences output</b>, unlike <b>List Contains</b>: a set holds a thing once or
 * not at all, and an output that could only ever be 0 or 1 would just be Found in a worse costume.
 * <p>
 * Matching is by {@link Sets#contains text form}, so a typed <b>Item</b> of {@code "3"} finds a
 * member that arrived as the number {@code 3}. That forgiveness is what makes the text field
 * usable at all — a set port's member type is erased, so nothing here can know what is in there.
 * A non-text source feeds Item through one of the host's <b>… to String</b> converter nodes; the
 * comparison then does the rest, so the <em>set</em> may hold anything at all.
 */
@Display.Name("Set Contains")
@Display.Description("Whether a set holds a given member.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"set", "contains", "has", "includes", "member", "in", "search", "find"})
@Node.Type("collections.SetContainsNode")
public class SetContainsNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        found.setValue(Sets.contains(Sets.copyOf(set.getValue()), item.getValue()));
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
