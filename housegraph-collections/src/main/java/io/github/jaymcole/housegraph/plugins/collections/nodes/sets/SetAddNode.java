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
 * A set with one more member in it. The input set is left alone and a new one is published, as
 * everywhere else in this library.
 * <p>
 * <b>Added</b> says whether the set actually grew — false when it already held something with the
 * same text form. That is the output worth wiring: "was this new?" is the question a set is
 * usually being asked, and answering it here saves a <b>Set Contains</b> beforehand that would
 * have to be kept in step with this node.
 * <p>
 * <b>Item is typed {@link Object}</b>, unlike the text-typed Item on <b>Set Contains</b>, and the
 * difference is the one <b>Append Item</b> explains: that node takes a thing to look <em>for</em>,
 * which a person types, while this takes a value to <em>keep</em>, which another node produced —
 * and an {@code Object} input accepts every type there is where a {@code String} input would
 * reject an {@code Integer} output outright. To add a literal, wire in a Constant.
 * <p>
 * A null item adds nothing, so an unwired Item leaves the set as it was.
 */
@Display.Name("Set Add")
@Display.Description("A set with one more member, reporting whether it was new.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"set", "add", "insert", "member", "include", "plus", "new"})
@Node.Type("collections.SetAddNode")
public class SetAddNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class).required();

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Boolean> added = new NodeVariable<>("Added", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Set<Object> members = Sets.mutableCopyOf(set.getValue());
        boolean grew = Sets.add(members, item.getValue());
        result.setValue(Sets.frozen(members));
        added.setValue(grew);
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
