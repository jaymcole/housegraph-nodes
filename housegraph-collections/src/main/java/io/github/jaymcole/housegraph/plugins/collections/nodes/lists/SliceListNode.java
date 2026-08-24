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
 * A run of entries out of a list: <b>Count</b> of them, starting at <b>Start</b>. This is the
 * take/drop/first-N/last-N node — {@code Start 0, Count 5} is the first five, {@code Start 5} with
 * a count past the end is everything after the first five, and a negative start counts back from
 * the end, so {@code Start -3} is the last three.
 * <p>
 * A count of zero or less means "everything from Start onwards", which is the reading that makes
 * an unfilled Count field useful rather than empty. Asking for more than there is yields what
 * there is; a start beyond either end yields an empty list. None of those are errors — a slice
 * computed from live data routinely lands off the end, and failing the graph over it would be
 * worse than returning nothing.
 */
@Display.Name("Slice List")
@Display.Description("A run of entries from a list: take, drop, first N or last N.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"slice", "take", "drop", "first", "last", "sub list", "range", "limit", "top"})
@Node.Type("collections.SliceListNode")
public class SliceListNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<Integer> start = new NodeVariable<>("Start", Integer.class, true);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class, true);

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    public SliceListNode() {
        start.setValue(0);
        count.setValue(0);
    }

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.copyOf(list.getValue());
        int size = entries.size();
        if (size == 0) {
            result.setValue(List.of());
            return;
        }

        int authoredStart = ctx.get(start, 0);
        int from = authoredStart < 0 ? Math.max(0, size + authoredStart) : authoredStart;
        if (from >= size) {
            result.setValue(List.of());
            return;
        }

        int howMany = ctx.get(count, 0);
        int to = howMany <= 0 ? size : (int) Math.min((long) from + howMany, size);
        result.setValue(Lists.frozen(entries.subList(from, to)));
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(start);
        addInput(count);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
