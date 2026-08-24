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
 * A map with one entry taken out, by key. <b>Removed</b> says whether there was one to take, and
 * the value that went comes out on <b>Value</b> — a removal is nearly always followed by wanting
 * to know what was there, and it is one lookup either way.
 * <p>
 * The input map is left alone and a new one is published; the entries that stay keep their order.
 * A key that isn't there is not an error: the map comes through unchanged, Removed is false and
 * Value is null. Matching is by {@link Maps#key text form}, as everywhere else here.
 */
@Display.Name("Map Remove")
@Display.Description("A map with the entry under a given key taken out.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"map", "remove", "delete", "drop", "key", "without", "unset", "erase"})
@Node.Type("collections.MapRemoveNode")
public class MapRemoveNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true).required();

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Object> value = new NodeVariable<>("Value", Object.class);
    private final NodeVariable<Boolean> removed = new NodeVariable<>("Removed", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> entries = Maps.mutableCopyOf(map.getValue());
        String unwanted = Maps.key(key.getValue());
        Object went = unwanted == null ? null : entries.remove(unwanted);
        result.setValue(Maps.frozen(entries));
        value.setValue(went);
        removed.setValue(went != null);
    }

    @Override
    public void configureInputs() {
        addInput(map);
        addInput(key);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(value);
        addOutput(removed);
    }
}
