package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Databases;
import io.github.jaymcole.housegraph.plugins.database.DatabaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find Rows reads, so unlike Insert Row it does its whole job on a pull — which is what makes it
 * testable end to end here, engine or no engine.
 */
class FindRowsNodeTest {

    @TempDir
    Path directory;

    private Database database;
    private FindRowsNode node;

    @BeforeEach
    void setUp() {
        database = Databases.forFile(directory.resolve("database.db"));
        database.insert("chores", Map.of("name", "dishes", "who", "ada"));
        database.insert("chores", Map.of("name", "bins", "who", "grace"));
        database.insert("chores", Map.of("name", "post"));

        node = new FindRowsNode();
        Nodes.set(node, "Database", database);
        Nodes.set(node, "Table", "chores");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows() {
        return (List<Map<String, Object>>) Nodes.<List<?>>get(node, "Rows");
    }

    @Test
    void readsEveryRowWhenNoConditionIsFilledIn() {
        Nodes.run(node);

        assertEquals(3, rows().size());
        assertEquals(3, (int) Nodes.<Integer>get(node, "Count"));
        assertTrue(Nodes.<Boolean>get(node, "Found"));
    }

    @Test
    void appliesAFilledInCondition() {
        Nodes.set(node, "Column 1", "who");
        Nodes.set(node, "Test 1", "=");
        Nodes.set(node, "Value 1", "ada");

        Nodes.run(node);

        assertEquals(1, rows().size());
        assertEquals("dishes", rows().get(0).get("name"));
    }

    @Test
    void findsRowsWithNothingInAColumn() {
        Nodes.set(node, "Column 1", "who");
        Nodes.set(node, "Test 1", "is empty");

        Nodes.run(node);

        assertEquals(1, rows().size());
        assertEquals("post", rows().get(0).get("name"));
    }

    @Test
    void reportsNoMatchesWithoutFailing() {
        Nodes.set(node, "Column 1", "who");
        Nodes.set(node, "Value 1", "nobody");

        Nodes.run(node);

        assertEquals(List.of(), rows());
        assertEquals(0, (int) Nodes.<Integer>get(node, "Count"));
        assertFalse(Nodes.<Boolean>get(node, "Found"));
    }

    @Test
    void readsATableNobodyHasWrittenToAsNoRows() {
        Nodes.set(node, "Table", "not-a-table");

        Nodes.run(node);

        assertEquals(List.of(), rows());
        assertFalse(Nodes.<Boolean>get(node, "Found"));
    }

    @Test
    void refusesAConditionWithAColumnButNoValue() {
        Nodes.set(node, "Column 1", "who");
        Nodes.set(node, "Test 1", "=");

        DatabaseException failure = assertThrows(DatabaseException.class, () -> Nodes.run(node));
        assertTrue(failure.getMessage().contains("who"), "the message names the condition that isn't finished");
    }

    @Test
    void ignoresAnUnfilledSpareCondition() {
        Nodes.set(node, "Test 1", ">=");
        Nodes.set(node, "Value 1", "anything");

        Nodes.run(node);

        assertEquals(3, rows().size(), "a condition with no column is a spare, not a filter");
    }

    @Test
    void ordersAndLimits() {
        Nodes.set(node, "Sort", "id desc");
        Nodes.set(node, "Limit", 2);

        Nodes.run(node);

        assertEquals(List.of("post", "bins"), rows().stream().map(row -> row.get("name")).toList());
    }

    @Test
    void treatsZeroLimitAsEveryRow() {
        Nodes.set(node, "Limit", 0);

        Nodes.run(node);

        assertEquals(3, rows().size());
    }

    @Test
    void hasThePortsItsDocumentationDescribes() {
        assertEquals(List.of("Database", "Table", "Column 1", "Test 1", "Value 1", "Sort", "Limit"),
                Nodes.inputNames(node));
        assertEquals(List.of("Found", "None"), Nodes.flowOutputNames(node));
    }

    @Test
    void keepsATypedColumnNameWhenItsPortsAreRebuilt() {
        Nodes.set(node, "Column 1", "who");

        // Loading a saved graph with more conditions rebuilds the ports; a rebuild that recreated
        // the variables would silently wipe what the user had already typed.
        node.loadState(Map.of("conditions", "3"));
        node.reconfigure();

        assertEquals("who", Nodes.<String>getInput(node, "Column 1"));
        assertEquals(List.of("Database", "Table", "Column 1", "Test 1", "Value 1",
                "Column 2", "Test 2", "Value 2", "Column 3", "Test 3", "Value 3", "Sort", "Limit"),
                Nodes.inputNames(node));
    }
}
