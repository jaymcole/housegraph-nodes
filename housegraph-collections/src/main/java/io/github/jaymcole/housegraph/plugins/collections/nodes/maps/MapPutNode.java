package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Map;

/**
 * A map with one more entry in it. The input map is left alone and a new one is published — the
 * same rule <b>Append Item</b> follows, for the same reason: nothing in this library edits a
 * collection it was handed.
 * <p>
 * An existing key is overwritten, and <b>Replaced</b> says whether that happened, so a graph can
 * tell "added something new" from "changed something that was already there" without a second
 * lookup. The entry keeps its original position when it replaces one, and goes on the end when it
 * is new.
 * <p>
 * <b>A half-filled pair does nothing at all</b>: with no Key, or no Value, the map comes through
 * unchanged and Replaced is false (see {@link Maps#put} for why a keyed null is not an entry).
 * <b>Value is typed {@link Object}</b> so it accepts anything another node produced; to store a
 * literal, wire in a Constant.
 */
@Display.Name("Map Put")
@Display.Description("A map with one more entry, replacing any entry already under that key.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"map", "put", "set", "add", "store", "insert", "key", "value", "update"})
@Node.Type("collections.MapPutNode")
public class MapPutNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true).required();
    private final NodeVariable<Object> value = new NodeVariable<>("Value", Object.class).required();

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Boolean> replaced = new NodeVariable<>("Replaced", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> entries = Maps.mutableCopyOf(map.getValue());
        String wanted = Maps.key(key.getValue());
        boolean overwrote = wanted != null && entries.containsKey(wanted);
        boolean stored = Maps.put(entries, key.getValue(), value.getValue());
        result.setValue(Maps.frozen(entries));
        replaced.setValue(stored && overwrote);
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
        addOutput(replaced);
    }
}
