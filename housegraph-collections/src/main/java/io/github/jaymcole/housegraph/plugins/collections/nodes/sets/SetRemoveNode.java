package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.Set;

/**
 * A set with one member taken out. The input set is left alone and a new one is published; the
 * members that stay keep their order.
 * <p>
 * <b>Removed</b> says whether there was anything to take, so "stop tracking this camera" can tell
 * "it was being tracked" from "it never was". A member that isn't there is not an error.
 * <p>
 * <b>Item is a text input</b>, matching <b>Set Contains</b> and for the same reason: this node
 * takes a thing to look for. Matching is by {@link Sets#remove text form}, so a typed {@code "3"}
 * takes out the member that arrived as the number {@code 3} — which plain {@code Set.remove} would
 * miss.
 */
@Display.Name("Set Remove")
@Display.Description("A set with a given member taken out.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"set", "remove", "delete", "drop", "exclude", "without", "member", "minus"})
@Node.Type("collections.SetRemoveNode")
public class SetRemoveNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Boolean> removed = new NodeVariable<>("Removed", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Set<Object> members = Sets.mutableCopyOf(set.getValue());
        boolean shrank = Sets.remove(members, item.getValue());
        result.setValue(Sets.frozen(members));
        removed.setValue(shrank);
    }

    @Override
    public void configureInputs() {
        addInput(set);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(removed);
    }
}
