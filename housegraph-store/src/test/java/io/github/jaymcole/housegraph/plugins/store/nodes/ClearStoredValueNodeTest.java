package io.github.jaymcole.housegraph.plugins.store.nodes;

import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.store.JsonDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resetting half of the pair with {@code StoredValueNode} — see that class's Javadoc for why
 * clearing an entry is a separate node now rather than a second flow-in port on the same one. The
 * erase routing itself needs the engine, for the same reason documented on
 * {@code StoredValueNodeTest}.
 */
class ClearStoredValueNodeTest {

    @TempDir
    Path directory;

    private Path file;
    private JsonDocumentStore store;
    private ClearStoredValueNode node;

    @BeforeEach
    void setUp() {
        file = directory.resolve("data.json");
        store = new JsonDocumentStore(file);
        node = wired(store, "lastPayer");
    }

    private static ClearStoredValueNode wired(JsonDocumentStore store, String key) {
        ClearStoredValueNode node = new ClearStoredValueNode();
        Nodes.set(node, "Store", store);
        Nodes.set(node, "Key", key);
        return node;
    }

    @Test
    void clearingRemovesTheEntry() {
        StoredValueNode setter = new StoredValueNode();
        Nodes.set(setter, "Store", store);
        Nodes.set(setter, "Key", "lastPayer");
        setter.write(store, "lastPayer", "ada");

        node.erase(store, "lastPayer");
        Nodes.run(node);

        assertEquals("", Nodes.textOf(node, "Value"));
        assertFalse(Nodes.boolOf(node, "Found"), "a clear must not leave a stale value downstream");
    }

    @Test
    void clearingSomethingThatIsntThereWritesNothing() {
        node.erase(store, "other");
        String before = store.get();

        node.erase(store, "lastPayer");

        assertEquals(before, store.get(), "a no-op edit must not rewrite the file or wake the store's listeners");
    }

    @Test
    void oneKeysClearLeavesAnotherKeysAlone() {
        node.erase(store, "lastCurry");

        StoredValueNode setter = new StoredValueNode();
        setter.write(store, "lastPayer", "ada");
        setter.write(store, "lastCurry", "friday");

        node.erase(store, "lastPayer");

        assertEquals("friday", node.read(store, "lastCurry"),
                "several of these nodes share one store, and one must not be able to trample another");
    }

    @Test
    void reachesTheSameEntryAStoredValueNodeWroteUnderTheSameStoreAndKey() {
        StoredValueNode setter = new StoredValueNode();
        Nodes.set(setter, "Store", store);
        Nodes.set(setter, "Key", "lastPayer");
        setter.write(store, "lastPayer", "ada");

        node.erase(store, "lastPayer");
        Nodes.run(setter);

        assertFalse(Nodes.boolOf(setter, "Found"),
                "clearing must reach the entry a separate node instance shares Store and Key with");
    }

    @Test
    void beingPulledForDataDoesNotClear() {
        node.erase(store, "lastPayer");
        StoredValueNode setter = new StoredValueNode();
        Nodes.set(setter, "Store", store);
        Nodes.set(setter, "Key", "lastPayer");
        setter.write(store, "lastPayer", "ada");

        Nodes.run(node);
        Nodes.run(node);

        assertEquals("ada", node.read(store, "lastPayer"),
                "resolving this node's outputs must not erase an entry it wasn't flowed into");
    }

    @Test
    void anUnwiredStoreStopsRatherThanReportingNothingStored() {
        ClearStoredValueNode unwired = new ClearStoredValueNode();
        Nodes.set(unwired, "Key", "lastPayer");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> Nodes.run(unwired));

        assertTrue(thrown.getMessage().contains("data store"), thrown.getMessage());
    }

    @Test
    void aBlankKeyStopsForTheSameReason() {
        ClearStoredValueNode unkeyed = wired(store, "   ");

        assertThrows(IllegalStateException.class, () -> Nodes.run(unkeyed));
    }

    @Test
    void carriesOneUnnamedFlowInAndOneUnnamedFlowOut() {
        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(1, flowInputs.size());
        assertEquals("", flowInputs.get(0).name, "a single flow in renders as a bare anchor");

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertEquals(FlowPort.Direction.OUT, node.getFlowOutputs().get(0).direction);
    }

    @Test
    void publishesItsContentsUnderTheNamesTheGraphSavesThemBy() {
        assertEquals(List.of("Store", "Key"), Nodes.inputNames(node));
        assertEquals(List.of("Value", "Found"), Nodes.outputNames(node));
    }

    @Test
    void doesNotWriteTheLiveStoreHandleIntoTheSaveFile() {
        assertTrue(node.getInputs().stream()
                        .filter(variable -> variable.name.equals("Store"))
                        .noneMatch(variable -> variable.isPersistentValue()),
                "a JsonDocumentStore cannot round-trip through the graph writer");
    }
}
