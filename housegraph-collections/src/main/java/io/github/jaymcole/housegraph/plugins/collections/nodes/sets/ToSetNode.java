package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.List;
import java.util.Set;

/**
 * Turns a list into a set: repeats dropped, first of each kept, order preserved. <b>This is the
 * way into this package</b> — every other node here takes a set, and a set comes from a list.
 * <p>
 * <b>How this differs from Distinct</b>, which does the same dropping to a list: what comes out.
 * <b>Distinct</b> gives a list, for feeding <b>For Each</b> or <b>Join List</b>; this gives a set,
 * for asking it against another set. Reach for Distinct when you want the deduplicated entries;
 * reach for this when the next question is "what's in both?" or "what's new since last time?".
 * <b>Removed</b> reports how many repeats went, the same number Distinct reports.
 * <p>
 * <b>There is no "Build Set" that grows wired slots</b>, the way <b>Build Map</b> and <b>Build
 * List</b> do. <b>Build List</b> into this node is that node, made of two things that already
 * exist — and unlike the map case there is no second half to pair up, so the composed form loses
 * nothing.
 * <p>
 * Null entries contribute nothing, so a list carrying the nulls the host's object decomposer can
 * emit does not gain a null member on the way in.
 */
@Display.Name("To Set")
@Display.Description("A list as a set - repeats dropped, first of each kept, order preserved.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"set", "to set", "unique", "distinct", "dedupe", "from list", "convert", "collection"})
@Node.Type("collections.ToSetNode")
public class ToSetNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Integer> removed = new NodeVariable<>("Removed", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.copyOf(list.getValue());
        Set<Object> members = Sets.mutableCopyOf(entries);
        result.setValue(Sets.frozen(members));
        removed.setValue(entries.size() - members.size());
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
