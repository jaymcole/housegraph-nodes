package io.github.jaymcole.housegraph.plugins.collections.nodes;

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
 * Unpacks nested lists into one flat list. An entry that is itself a list contributes its own
 * entries; anything else contributes itself.
 * <p>
 * <b>Depth</b> caps how far in to go: {@code 1} unpacks one level (the usual case — a list of
 * per-camera result lists into one list of results), and a depth of zero or less unpacks all the
 * way down. Cycles can't arise from lists this library produces, since every one of them is a
 * fresh unmodifiable copy, but a self-referencing list from elsewhere would be caught by the
 * depth cap rather than looping forever.
 */
@Display.Name("Flatten")
@Display.Description("Unpacks nested lists into one flat list.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"flatten", "nested", "unnest", "merge", "lists", "combine", "depth"})
@Node.Type("collections.FlattenListNode")
public class FlattenListNode extends BaseNode {

    /** The stop for an "all the way down" flatten, so a pathological structure still terminates. */
    private static final int MAXIMUM_DEPTH = 32;

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<Integer> depth = new NodeVariable<>("Depth", Integer.class, true);

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    public FlattenListNode() {
        depth.setValue(1);
    }

    @Override
    public void process(ProcessContext ctx) {
        int authored = ctx.get(depth, 1);
        int limit = authored <= 0 ? MAXIMUM_DEPTH : Math.min(authored, MAXIMUM_DEPTH);
        List<Object> flat = new ArrayList<>();
        flatten(Lists.copyOf(list.getValue()), limit, flat);
        result.setValue(Lists.frozen(flat));
    }

    private static void flatten(List<Object> entries, int remaining, List<Object> into) {
        for (Object entry : entries) {
            if (remaining > 0 && entry instanceof List<?> nested) {
                flatten(Lists.copyOf(nested), remaining - 1, into);
            } else {
                into.add(entry);
            }
        }
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(depth);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
