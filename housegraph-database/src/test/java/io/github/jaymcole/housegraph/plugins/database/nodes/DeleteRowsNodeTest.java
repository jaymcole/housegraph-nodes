package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.plugins.database.Criterion;
import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Databases;
import io.github.jaymcole.housegraph.plugins.database.Match;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The delete itself, the count it publishes, and the two ways it refuses to run. */
class DeleteRowsNodeTest {

    @TempDir
    Path directory;

    private Database database;
    private DeleteRowsNode node;

    @BeforeEach
    void setUp() {
        database = Databases.forFile(directory.resolve("database.db"));
        database.insert("chores", Map.of("name", "dishes"));
        database.insert("chores", Map.of("name", "bins"));

        node = new DeleteRowsNode();
        Nodes.set(node, "Database", database);
        Nodes.set(node, "Table", "chores");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void deletesTheMatchingRowsAndPublishesTheCount() {
        int removed = node.delete(database, "chores", List.of(Criterion.of("name", Match.EQUALS, "dishes")));

        assertEquals(1, removed);
        assertEquals(1, (int) Nodes.<Integer>get(node, "Deleted"));
        assertEquals(1, database.rowCount("chores"));
    }

    @Test
    void publishesZeroWhenNothingMatched() {
        node.delete(database, "chores", List.of(Criterion.of("name", Match.EQUALS, "nothing")));

        assertEquals(0, (int) Nodes.<Integer>get(node, "Deleted"));
        assertEquals(2, database.rowCount("chores"));
    }

    @Test
    void refusesToRunWithNoConditions() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Inputs.requireConditions(List.of(), "Delete Rows", "delete", "chores"));

        assertTrue(failure.getMessage().contains("every row"));
        assertTrue(failure.getMessage().contains("chores"), "the message names the table at risk");
    }

    @Test
    void deletesNothingWhenPulledForData() {
        Nodes.set(node, "Column 1", "name");
        Nodes.set(node, "Value 1", "dishes");

        Nodes.run(node);

        assertEquals(2, database.rowCount("chores"));
        assertNull(Nodes.get(node, "Deleted"));
    }

    @Test
    void hasThePortsItsDocumentationDescribes() {
        assertEquals(List.of("Database", "Table", "Column 1", "Test 1", "Value 1"), Nodes.inputNames(node));
        assertEquals(List.of("Deleted", "None"), Nodes.flowOutputNames(node));
    }
}
