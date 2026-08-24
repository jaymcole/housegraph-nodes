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
 * Puts a list in order: numbers numerically, everything else alphabetically and case-insensitively
 * (see {@link Lists#NATURAL_ORDER} for the exact rule and why it isn't simply {@code compareTo}).
 * <p>
 * <b>There is no descending flag.</b> Chain <b>Reverse List</b> after this one — the flag would be
 * a second way to say a thing the graph can already say, and a boolean input can't be typed in
 * anyway (only {@code String}, {@code Integer} and {@code Float} have value editors), so it would
 * have to be wired from somewhere to be useful at all.
 */
@Display.Name("Sort List")
@Display.Description("Puts a list in order - numbers numerically, text alphabetically.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"sort", "order", "alphabetical", "ascending", "arrange", "rank", "list"})
@Node.Type("collections.SortListNode")
public class SortListNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> sorted = Lists.mutableCopyOf(list.getValue());
        sorted.sort(Lists.NATURAL_ORDER);
        result.setValue(Lists.frozen(sorted));
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
