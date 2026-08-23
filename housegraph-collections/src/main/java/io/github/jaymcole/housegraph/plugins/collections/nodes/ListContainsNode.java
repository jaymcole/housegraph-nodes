package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;

/**
 * Whether a list holds a given entry, and how many times. Wire <b>Found</b> into the host's
 * <b>If (Boolean)</b> to branch on it.
 * <p>
 * Matching is {@link Lists#sameValue forgiving}: equal by {@code equals}, or equal once both sides
 * are rendered as text. That is what makes the manually-typed <b>Item</b> field usable at all —
 * a list port's element type is erased, so a typed {@code "3"} would otherwise never match an
 * upstream node's {@code 3}.
 * <p>
 * <b>Item is a text input</b>, which is what makes it typeable at all — only {@code String},
 * {@code Integer} and {@code Float} have registered value editors. Text is also the right shape
 * for the thing being asked here, which is nearly always a name or a label. A non-text source
 * feeds it through one of the host's <b>… to String</b> converter nodes; the forgiving comparison
 * above then does the rest, so the <em>list</em> may hold anything at all.
 */
@Display.Name("List Contains")
@Display.Description("Whether a list holds a given entry, and how many times.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"contains", "has", "includes", "member", "search", "find", "list"})
@Node.Type("collections.ListContainsNode")
public class ListContainsNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);
    private final NodeVariable<Integer> occurrences = new NodeVariable<>("Occurrences", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Object wanted = item.getValue();
        int matches = 0;
        for (Object entry : Lists.copyOf(list.getValue())) {
            if (Lists.sameValue(entry, wanted)) {
                matches++;
            }
        }
        found.setValue(matches > 0);
        occurrences.setValue(matches);
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(found);
        addOutput(occurrences);
    }
}
