package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Databases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The insert itself needs the engine: only it can build a {@code ProcessContext} carrying which flow
 * port control arrived through, and its constructor is package-private to the API. So what's covered
 * here is everything reachable without one — the write the gate gates, the ports it publishes, and
 * the pull path, which is the case a regression would be silent (and destructive) in.
 */
class InsertRowNodeTest {

    @TempDir
    Path directory;

    private Database database;
    private InsertRowNode node;

    @BeforeEach
    void setUp() {
        database = Databases.forFile(directory.resolve("database.db"));
        node = new InsertRowNode();
        Nodes.set(node, "Database", database);
        Nodes.set(node, "Table", "readings");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void publishesTheIdOfTheRowItWrote() {
        node.insert(database, "readings", Map.of("temperature", 21));

        assertEquals(1, database.rowCount("readings"));
        assertEquals(1L, (long) Nodes.<Long>get(node, "Id"));
    }

    @Test
    void reportsTheColumnsItHadToCreate() {
        node.insert(database, "readings", Map.of("temperature", 21));
        // id, created_at and temperature all had to be made for the first row.
        assertEquals(3, (int) Nodes.<Integer>get(node, "Columns Added"));

        node.insert(database, "readings", Map.of("temperature", 22));
        assertEquals(0, (int) Nodes.<Integer>get(node, "Columns Added"));

        node.insert(database, "readings", Map.of("temperature", 23, "humidity", 55));
        assertEquals(1, (int) Nodes.<Integer>get(node, "Columns Added"));
    }

    @Test
    void writesNothingWhenPulledForData() {
        // A downstream node resolving Id must not append a row. Nodes.run() is exactly that pull:
        // no flow arrived, so triggeredVia is empty.
        Nodes.set(node, "Row", Map.of("temperature", 21));
        Nodes.run(node);

        assertEquals(0, database.rowCount("readings"));
        assertNull(Nodes.get(node, "Id"));
    }

    @Test
    void hasThePortsItsDocumentationDescribes() {
        assertEquals(List.of("Database", "Table", "Row"), Nodes.inputNames(node));
        assertEquals(List.of("Id", "Columns Added"),
                node.getOutputs().stream().map(variable -> variable.name).toList());
    }
}
