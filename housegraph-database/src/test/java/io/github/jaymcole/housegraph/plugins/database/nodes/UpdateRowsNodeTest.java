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

/** The update itself, and the pull path that must not change anything. */
class UpdateRowsNodeTest {

    @TempDir
    Path directory;

    private Database database;
    private UpdateRowsNode node;

    @BeforeEach
    void setUp() {
        database = Databases.forFile(directory.resolve("database.db"));
        database.insert("chores", Map.of("name", "dishes", "done", "no"));
        database.insert("chores", Map.of("name", "bins", "done", "no"));

        node = new UpdateRowsNode();
        Nodes.set(node, "Database", database);
        Nodes.set(node, "Table", "chores");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void changesTheMatchingRowsAndPublishesTheCount() {
        int changed = node.update(database, "chores",
                List.of(Criterion.of("name", Match.EQUALS, "dishes")), Map.of("done", "yes"));

        assertEquals(1, changed);
        assertEquals(1, (int) Nodes.<Integer>get(node, "Updated"));
        assertEquals("yes", database.find("chores",
                List.of(Criterion.of("name", Match.EQUALS, "dishes")), null, 0).get(0).get("done"));
    }

    @Test
    void changesNothingWhenPulledForData() {
        Nodes.set(node, "Set", Map.of("done", "yes"));
        Nodes.set(node, "Column 1", "name");
        Nodes.set(node, "Value 1", "dishes");

        Nodes.run(node);

        assertEquals("no", database.find("chores",
                List.of(Criterion.of("name", Match.EQUALS, "dishes")), null, 0).get(0).get("done"));
        assertNull(Nodes.get(node, "Updated"));
    }

    @Test
    void hasThePortsItsDocumentationDescribes() {
        assertEquals(List.of("Database", "Table", "Set", "Column 1", "Test 1", "Value 1"),
                Nodes.inputNames(node));
        assertEquals(List.of("Updated", "None"), Nodes.flowOutputNames(node));
    }
}
