package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;

/**
 * One entry out of a list, by position. <b>Index</b> is zero-based, and <b>negative counts back
 * from the end</b> — {@code -1} is the last entry — so "the most recent one" needs no arithmetic
 * and no Count node.
 * <p>
 * An index outside the list is not an error: <b>Item</b> is null and <b>Found</b> is false. That
 * pairing is the deliberate shape of this library (see the package documentation): rather than
 * branching on its own flow ports, the node reports the outcome as a boolean for the host's
 * <b>If (Boolean)</b> to branch on, which keeps the "did we get one?" decision visible in the
 * graph instead of buried in this node.
 * <p>
 * <b>Item is typed {@link Object}</b>, and has to be: a list port's element type is erased (see
 * {@link Lists}), so nothing here can know what came out. Wiring it into a strongly-typed input
 * may need a converter node.
 */
@Display.Name("Get Item")
@Display.Description("One entry from a list by index, negative counting from the end.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"get", "item", "index", "element", "at", "first", "last", "list", "nth"})
@Node.Type("collections.GetItemNode")
public class GetItemNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<Integer> index = new NodeVariable<>("Index", Integer.class, true).required();

    private final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class);
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    public GetItemNode() {
        index.setValue(0);
    }

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.copyOf(list.getValue());
        int at = Lists.resolveIndex(ctx.get(index, 0), entries.size());
        item.setValue(at < 0 ? null : entries.get(at));
        found.setValue(at >= 0);
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(index);
    }

    @Override
    public void configureOutputs() {
        addOutput(item);
        addOutput(found);
    }
}
