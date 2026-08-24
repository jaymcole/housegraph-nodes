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
 * A map with one key set to a given value. The input map is left alone and a new one is returned —
 * the same never-mutate-what-you-were-given rule every node in this library follows.
 * <p>
 * If <b>Key</b> already matches an entry ({@link Lists#sameValue forgivingly}), that entry's value
 * is replaced in place, so the key keeps its original position — this is a real update, not a
 * remove-then-append. Otherwise the pair is added at the end.
 * <p>
 * <b>Value is typed {@link Object}</b>, unlike the text-typed Key, for the same reason
 * {@code AppendItemNode}'s Item is: a key is nearly always something a person types, but a value is
 * nearly always something another node produced. <b>Value stores exactly what it was given,
 * including null</b> — unlike <b>Append Item</b>, which drops a null Item, a null here is a
 * meaningful thing to put under a key (compare <b>Remove Key</b>, which takes the entry out
 * entirely) rather than the default state of an unwired port.
 */
@Display.Name("Put")
@Display.Description("A map with one key set to a given value.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"put", "set", "add", "key", "value", "map", "dictionary", "assign"})
@Node.Type("collections.PutNode")
public class PutNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true).required();
    private final NodeVariable<Object> value = new NodeVariable<>("Value", Object.class);

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        Map<Object, Object> entries = Maps.mutableCopyOf(map.getValue());
        Object wanted = key.getValue();
        Object existingKey = null;
        for (Object candidate : entries.keySet()) {
            if (Lists.sameValue(candidate, wanted)) {
                existingKey = candidate;
                break;
            }
        }
        entries.put(existingKey != null ? existingKey : wanted, value.getValue());
        result.setValue(Maps.frozen(entries));
    }

    @Override
    public void configureInputs() {
        addInput(map);
        addInput(key);
        addInput(value);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
