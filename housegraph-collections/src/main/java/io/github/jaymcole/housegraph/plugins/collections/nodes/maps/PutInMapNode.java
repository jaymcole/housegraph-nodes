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
 * Stores Key/Value in the named collection and republishes it — the accumulating half of the pair
 * with {@link ClearMapNode}, and the map twin of {@code AddToCollectionNode} in the {@code lists}
 * package. Wire the loop's Body into this node's flow-in and whatever the body computed into Key
 * and Value; whoever needs the finished map reads it back under the same Name with
 * {@link GetNamedMapNode}.
 * <p>
 * <b>This replaces the single {@code CollectEntriesNode}, which had a Put port and a Clear port on
 * one node.</b> See {@code AddToCollectionNode}'s class documentation for why that pairing is a
 * trap: sequencing "reset, then loop" needs Clear to finish before the loop's first iteration can
 * reach Put, and fanning a shared trigger out to both ports races for the node's re-entry gate — a
 * fast Put arrival can silently evict Clear before it ever runs. Naming the map instead of wiring
 * it turns "reset, then loop" into a straight line: trigger &rarr; {@link ClearMapNode} &rarr; For
 * Each &rarr; Body &rarr; this node. No edge doubles back.
 * <p>
 * An existing key is overwritten, matching <b>Map Put</b>'s behaviour. <b>A half-filled pair does
 * nothing</b>: a null or blank Key, or a null Value, leaves the map unchanged (see {@link Maps#put}).
 * <p>
 * <b>Being pulled for data adds nothing.</b> A downstream node resolving Map or Count without any
 * flow arriving here republishes the current contents and stops. <b>Don't read the map that way
 * anyway</b> — a pull earlier in the same run than the flow arrival completes this node, so the
 * flow that follows finds it already done and the put is silently skipped. Read it through
 * {@link GetNamedMapNode}, which has no such hazard; see its documentation for the detail.
 * <p>
 * <b>The collection is deliberately memory-only and outlives this node</b> — see
 * {@link NamedMaps} and {@code AddToCollectionNode}'s equivalent note.
 */
@Display.Name("Put In Map")
@Display.Description("Stores Key/Value in the named collection and republishes it.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"collect", "accumulate", "gather", "put", "map", "build", "results", "loop", "key", "value"})
@Node.Type("collections.PutInMapNode")
public class PutInMapNode extends BaseNode {

    private final NodeVariable<String> name = new NodeVariable<>("Name", String.class, true).required();
    private final NodeVariable<String> key = new NodeVariable<>("Key", String.class, true);
    private final NodeVariable<Object> value = new NodeVariable<>("Value", Object.class);

    private final NodeVariable<Map<?, ?>> collected = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        if (ctx.wasTriggeredVia(in)) {
            collect(name.getValue(), key.getValue(), value.getValue());
        }
        publish(name.getValue());
    }

    /**
     * Stores one pair under {@code collectionName}, ignoring a null collection name and a
     * half-filled pair (see {@link Maps#put}). Package-private so a test can exercise the
     * accumulation without a live {@code NodeGraph}, for the same reason as
     * {@code AddToCollectionNode#collect}.
     *
     * @return whether an entry was stored
     */
    boolean collect(String collectionName, Object entryKey, Object entryValue) {
        if (collectionName == null) {
            return false;
        }
        Map<String, Object> entries = NamedMaps.get(collectionName);
        synchronized (entries) {
            return Maps.put(entries, entryKey, entryValue);
        }
    }

    /** Publishes an unmodifiable snapshot of {@code collectionName}'s current contents. */
    private void publish(String collectionName) {
        Map<String, Object> snapshot = snapshotOf(collectionName);
        collected.setValue(snapshot);
        count.setValue(snapshot.size());
    }

    /**
     * An unmodifiable snapshot of {@code collectionName}'s current contents, or the empty map for
     * a null name. Package-private so {@link ClearMapNode} and tests can read what a name currently
     * holds without going through this node's ports.
     */
    static Map<String, Object> snapshotOf(String collectionName) {
        if (collectionName == null) {
            return Map.of();
        }
        Map<String, Object> entries = NamedMaps.get(collectionName);
        synchronized (entries) {
            return Maps.frozen(entries);
        }
    }

    @Override
    public void configureInputs() {
        addInput(name);
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
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
