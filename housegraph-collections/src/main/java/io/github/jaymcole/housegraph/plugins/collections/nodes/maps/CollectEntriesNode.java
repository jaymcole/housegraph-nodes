package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gathers key/value pairs one firing at a time into a map that outlives the firing — the map half
 * of <b>Collect Items</b>, and the other half of the host's <b>For Each</b>. The loop hands you one
 * element at a time; wire its Body into <b>Put</b>, the key and value the body computed into
 * <b>Key</b> and <b>Value</b>, and the loop's Completed into whatever should read the finished
 * <b>Map</b>.
 * <p>
 * <b>Put</b> stores the current pair, overwriting any entry already under that key; <b>Clear</b>
 * empties the collection. Arriving through both in one firing clears first and then stores, which
 * is how you start a fresh map from the top of a run. Either way the flow output fires afterwards,
 * and both outputs are republished — so a Clear leaves an empty map downstream rather than a stale
 * one. A pair with no key, or no value, stores nothing (see {@link Maps#put}).
 * <p>
 * <b>Being pulled for data adds nothing.</b> A downstream node resolving <b>Map</b> without any
 * flow arriving here publishes the current contents and stops. Storing on a pull would make the
 * map grow every time something read it, which is the kind of bug that only shows up once a graph
 * gets a second reader.
 * <p>
 * <b>The state is deliberately memory-only</b>, exactly as <b>Collect Items</b>' is: nothing is
 * written to the save file, so a reloaded graph starts empty rather than resuming a half-finished
 * collection whose other half is long gone. The contents are guarded by a lock because concurrent
 * runs may reach the same node, and every published map is an unmodifiable snapshot taken under
 * that lock, so a downstream reader can never see the map change under it mid-run.
 */
@Display.Name("Collect Entries")
@Display.Description("Gathers key/value pairs across firings into one map; Clear empties it.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"collect", "accumulate", "gather", "map", "put", "build", "results", "loop", "key", "value"})
@Node.Type("collections.CollectEntriesNode")
public class CollectEntriesNode extends BaseNode {

    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true);
    private final NodeVariable<Object> value = new NodeVariable<>("Value", Object.class);

    private final NodeVariable<Map<?, ?>> collected = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    private final FlowPort put = new FlowPort("Put", FlowPort.Direction.IN);
    private final FlowPort clear = new FlowPort("Clear", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    /** The accumulated entries. Guarded by itself; see the class documentation. */
    private final Map<String, Object> entries = new LinkedHashMap<>();

    @Override
    public void process(ProcessContext ctx) {
        if (ctx.wasTriggeredVia(clear)) {
            discardAll();
        }
        if (ctx.wasTriggeredVia(put)) {
            collect(key.getValue(), value.getValue());
        }
        publish();
    }

    /**
     * Stores one pair, ignoring a half-filled one. Package-private so a test can exercise the
     * accumulation without a live {@code NodeGraph}: the {@code ProcessContext} carrying "which
     * flow port fired" can only be built by the engine, so the routing in {@code process()} above
     * is only observable in a running graph, but what it routes <em>to</em> is testable here.
     *
     * @return whether an entry was stored
     */
    boolean collect(Object entryKey, Object entryValue) {
        synchronized (entries) {
            return Maps.put(entries, entryKey, entryValue);
        }
    }

    /** Empties the collection. Package-private for the same reason as {@link #collect}. */
    void discardAll() {
        synchronized (entries) {
            entries.clear();
        }
    }

    /** Publishes an unmodifiable snapshot of the current contents on both outputs. */
    private void publish() {
        Map<String, Object> snapshot;
        synchronized (entries) {
            snapshot = Maps.frozen(entries);
        }
        collected.setValue(snapshot);
        count.setValue(snapshot.size());
    }

    @Override
    public void configureInputs() {
        addInput(key);
        addInput(value);
    }

    @Override
    public void configureOutputs() {
        addOutput(collected);
        addOutput(count);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(put);
        addFlowInput(clear);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    /**
     * Drops the accumulated entries when the node is deleted. Fast and thread-affine, so it belongs
     * here rather than in {@code releaseResources()}, and idempotent — clearing twice is clearing.
     */
    @Override
    protected void onRemoved() {
        discardAll();
    }
}
