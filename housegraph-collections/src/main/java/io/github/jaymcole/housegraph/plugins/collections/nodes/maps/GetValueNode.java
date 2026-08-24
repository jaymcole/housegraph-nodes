package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Map;

/**
 * The value stored under a key, or nothing if there isn't one. <b>Found</b> says which of those two
 * happened, the same "did we get one?" shape {@code GetItemNode} uses for lists, so both branch the
 * same way through the host's <b>If (Boolean)</b>.
 * <p>
 * Matching is the same {@link Lists#sameValue forgiving} comparison every lookup in this library
 * uses, and <b>Key</b> is a text input for the same reason {@code ListContainsNode}'s Item is: a
 * key is nearly always something a person types.
 * <p>
 * <b>Value is typed {@link Object}</b>, and has to be: a map port's value type is erased (see
 * {@link Maps}), so nothing here can know what was stored. Wiring it into a strongly-typed input
 * may need a converter node.
 */
@Display.Name("Get Value")
@Display.Description("The value stored under a key in a map, or nothing if there isn't one.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"get", "value", "key", "lookup", "map", "dictionary", "read"})
@Node.Type("collections.GetValueNode")
public class GetValueNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true).required();

    private final NodeVariable<Object> value = new NodeVariable<>("Value", Object.class);
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Object wanted = key.getValue();
        Object matched = null;
        boolean matchedAny = false;
        for (Map.Entry<Object, Object> entry : Maps.copyOf(map.getValue()).entrySet()) {
            if (Lists.sameValue(entry.getKey(), wanted)) {
                matched = entry.getValue();
                matchedAny = true;
                break;
            }
        }
        value.setValue(matched);
        found.setValue(matchedAny);
    }

    @Override
    public void configureInputs() {
        addInput(map);
        addInput(key);
    }

    @Override
    public void configureOutputs() {
        addOutput(value);
        addOutput(found);
    }
}
