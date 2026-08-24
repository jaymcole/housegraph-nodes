package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Iterator;
import java.util.Map;

/**
 * A map with one key's entry taken out. <b>Removed</b> says whether that key was actually there —
 * removing an absent key leaves the map exactly as it was, and this is how a graph tells the two
 * cases apart without a second lookup.
 * <p>
 * Matching is the same {@link Lists#sameValue forgiving} comparison every lookup in this library
 * uses, and <b>Key</b> is a text input for the same reason {@code ListContainsNode}'s Item is.
 */
@Display.Name("Remove Key")
@Display.Description("A map with one key's entry taken out.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"remove", "delete", "drop", "key", "map", "dictionary", "without"})
@Node.Type("collections.RemoveKeyNode")
public class RemoveKeyNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true).required();

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Boolean> removed = new NodeVariable<>("Removed", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Object wanted = key.getValue();
        Map<Object, Object> entries = Maps.mutableCopyOf(map.getValue());
        boolean any = false;
        Iterator<Object> keys = entries.keySet().iterator();
        while (keys.hasNext()) {
            if (Lists.sameValue(keys.next(), wanted)) {
                keys.remove();
                any = true;
                break;
            }
        }
        result.setValue(Maps.frozen(entries));
        removed.setValue(any);
    }

    @Override
    public void configureInputs() {
        addInput(map);
        addInput(key);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(removed);
    }
}
