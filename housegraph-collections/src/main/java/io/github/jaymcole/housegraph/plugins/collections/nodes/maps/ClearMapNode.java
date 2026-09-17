package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Map;

/**
 * Empties the named collection and republishes it empty — the resetting half of the pair with
 * {@link PutInMapNode}, and the map twin of {@code ClearCollectionNode} in the {@code lists}
 * package. Wire this immediately upstream of a loop whose body feeds Put In Map under the same
 * Name, so the reset is guaranteed to finish before the loop's first iteration can reach it:
 * <pre>trigger &rarr; Clear Map &rarr; For Each &rarr; Body &rarr; Put In Map</pre>
 * See {@code AddToCollectionNode}'s class documentation for why that guarantee matters.
 * <p>
 * <b>Being pulled for data does nothing.</b> A downstream node resolving Map or Count without any
 * flow arriving here republishes the current contents rather than clearing them — but, for the
 * reason {@link PutInMapNode} gives, read the map through {@link GetNamedMapNode} instead.
 */
@Display.Name("Clear Map")
@Display.Description("Empties the named collection and republishes it empty.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"collect", "clear", "reset", "empty", "map", "loop"})
@Node.Type("collections.ClearMapNode")
public class ClearMapNode extends BaseNode {

    private final NodeVariable<String> name = new NodeVariable<>("Name", String.class, true).required();

    private final NodeVariable<Map<?, ?>> collected = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        if (ctx.wasTriggeredVia(in)) {
            discardAll(name.getValue());
        }
        publish(name.getValue());
    }

    /**
     * Empties {@code collectionName}, ignoring a null name. Package-private so a test can exercise
     * it without a live {@code NodeGraph}, for the same reason as
     * {@code AddToCollectionNode#collect}.
     */
    void discardAll(String collectionName) {
        if (collectionName == null) {
            return;
        }
        Map<String, Object> entries = NamedMaps.get(collectionName);
        synchronized (entries) {
            entries.clear();
        }
    }

    /** Publishes an unmodifiable snapshot of {@code collectionName}'s current (now empty) contents. */
    private void publish(String collectionName) {
        Map<String, Object> snapshot = PutInMapNode.snapshotOf(collectionName);
        collected.setValue(snapshot);
        count.setValue(snapshot.size());
    }

    @Override
    public void configureInputs() {
        addInput(name);
    }

    @Override
    public void configureOutputs() {
        addOutput(collected);
        addOutput(count);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
