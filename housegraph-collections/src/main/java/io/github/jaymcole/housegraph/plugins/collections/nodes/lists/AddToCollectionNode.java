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
 * Appends Item to the named collection and republishes it — the accumulating half of the pair
 * with {@link ClearCollectionNode}. Wire the loop's Body into this node's flow-in and whatever the
 * body computed into Item; whoever needs the finished list reads it back under the same Name,
 * whether or not they are wired to this particular node.
 * <p>
 * <b>This replaces the single {@code CollectItemsNode}, which had an Add port and a Clear port on
 * one node.</b> Sequencing "reset, then loop" needs Clear to finish before the loop's first
 * iteration can reach Add. On the one-node design that meant either fanning a shared trigger out
 * to both ports — which races, because sibling flow edges run concurrently and the loop's Add
 * arrivals could win the node's re-entry gate before Clear ever got a turn, silently abandoning it
 * (see {@code docs/engine/execution-policy.md} in HouseGraph) — or routing the trigger through the
 * node's own flow-out before the loop, which drew the wiring back into itself and read as a cycle.
 * Naming the collection instead of wiring it turns that into a straight line: trigger &rarr;
 * {@link ClearCollectionNode} &rarr; For Each &rarr; Body &rarr; this node. No edge doubles back.
 * <p>
 * <b>Being pulled for data adds nothing.</b> A downstream node resolving List or Count without any
 * flow arriving here (see {@code ProcessContext#triggeredVia}) republishes the current contents
 * and stops. Appending on a pull would make the list grow every time something read it.
 * <p>
 * <b>The collection is deliberately memory-only and outlives this node.</b> Nothing is written to
 * the save file, so a reloaded graph starts empty; and removing this node neither clears nor
 * otherwise affects the named collection, since other Add or Clear nodes may still share the name.
 * See {@link NamedCollections}.
 */
@Display.Name("Add To Collection")
@Display.Description("Appends Item to the named collection and republishes it.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"collect", "accumulate", "gather", "append", "build", "results", "list", "loop", "add"})
@Node.Type("collections.AddToCollectionNode")
public class AddToCollectionNode extends BaseNode {

    private final NodeVariable<String> name = new NodeVariable<>("Name", String.class, true).required();
    private final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class);

    private final NodeVariable<List<?>> collected = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        if (ctx.wasTriggeredVia(in)) {
            collect(name.getValue(), item.getValue());
        }
        publish(name.getValue());
    }

    /**
     * Appends one item under {@code collectionName}, ignoring a null name (an unwired or blank
     * Name must not silently share a collection with every other unnamed node) and a null value
     * (an unwired Item must not grow the list by a null). Package-private so a test can exercise
     * the accumulation without a live {@code NodeGraph}: the {@code ProcessContext} carrying "did
     * flow arrive here" can only be built by the engine, so the routing in {@code process()} above
     * is only observable in a running graph, but what it routes <em>to</em> is testable here.
     */
    void collect(String collectionName, Object value) {
        if (collectionName == null || value == null) {
            return;
        }
        List<Object> items = NamedCollections.get(collectionName);
        synchronized (items) {
            items.add(value);
        }
    }

    /** Publishes an unmodifiable snapshot of {@code collectionName}'s current contents. */
    private void publish(String collectionName) {
        List<Object> snapshot = snapshotOf(collectionName);
        collected.setValue(snapshot);
        count.setValue(snapshot.size());
    }

    /**
     * An unmodifiable snapshot of {@code collectionName}'s current contents, or the empty list for
     * a null name. Package-private so {@link ClearCollectionNode} and tests can read what a name
     * currently holds without going through this node's ports.
     */
    static List<Object> snapshotOf(String collectionName) {
        if (collectionName == null) {
            return List.of();
        }
        List<Object> items = NamedCollections.get(collectionName);
        synchronized (items) {
            return Lists.frozen(items);
        }
    }

    @Override
    public void configureInputs() {
        addInput(name);
        addInput(item);
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
