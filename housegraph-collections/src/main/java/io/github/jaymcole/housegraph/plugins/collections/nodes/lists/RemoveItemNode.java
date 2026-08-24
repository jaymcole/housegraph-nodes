package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * A list with a given entry taken out — <b>every</b> occurrence of it, not just the first, since
 * "remove the second copy but keep the third" is not a thing anyone wants from a node with one
 * Item field. <b>Removed</b> says how many went.
 * <p>
 * Matching is the same {@link Lists#sameValue forgiving} comparison as <b>List Contains</b>, and
 * <b>Item</b> is a text input for the same reason — see that node. To remove by position instead,
 * use <b>Slice List</b>, or filter.
 */
@Display.Name("Remove Item")
@Display.Description("A list with every copy of a given entry taken out.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"remove", "delete", "drop", "exclude", "without", "item", "list"})
@Node.Type("collections.RemoveItemNode")
public class RemoveItemNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> removed = new NodeVariable<>("Removed", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Object unwanted = item.getValue();
        List<Object> entries = Lists.copyOf(list.getValue());
        List<Object> kept = new ArrayList<>();
        for (Object entry : entries) {
            if (!Lists.sameValue(entry, unwanted)) {
                kept.add(entry);
            }
        }
        result.setValue(Lists.frozen(kept));
        removed.setValue(entries.size() - kept.size());
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(removed);
    }
}
