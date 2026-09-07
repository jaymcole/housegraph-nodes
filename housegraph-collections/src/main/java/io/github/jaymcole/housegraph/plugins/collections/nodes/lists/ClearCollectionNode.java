package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;

/**
 * Empties the named collection and republishes it empty — the resetting half of the pair with
 * {@link AddToCollectionNode}. Wire this immediately upstream of a loop whose body feeds Add To
 * Collection under the same Name, so the reset is guaranteed to finish before the loop's first
 * iteration can reach it:
 * <pre>trigger &rarr; Clear Collection &rarr; For Each &rarr; Body &rarr; Add To Collection</pre>
 * See {@link AddToCollectionNode}'s class documentation for why that guarantee matters and why it
 * used to require a racier, self-referencing wiring on one combined node.
 * <p>
 * <b>Being pulled for data does nothing.</b> A downstream node resolving List or Count without any
 * flow arriving here republishes the current contents rather than clearing them — the same
 * pull-adds-nothing rule {@link AddToCollectionNode} follows, for the same reason: a value read
 * must never be a side effect.
 */
@Display.Name("Clear Collection")
@Display.Description("Empties the named collection and republishes it empty.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"collect", "clear", "reset", "empty", "list", "loop"})
@Node.Type("collections.ClearCollectionNode")
public class ClearCollectionNode extends BaseNode {

    private final NodeVariable<String> name = new NodeVariable<>("Name", String.class, true).required();

    private final NodeVariable<List<?>> collected = new NodeVariable<>("List", Lists.TYPE);
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
     * {@link AddToCollectionNode#collect}.
     */
    void discardAll(String collectionName) {
        if (collectionName == null) {
            return;
        }
        List<Object> items = NamedCollections.get(collectionName);
        synchronized (items) {
            items.clear();
        }
    }

    /** Publishes an unmodifiable snapshot of {@code collectionName}'s current (now empty) contents. */
    private void publish(String collectionName) {
        List<Object> snapshot = AddToCollectionNode.snapshotOf(collectionName);
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
