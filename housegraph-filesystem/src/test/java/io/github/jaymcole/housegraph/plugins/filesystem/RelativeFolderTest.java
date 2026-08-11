package io.github.jaymcole.housegraph.plugins.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelativeFolderTest {

    @TempDir
    Path root;

    @Test
    void createsANewSubdirectory() throws IOException {
        RelativeFolder.Result result = RelativeFolder.ensure(root, "widgets");

        assertTrue(result.created());
        assertEquals(root.resolve("widgets"), result.path());
        assertTrue(Files.isDirectory(root.resolve("widgets")));
    }

    @Test
    void reRunningAgainstAnExistingFolderReportsItWasNotCreated() throws IOException {
        RelativeFolder.ensure(root, "widgets");

        RelativeFolder.Result result = RelativeFolder.ensure(root, "widgets");

        assertFalse(result.created());
        assertTrue(Files.isDirectory(root.resolve("widgets")));
    }

    @Test
    void createsNestedSubdirectoriesInOnePass() throws IOException {
        RelativeFolder.Result result = RelativeFolder.ensure(root, "photos/2026/vacation");

        assertTrue(result.created());
        assertEquals(root.resolve("photos").resolve("2026").resolve("vacation"), result.path());
        assertTrue(Files.isDirectory(result.path()));
    }

    @Test
    void backslashSeparatorsResolveTheSameAsForwardSlashes() throws IOException {
        RelativeFolder.Result result = RelativeFolder.ensure(root, "photos\\2026");

        assertEquals(root.resolve("photos").resolve("2026"), result.path());
    }

    @Test
    void leadingTrailingAndRepeatedSeparatorsCollapseCleanly() throws IOException {
        RelativeFolder.Result result = RelativeFolder.ensure(root, "//photos\\2026/");

        assertEquals(root.resolve("photos").resolve("2026"), result.path());
    }

    @Test
    void rejectsATraversalSegment() {
        assertThrows(IllegalArgumentException.class, () -> RelativeFolder.ensure(root, "../escape"));
    }

    @Test
    void rejectsATraversalSegmentBuriedInTheMiddle() {
        assertThrows(IllegalArgumentException.class, () -> RelativeFolder.ensure(root, "a/../../b"));
    }

    @Test
    void refusesWhenThePathAlreadyExistsAsAFile() throws IOException {
        Files.writeString(root.resolve("widgets"), "not a directory");

        assertThrows(IOException.class, () -> RelativeFolder.ensure(root, "widgets"));
    }

    @Test
    void rejectsABlankFolder() {
        assertThrows(IllegalArgumentException.class, () -> RelativeFolder.ensure(root, "   "));
    }
}
