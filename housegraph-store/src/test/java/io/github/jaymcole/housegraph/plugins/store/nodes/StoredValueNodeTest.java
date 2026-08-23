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
 * The Set/Clear routing itself needs the engine: only it can build a {@code ProcessContext}
 * carrying which flow port control arrived through, and its constructor is package-private to the
 * API. So what's covered here is everything reachable without one — the writes the routing routes
 * to, the ports it routes between, and the pull path, which is the case a regression would be
 * silent in.
 */
class StoredValueNodeTest {

    @TempDir
    Path directory;

    private Path file;
    private JsonDocumentStore store;
    private StoredValueNode node;

    @BeforeEach
    void setUp() {
        file = directory.resolve("data.json");
        store = new JsonDocumentStore(file);
        node = wired(store, "lastPayer");
    }

    private static StoredValueNode wired(JsonDocumentStore store, String key) {
        StoredValueNode node = new StoredValueNode();
        Nodes.set(node, "Store", store);
        Nodes.set(node, "Key", key);
        return node;
    }

    @Test
    void publishesWhatWasStored() {
        node.write(store, "lastPayer", "ada");

        Nodes.run(node);

        assertEquals("ada", Nodes.textOf(node, "Value"));
        assertTrue(Nodes.boolOf(node, "Found"));
    }

    @Test
    void nothingStoredIsEmptyTextRatherThanNull() {
        Nodes.run(node);

        assertEquals("", Nodes.textOf(node, "Value"), "an absent value must be safe to wire into a text input");
        assertFalse(Nodes.boolOf(node, "Found"),
                "Found is the only thing that separates a first run from a lookup that failed");
    }

    @Test
    void survivesTheAppBeingRestarted() {
        node.write(store, "lastPayer", "ada");

        // A fresh store handle on the same file and a fresh node is what a restart amounts to:
        // the whole reason this library exists rather than Collect Items.
        StoredValueNode reloaded = wired(new JsonDocumentStore(file), "lastPayer");
        Nodes.run(reloaded);

        assertEquals("ada", Nodes.textOf(reloaded, "Value"));
        assertTrue(Nodes.boolOf(reloaded, "Found"));
    }

    @Test
    void beingPulledForDataChangesNothing() {
        node.write(store, "lastPayer", "ada");
        Nodes.set(node, "Value", "grace");

        Nodes.run(node);
        Nodes.run(node);
        Nodes.run(node);

        assertEquals("ada", Nodes.textOf(node, "Value"),
                "a value that rewrote itself every time something read it would break on the second reader");
    }

    @Test
    void aSetWithNothingWiredLeavesTheStoredValueAlone() {
        node.write(store, "lastPayer", "ada");

        node.write(store, "lastPayer", null);

        assertEquals("ada", node.read(store, "lastPayer"),
                "an unwired input must not be able to overwrite remembered state");
    }

    @Test
    void writesTheTextFormOfWhateverArrives() {
        node.write(store, "lastPayer", 7);

        assertEquals("7", node.read(store, "lastPayer"));
    }

    @Test
    void clearingRemovesTheEntry() {
        node.write(store, "lastPayer", "ada");

        node.erase(store, "lastPayer");
        Nodes.run(node);

        assertEquals("", Nodes.textOf(node, "Value"));
        assertFalse(Nodes.boolOf(node, "Found"), "a clear must not leave a stale value downstream");
    }

    @Test
    void clearingSomethingThatIsntThereWritesNothing() {
        node.write(store, "other", "x");
        String before = store.get();

        node.erase(store, "lastPayer");

        assertEquals(before, store.get(), "a no-op edit must not rewrite the file or wake the store's listeners");
    }

    @Test
    void oneKeysWritesLeaveAnotherKeysAlone() {
        node.write(store, "lastPayer", "ada");
        node.write(store, "lastCurry", "friday");

        node.erase(store, "lastPayer");

        assertEquals("friday", node.read(store, "lastCurry"),
                "several of these nodes share one store, and one must not be able to trample another");
    }

    @Test
    void anUnwiredStoreStopsRatherThanReportingNothingStored() {
        StoredValueNode unwired = new StoredValueNode();
        Nodes.set(unwired, "Key", "lastPayer");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> Nodes.run(unwired));

        assertTrue(thrown.getMessage().contains("data store"), thrown.getMessage());
    }

    @Test
    void aBlankKeyStopsForTheSameReason() {
        StoredValueNode unkeyed = wired(store, "   ");

        assertThrows(IllegalStateException.class, () -> Nodes.run(unkeyed));
    }

    @Test
    void carriesTheSetAndClearEntryPointsAndOneUnnamedFlowOut() {
        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(2, flowInputs.size());
        assertEquals("Set", flowInputs.get(0).name);
        assertEquals("Clear", flowInputs.get(1).name);

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name, "a single flow out renders as a bare anchor");
        assertEquals(FlowPort.Direction.OUT, node.getFlowOutputs().get(0).direction);
    }

    @Test
    void publishesItsContentsUnderTheNamesTheGraphSavesThemBy() {
        assertEquals(List.of("Store", "Key", "Value"), Nodes.inputNames(node));
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
