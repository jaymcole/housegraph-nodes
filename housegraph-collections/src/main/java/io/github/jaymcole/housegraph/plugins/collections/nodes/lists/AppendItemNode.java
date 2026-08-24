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
 * A list with one more entry on the end. The input list is left alone and a new one is returned —
 * see <b>Reverse List</b> for why nothing here edits in place.
 * <p>
 * <b>Item is typed {@link Object}</b>, unlike the text-typed Item on <b>List Contains</b> and its
 * siblings, and the difference is deliberate. Those nodes take a thing to <em>look for</em>, which
 * is nearly always something a person types; this one takes a value to <em>keep</em>, which is
 * nearly always something another node produced — and an {@code Object} input accepts every type
 * there is, where a {@code String} input would reject an {@code Integer} output outright (the
 * host registers no {@code *}&nbsp;&rarr;&nbsp;{@code String} converters). To append a literal,
 * wire in a Constant.
 * <p>
 * A null item appends nothing, so an unwired Item leaves the list as it was rather than growing
 * it by a null.
 */
@Display.Name("Append Item")
@Display.Description("A list with one more entry on the end.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"append", "add", "push", "item", "entry", "list", "plus"})
@Node.Type("collections.AppendItemNode")
public class AppendItemNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.mutableCopyOf(list.getValue());
        Object value = item.getValue();
        if (value != null) {
            entries.add(value);
        }
        result.setValue(Lists.frozen(entries));
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
