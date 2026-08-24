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
 * One value out of a map, by key. This is the map half of <b>Get Item</b>, and it reports the same
 * way: a key that isn't there is not an error — <b>Value</b> is null, <b>Found</b> is false, and
 * the "did we get one?" decision goes into the host's <b>If (Boolean)</b> where the graph can see
 * it, rather than being hidden in a flow branch here.
 * <p>
 * <b>Found can be trusted.</b> No map this library publishes holds a null value (see
 * {@link Maps#put}), so Found is false exactly when the key is missing, never because the value
 * happened to be nothing.
 * <p>
 * Lookup is by {@link Maps#key text form}, so a <b>Key</b> of {@code "3"} finds an entry stored
 * under a {@code 3} — the erasure that makes that necessary is explained in {@link Lists}. A blank
 * Key finds nothing rather than matching some empty-string entry, because a map here has no
 * empty-string entries to match.
 * <p>
 * <b>Value is typed {@link Object}</b>, and has to be: a map port's value type is erased, so
 * nothing here can know what came out. Wiring it into a strongly-typed input may need a converter
 * node. <b>Default</b> fills in for a missing key, so a lookup that misses can still feed
 * something downstream; it does not change what Found says.
 */
@Display.Name("Map Get")
@Display.Description("One value from a map by key, with a Found flag and an optional default.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"map", "get", "lookup", "key", "value", "read", "dictionary", "find"})
@Node.Type("collections.MapGetNode")
public class MapGetNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true).required();
    private final NodeVariable<Object> fallback = new NodeVariable<>("Default", Object.class);

    private final NodeVariable<Object> value = new NodeVariable<>("Value", Object.class);
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> entries = Maps.copyOf(map.getValue());
        String wanted = Maps.key(key.getValue());
        Object hit = wanted == null ? null : entries.get(wanted);
        value.setValue(hit != null ? hit : fallback.getValue());
        found.setValue(hit != null);
    }

    @Override
    public void configureInputs() {
        addInput(map);
        addInput(key);
        addInput(fallback);
    }

    @Override
    public void configureOutputs() {
        addOutput(value);
        addOutput(found);
    }
}
