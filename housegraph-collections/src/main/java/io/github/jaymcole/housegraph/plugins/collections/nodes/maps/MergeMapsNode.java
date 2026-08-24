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
 * Two maps as one. Every entry of <b>Base</b> comes through, then every entry of <b>Overrides</b>
 * on top — so where both hold the same key, <b>Overrides wins</b>. That is the shape this is for:
 * a map of defaults with a map of what the user actually set laid over it.
 * <p>
 * <b>The port names carry the whole contract</b>, which is why they aren't "Map A" and "Map B".
 * With A and B the answer to "which one wins?" would live only in this documentation, and a graph
 * that got it backwards would go on producing plausible, wrong values indefinitely.
 * <p>
 * <b>Overridden</b> counts the keys Overrides actually took over, so a graph can notice a
 * collision it did not intend. Order follows Base first, then whatever Overrides adds that Base
 * did not have; an overriding entry keeps Base's position rather than jumping to the end, so the
 * merged map reads in the order a person laid the defaults out. Either map may be unwired, which
 * reads as empty.
 */
@Display.Name("Merge Maps")
@Display.Description("Two maps as one, with the second winning on any shared key.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"merge", "combine", "maps", "overlay", "defaults", "override", "join", "union"})
@Node.Type("collections.MergeMapsNode")
public class MergeMapsNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> base = new NodeVariable<>("Base", Maps.TYPE);
    private final NodeVariable<Map<?, ?>> overrides = new NodeVariable<>("Overrides", Maps.TYPE);

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Integer> overridden = new NodeVariable<>("Overridden", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> merged = Maps.mutableCopyOf(base.getValue());
        int collisions = 0;
        for (Map.Entry<String, Object> entry : Maps.copyOf(overrides.getValue()).entrySet()) {
            if (merged.put(entry.getKey(), entry.getValue()) != null) {
                collisions++;
            }
        }
        result.setValue(Maps.frozen(merged));
        overridden.setValue(collisions);
    }

    @Override
    public void configureInputs() {
        addInput(base);
        addInput(overrides);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(overridden);
    }
}
