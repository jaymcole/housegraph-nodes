package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.plugins.collections.nodes.Nodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** See {@code PutInMapNodeTest} for why every test uses a fresh random name. */
class GetNamedMapNodeTest {

    private static String freshName() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void readsWhatAPutNodeStoredUnderTheSameName() {
        String name = freshName();
        PutInMapNode putter = new PutInMapNode();
        putter.collect(name, "front", "porch");
        putter.collect(name, "back", "gate");

        GetNamedMapNode reader = new GetNamedMapNode();
        Nodes.set(reader, "Name", name);
        Nodes.run(reader);

        assertEquals(Map.of("front", "porch", "back", "gate"), Nodes.map(reader, "Map"));
        assertEquals(List.of("front", "back"), List.copyOf(Nodes.map(reader, "Map").keySet()),
                "the map must keep the order entries were put in");
        assertEquals(2, Nodes.get(reader, "Count"));
    }

    @Test
    void aNameNothingHasPutIntoReadsAsEmpty() {
        GetNamedMapNode reader = new GetNamedMapNode();
        Nodes.set(reader, "Name", freshName());
        Nodes.run(reader);

        assertEquals(Map.of(), Nodes.map(reader, "Map"));
        assertEquals(0, Nodes.get(reader, "Count"));
    }

    @Test
    void anUnnamedReaderReadsAsEmpty() {
        GetNamedMapNode reader = new GetNamedMapNode();
        Nodes.run(reader);

        assertEquals(Map.of(), Nodes.map(reader, "Map"));
        assertEquals(0, Nodes.get(reader, "Count"));
    }

    @Test
    void eachResolveSeesTheLatestContents() {
        String name = freshName();
        PutInMapNode putter = new PutInMapNode();
        GetNamedMapNode reader = new GetNamedMapNode();
        Nodes.set(reader, "Name", name);

        putter.collect(name, "front", "porch");
        Nodes.run(reader);
        assertEquals(1, Nodes.get(reader, "Count"));

        new ClearMapNode().discardAll(name);
        putter.collect(name, "back", "gate");
        Nodes.run(reader);
        assertEquals(Map.of("back", "gate"), Nodes.map(reader, "Map"));
    }

    @Test
    void publishesASnapshotThatLaterPutsCannotReach() {
        String name = freshName();
        PutInMapNode putter = new PutInMapNode();
        putter.collect(name, "front", "porch");

        GetNamedMapNode reader = new GetNamedMapNode();
        Nodes.set(reader, "Name", name);
        Nodes.run(reader);
        Map<String, Object> published = Nodes.map(reader, "Map");

        putter.collect(name, "back", "gate");

        assertEquals(Map.of("front", "porch"), published);
        assertThrows(UnsupportedOperationException.class, () -> published.put("side", "door"));
    }

    @Test
    void hasNoFlowPorts() {
        GetNamedMapNode reader = new GetNamedMapNode();

        assertTrue(reader.getFlowInputs().isEmpty(), "a pure read is pulled, never flowed into");
        assertTrue(reader.getFlowOutputs().isEmpty());
    }

    @Test
    void publishesItsPortsUnderTheNamesTheGraphSavesThemBy() {
        GetNamedMapNode reader = new GetNamedMapNode();

        assertEquals(List.of("Name"), Nodes.inputNames(reader));
        assertEquals(List.of("Map", "Count"), Nodes.outputNames(reader));
    }
}
