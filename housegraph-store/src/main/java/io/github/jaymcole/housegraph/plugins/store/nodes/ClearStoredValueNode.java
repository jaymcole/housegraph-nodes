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
 * Removes one entry — the resetting half of the pair with {@link StoredValueNode}. Wire this into
 * the same <b>Store</b> and <b>Key</b> a {@link StoredValueNode} uses; nothing extra is needed to
 * connect the two, since the entry has always lived in the store keyed by Key rather than in
 * either node's own fields. See {@link StoredValueNode}'s class documentation for why this used to
 * be a second flow-in port on that node instead, and why that pairing raced.
 * <p>
 * <b>Being pulled for data does nothing.</b> A downstream node resolving Value or Found without any
 * flow arriving here reads the store and stops, rather than erasing the entry — the same rule
 * {@link StoredValueNode} follows.
 */
@Display.Name("Clear Stored Value")
@Display.Description("Removes one named value from the store.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"store", "stored", "clear", "remove", "erase", "reset", "delete", "key", "variable", "memory"})
@Node.Type("store.ClearStoredValueNode")
public class ClearStoredValueNode extends BaseNode {

    private final NodeVariable<JsonDocumentStore> storeInput =
            new NodeVariable<>("Store", JsonDocumentStore.class).transientValue().required()
                    .describedAs("Must be wired from a Data Store node's output.");
    private final NodeVariable<String> keyInput = new NodeVariable<>("Key", String.class, true).required()
            .describedAs("A shared identifier — any node pointed at the same Store and Key addresses the "
                    + "same entry.");

    private final NodeVariable<String> value = new NodeVariable<>("Value", String.class)
            .describedAs("Reads as empty text, not null, when nothing was stored — pair with Found.");
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class)
            .describedAs("Distinguishes a genuinely empty stored value from no entry at all.");

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        JsonDocumentStore store = requireStore();
        String key = requireKey();
        synchronized (store) {
            if (ctx.wasTriggeredVia(in)) {
                erase(store, key);
            }
            publish(read(store, key));
        }
    }

    /**
     * Removes one entry. Package-private so a test can exercise it without a live
     * {@code NodeGraph}, for the same reason as {@link StoredValueNode#write}.
     */
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

    /** Publishes the pair: absent reads as empty text with Found false. See {@link StoredValueNode}. */
    private void publish(String stored) {
        value.setValue(stored == null ? "" : stored);
        found.setValue(stored != null);
    }

    private JsonDocumentStore requireStore() {
        JsonDocumentStore store = storeInput.getValue();
        if (store == null) {
            throw new IllegalStateException("No data store wired into this Clear Stored Value node");
        }
        return store;
    }

    private String requireKey() {
        String key = keyInput.getValue();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("This Clear Stored Value node has no Key, so it names nothing to clear");
        }
        return key.trim();
    }

    @Override
    public void configureInputs() {
        addInput(storeInput);
        addInput(keyInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(value);
        addOutput(found);
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
