package io.github.jaymcole.housegraph.plugins.filesystem.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link CreateFolderNode} headlessly: {@code process()} invoked directly with
 * {@link ProcessContext#uncancelled()} (no {@code NodeGraph}). Which flow-out port fires is
 * graph-cascade behavior — {@code BaseNode.activate()} is a no-op with no {@code ExecutionContext}
 * bound — so that part, and the underlying create-or-reuse mechanics, are covered by
 * {@code RelativeFolderTest} instead; this stays focused on the node's declared ports and its
 * Folder Path output.
 * <p>
 * {@link AppDirectories#get()} caches its resolved root for the life of the JVM, so its home is
 * pointed at a temp directory once, before anything in this class touches it (see
 * {@link AppDirectories}'s {@code housegraph.home} override) — every test below uses its own
 * subdirectory name so they don't collide sharing that one root.
 */
class CreateFolderNodeTest {

    @TempDir
    static Path defaultStorageRoot;

    @BeforeAll
    static void pointTheDefaultStorageLocationAtATempDirectory() {
        System.setProperty("housegraph.home", defaultStorageRoot.toString());
    }

    @Test
    void declaresAFlowInTwoNamedFlowOutsAndAFolderPathOutput() {
        CreateFolderNode node = new CreateFolderNode();

        assertEquals(1, node.getFlowInputs().size());
        assertEquals(List.of("Done", "Created"), node.getFlowOutputs().stream().map(port -> port.name).toList());
        assertEquals(List.of("Folder Path"), node.getOutputs().stream().map(variable -> variable.name).toList());
    }

    @Test
    void runningItCreatesTheFolderUnderTheDefaultStorageLocationAndSetsItsPathOutput() {
        CreateFolderNode node = new CreateFolderNode();
        input(node).setValue("widgets");

        node.process(ProcessContext.uncancelled());

        Path expected = AppDirectories.get().root().resolve("widgets");
        assertTrue(Files.isDirectory(expected));
        assertEquals(expected.toString(), folderPathOutput(node).getValue());
    }

    @Test
    void aNestedSubdirectoryIsCreatedRegardlessOfWhichSeparatorWasTyped() {
        CreateFolderNode node = new CreateFolderNode();
        input(node).setValue("photos\\2026");

        node.process(ProcessContext.uncancelled());

        assertTrue(Files.isDirectory(AppDirectories.get().root().resolve("photos").resolve("2026")));
    }

    @Test
    void aTraversalAttemptFailsInsteadOfEscapingTheDefaultStorageLocation() {
        CreateFolderNode node = new CreateFolderNode();
        input(node).setValue("../escape");

        assertThrows(IllegalArgumentException.class, () -> node.process(ProcessContext.uncancelled()));
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<String> input(CreateFolderNode node) {
        return (NodeVariable<String>) node.getInputs().stream()
                .filter(variable -> variable.name.equals("Folder"))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<String> folderPathOutput(CreateFolderNode node) {
        return (NodeVariable<String>) node.getOutputs().stream()
                .filter(variable -> variable.name.equals("Folder Path"))
                .findFirst()
                .orElseThrow();
    }
}
