package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drops repeats, keeping the first of each and the original order. Between a motion sensor and a
 * notification, this is what stops the same camera being announced four times.
 * <p>
 * Two entries count as the same when they have the same {@link Lists#key text form} — the same
 * forgiving identity <b>List Contains</b> uses, chosen for the same reason: a list's element type
 * is erased, so identity by {@code equals} alone would leave a {@code 3} and a {@code "3"} as two
 * different things when nothing downstream could tell them apart. <b>Removed</b> reports how many
 * entries this dropped.
 */
@Display.Name("Distinct")
@Display.Description("Drops repeated entries, keeping the first of each.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"distinct", "unique", "dedupe", "duplicates", "repeats", "list", "set"})
@Node.Type("collections.DistinctListNode")
public class DistinctListNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> removed = new NodeVariable<>("Removed", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.copyOf(list.getValue());
        Set<String> seen = new HashSet<>();
        List<Object> kept = new ArrayList<>();
        for (Object entry : entries) {
            if (seen.add(Lists.key(entry))) {
                kept.add(entry);
            }
        }
        result.setValue(Lists.frozen(kept));
        removed.setValue(entries.size() - kept.size());
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(removed);
    }
}
