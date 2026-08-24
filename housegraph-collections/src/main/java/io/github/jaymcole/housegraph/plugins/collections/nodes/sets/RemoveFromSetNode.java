package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A set with a given member taken out. <b>Removed</b> says whether it was actually there — the
 * same "did we get one?" shape {@code RemoveKeyNode} uses for maps.
 * <p>
 * Matching is the same {@link Lists#sameValue forgiving} comparison every lookup in this library
 * uses, and <b>Item</b> is a text input for the same reason {@code ListContainsNode}'s Item is.
 */
@Display.Name("Remove From Set")
@Display.Description("A set with a given member taken out.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"remove", "delete", "drop", "exclude", "without", "set", "member"})
@Node.Type("collections.RemoveFromSetNode")
public class RemoveFromSetNode extends BaseNode {

    private final NodeVariable<Set<?>> set = new NodeVariable<>("Set", Sets.TYPE).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);
    private final NodeVariable<Boolean> removed = new NodeVariable<>("Removed", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Object unwanted = item.getValue();
        LinkedHashSet<Object> members = Sets.mutableCopyOf(set.getValue());
        boolean any = false;
        Iterator<Object> iterator = members.iterator();
        while (iterator.hasNext()) {
            if (Lists.sameValue(iterator.next(), unwanted)) {
                iterator.remove();
                any = true;
                break;
            }
        }
        result.setValue(Sets.frozen(members));
        removed.setValue(any);
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
