package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * Gathers items one firing at a time into a list that outlives the firing. This is the other half
 * of the host's <b>For Each</b>: the loop hands you one element at a time, and without somewhere
 * to put the results there is no way to build a list <em>out of</em> a loop. Wire the loop's Body
 * into <b>Add</b>, whatever the body computed into <b>Item</b>, and the loop's Completed into
 * whatever should read the finished <b>List</b>.
 * <p>
 * <b>Add</b> appends the current Item; <b>Clear</b> empties the collection. Arriving through both
 * in one firing clears first and then appends, which is how you start a fresh collection from the
 * top of a run. Either way the flow output fires afterwards, and both outputs are republished — so
 * a Clear leaves an empty list downstream rather than a stale one.
 * <p>
 * <b>Being pulled for data adds nothing.</b> A downstream node resolving <b>List</b> without any
 * flow arriving here (see {@code ProcessContext#triggeredVia}) publishes the current contents and
 * stops. Appending on a pull would make the list grow every time something read it, which is the
 * kind of bug that only shows up once a graph gets a second reader.
 * <p>
 * <b>This is the one node here that holds state between firings</b>, and the state is deliberately
 * memory-only: nothing is written to the save file, so a reloaded graph starts empty rather than
 * resuming a half-finished collection whose other half is long gone. Everything else in this
 * library is a pure function of its inputs. The contents are guarded by a lock because concurrent
 * runs may reach the same node ({@code ExecutionPolicy#PARALLEL}), and every published list is an
 * unmodifiable snapshot taken under that lock, so a downstream reader can never see the list
 * change under it mid-run.
 */
@Display.Name("Collect Items")
@Display.Description("Gathers items across firings into one list; Clear empties it.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"collect", "accumulate", "gather", "append", "build", "results", "list", "loop"})
@Node.Type("collections.CollectItemsNode")
public class CollectItemsNode extends BaseNode {

    private final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class);

    private final NodeVariable<List<?>> collected = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    private final FlowPort add = new FlowPort("Add", FlowPort.Direction.IN);
    private final FlowPort clear = new FlowPort("Clear", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    /** The accumulated items. Guarded by itself; see the class documentation. */
    private final List<Object> items = new ArrayList<>();

    @Override
    public void process(ProcessContext ctx) {
        if (ctx.wasTriggeredVia(clear)) {
            discardAll();
        }
        if (ctx.wasTriggeredVia(add)) {
            collect(item.getValue());
        }
        publish();
    }

    /**
     * Appends one item, ignoring null (an unwired Item must not grow the list by a null).
     * Package-private so a test can exercise the accumulation without a live {@code NodeGraph}:
     * the {@code ProcessContext} carrying "which flow port fired" can only be built by the engine,
     * so the routing in {@code process()} above is only observable in a running graph, but what it
     * routes <em>to</em> is testable here.
     */
    void collect(Object value) {
        synchronized (items) {
            if (value != null) {
                items.add(value);
            }
        }
    }

    /** Empties the collection. Package-private for the same reason as {@link #collect}. */
    void discardAll() {
        synchronized (items) {
            items.clear();
        }
    }

    /** Publishes an unmodifiable snapshot of the current contents on both outputs. */
    private void publish() {
        List<Object> snapshot;
        synchronized (items) {
            snapshot = Lists.frozen(items);
        }
        collected.setValue(snapshot);
        count.setValue(snapshot.size());
    }

    @Override
    public void configureInputs() {
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(collected);
        addOutput(count);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(add);
        addFlowInput(clear);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    /**
     * Drops the accumulated items when the node is deleted. Fast and thread-affine, so it belongs
     * here rather than in {@code releaseResources()}, and idempotent — clearing twice is clearing.
     */
    @Override
    protected void onRemoved() {
        discardAll();
    }
}
