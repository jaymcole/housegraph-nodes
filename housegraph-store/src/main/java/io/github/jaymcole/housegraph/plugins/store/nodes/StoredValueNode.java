package io.github.jaymcole.housegraph.plugins.store.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.store.Documents;
import io.github.jaymcole.housegraph.store.JsonDocumentStore;

/**
 * One named value that outlives the run, the app, and the graph being reloaded. Wire a
 * <b>Data Store</b> node into <b>Store</b>, name the entry in <b>Key</b>, and the node reads that
 * entry whenever something pulls it. <b>Set</b> writes whatever is on the <b>Value</b> input;
 * <b>Clear</b> removes the entry.
 * <p>
 * <b>Being pulled for data changes nothing.</b> A downstream node resolving <b>Value</b> without
 * any flow arriving here reads the store and stops — the same rule <b>Collect Items</b> follows,
 * and for the same reason: state that mutated every time something read it would break on the
 * second reader. Arriving through both flow inputs in one firing clears first and then sets.
 * <p>
 * <b>Nothing stored reads as empty text with Found false</b>, rather than as null, so the value can
 * be wired straight into text inputs without null handling. Branch on <b>Found</b> to tell "stored
 * the empty string" from "never stored anything" — for a rotation, that is the difference between
 * "nobody has paid yet, start at the top" and a lookup that quietly failed.
 * <p>
 * <b>A Set with nothing on Value does nothing</b>, leaving the stored value alone. An unwired
 * input must not be able to overwrite remembered state with a null, and {@link Documents} spells
 * out the other half of that rule. Use <b>Clear</b> to remove an entry deliberately.
 * <p>
 * <b>Values go in and come out as text</b> — see {@link Documents} for what that means for a
 * document someone else wrote, and for why <b>Key</b> is a flat name rather than a path.
 * <p>
 * <b>This node does not own the store.</b> The Data Store node does, and the host hands out one
 * {@code JsonDocumentStore} per file, so several of these nodes pointed at the same store share one
 * object — which is what makes the {@code synchronized} in {@link #process} the right lock. Two
 * nodes writing different keys at the same time (an {@code ExecutionPolicy#PARALLEL} graph, or two
 * Discord commands landing together) would otherwise read-modify-write the same document over each
 * other and one of the two writes would vanish. That guard covers writers in this JVM; something
 * replacing the whole document from outside — the web server node's {@code /api/data} — is
 * last-writer-wins, as whole-document replacement always is.
 */
@Display.Name("Stored Value")
@Display.Description("One named value that survives a restart; Set writes it, Clear removes it.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"store", "stored", "remember", "state", "persist", "save", "value", "key", "variable", "memory"})
@Node.Type("store.StoredValueNode")
public class StoredValueNode extends BaseNode {

    private final NodeVariable<JsonDocumentStore> storeInput =
            new NodeVariable<>("Store", JsonDocumentStore.class).transientValue().required();
    private final NodeVariable<String> keyInput = new NodeVariable<>("Key", String.class, true).required();
    private final NodeVariable<Object> valueInput = new NodeVariable<>("Value", Object.class);

    private final NodeVariable<String> value = new NodeVariable<>("Value", String.class);
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    private final FlowPort set = new FlowPort("Set", FlowPort.Direction.IN);
    private final FlowPort clear = new FlowPort("Clear", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    /**
     * Whichever of clear/set arrived, then publish — all three under the store's monitor, so a
     * Clear-then-Set pair is one atomic edit and the value published is the value written rather
     * than whatever another node put there in between. The helpers below take the same monitor;
     * {@code synchronized} is reentrant, so holding it here widens the atomic unit instead of
     * deadlocking on it.
     */
    @Override
    public void process(ProcessContext ctx) {
        JsonDocumentStore store = requireStore();
        String key = requireKey();
        synchronized (store) {
            if (ctx.wasTriggeredVia(clear)) {
                erase(store, key);
            }
            if (ctx.wasTriggeredVia(set)) {
                write(store, key, valueInput.getValue());
            }
            publish(read(store, key));
        }
    }

    /**
     * Writes one entry, ignoring a null value (see the class documentation). Package-private so a
     * test can exercise it without a live {@code NodeGraph}: the {@code ProcessContext} carrying
     * "which flow port fired" can only be built by the engine, so the routing in {@link #process}
     * is only observable in a running graph — but what it routes <em>to</em> is testable here.
     */
    void write(JsonDocumentStore store, String key, Object incoming) {
        if (incoming == null) {
            return;
        }
        synchronized (store) {
            store.set(Documents.with(store.get(), key, String.valueOf(incoming)));
        }
    }

    /** Removes one entry. Package-private for the same reason as {@link #write}. */
    void erase(JsonDocumentStore store, String key) {
        synchronized (store) {
            String document = store.get();
            String updated = Documents.without(document, key);
            // Nothing to remove: don't write. A pointless write would rewrite the file and wake
            // every change listener on the store for an edit that changed nothing.
            if (!updated.equals(document)) {
                store.set(updated);
            }
        }
    }

    /** The entry's current value, or null when there isn't one. Package-private for tests. */
    String read(JsonDocumentStore store, String key) {
        synchronized (store) {
            return Documents.read(store.get(), key);
        }
    }

    /** Publishes the pair: absent reads as empty text with Found false. See the class documentation. */
    private void publish(String stored) {
        value.setValue(stored == null ? "" : stored);
        found.setValue(stored != null);
    }

    /**
     * The wired store, or a failure. Reporting "nothing stored" for an unwired Store would be the
     * dangerous answer, not the forgiving one: it is indistinguishable from a first run, so a graph
     * that lost its store would silently start over instead of stopping to say so.
     */
    private JsonDocumentStore requireStore() {
        JsonDocumentStore store = storeInput.getValue();
        if (store == null) {
            throw new IllegalStateException("No data store wired into this Stored Value node");
        }
        return store;
    }

    /** The key, or a failure — a blank key names no entry, and for the same reason as above. */
    private String requireKey() {
        String key = keyInput.getValue();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("This Stored Value node has no Key, so it names nothing to store");
        }
        return key.trim();
    }

    @Override
    public void configureInputs() {
        addInput(storeInput);
        addInput(keyInput);
        addInput(valueInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(value);
        addOutput(found);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(set);
        addFlowInput(clear);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
