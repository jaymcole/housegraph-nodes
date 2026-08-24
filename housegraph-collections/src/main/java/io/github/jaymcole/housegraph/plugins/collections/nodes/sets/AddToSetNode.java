package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A set with one more member, unless it was already there. The input set is left alone and a new
 * one is returned — the same never-mutate-what-you-were-given rule every node in this library
 * follows. <b>Added</b> reports whether the member was actually new, the same true/false a
 * {@link java.util.Set#add} call itself returns, so a graph can tell "grew" from "already had it"
 * without a separate <b>Set Contains</b> lookup first.
 * <p>
 * <b>Item is typed {@link Object}</b>, unlike the text-typed Item on <b>Set Contains</b>, for the
 * same reason {@code AppendItemNode}'s Item is: this one takes a value to <em>keep</em>, which is
 * nearly always something another node produced. A null item adds nothing, the same as
 * <b>Append Item</b>.
 */
@Display.Name("Add To Set")
@Display.Description("A set with one more member, unless it was already there.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"add", "insert", "set", "member", "item", "plus"})
@Node.Type("collections.AddToSetNode")
public class AddToSetNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class).required();

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Boolean> added = new NodeVariable<>("Added", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        LinkedHashSet<Object> members = Sets.mutableCopyOf(set.getValue());
        Object value = item.getValue();
        boolean isNew = value != null && !Sets.contains(members, value);
        if (isNew) {
            members.add(value);
        }
        result.setValue(Sets.frozen(members));
        added.setValue(isNew);
    }

    @Override
    public void configureInputs() {
        addInput(set);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(added);
    }
}
