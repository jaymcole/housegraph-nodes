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
 * Where an entry sits in a list: the zero-based index of the first match, or {@code -1} when it
 * isn't there. <b>Found</b> says which of those two happened without anyone having to know that
 * {@code -1} is the sentinel.
 * <p>
 * Matching is the same {@link Lists#sameValue forgiving} comparison <b>List Contains</b> uses, and
 * <b>Item</b> is a text input for the same reason — see that node.
 */
@Display.Name("Index Of")
@Display.Description("The position of an entry in a list, or -1 when it isn't there.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"index", "position", "find", "locate", "search", "where", "list"})
@Node.Type("collections.IndexOfNode")
public class IndexOfNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<Integer> index = new NodeVariable<>("Index", Integer.class);
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Object wanted = item.getValue();
        List<Object> entries = Lists.copyOf(list.getValue());
        int at = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (Lists.sameValue(entries.get(i), wanted)) {
                at = i;
                break;
            }
        }
        index.setValue(at);
        found.setValue(at >= 0);
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(index);
        addOutput(found);
    }
}
