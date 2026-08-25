package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Databases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two hand-written-SQL nodes: their ports, and the pull that must not run a statement. */
class SqlNodesTest {

    @TempDir
    Path directory;

    private Database database;

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    @BeforeEach
    void setUp() {
        database = Databases.forFile(directory.resolve("database.db"));
        database.insert("chores", row("name", "dishes", "who", "ada"));
        database.insert("chores", row("name", "bins", "who", "grace"));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(SqlQueryNode node) {
        return (List<Map<String, Object>>) Nodes.<List<?>>get(node, "Rows");
    }

    @Test
    void queryReadsWithItsParamsBound() {
        SqlQueryNode node = new SqlQueryNode();
        Nodes.set(node, "Database", database);
        Nodes.set(node, "SQL", "SELECT name FROM chores WHERE who = ?");
        Nodes.set(node, "Params", List.of("ada"));

        Nodes.run(node);

        assertEquals(List.of("dishes"), rowsOf(node).stream().map(r -> r.get("name")).toList());
        assertEquals(1, (int) Nodes.<Integer>get(node, "Count"));
        assertTrue(Nodes.<Boolean>get(node, "Found"));
    }

    @Test
    void queryRunsWithNoParamsWired() {
        SqlQueryNode node = new SqlQueryNode();
        Nodes.set(node, "Database", database);
        Nodes.set(node, "SQL", "SELECT COUNT(*) AS total FROM chores");

        Nodes.run(node);

        assertEquals(2L, rowsOf(node).get(0).get("total"));
    }

    @Test
    void statementChangesNothingWhenPulledForData() {
        SqlStatementNode node = new SqlStatementNode();
        Nodes.set(node, "Database", database);
        Nodes.set(node, "SQL", "DELETE FROM chores");

        Nodes.run(node);

        assertEquals(2, database.rowCount("chores"), "a pull must never run a statement");
        assertNull(Nodes.get(node, "Changed"));
    }

    @Test
    void statementRunsAndPublishesTheCount() {
        SqlStatementNode node = new SqlStatementNode();

        int changed = node.run(database, "UPDATE chores SET who = ? WHERE who = ?", List.of("ada", "grace"));

        assertEquals(1, changed);
        assertEquals(1, (int) Nodes.<Integer>get(node, "Changed"));
    }

    @Test
    void haveThePortsTheirDocumentationDescribes() {
        SqlQueryNode query = new SqlQueryNode();
        SqlStatementNode statement = new SqlStatementNode();

        assertEquals(List.of("Database", "SQL", "Params"), Nodes.inputNames(query));
        assertEquals(List.of("Found", "None"), Nodes.flowOutputNames(query));
        assertEquals(List.of("Database", "SQL", "Params"), Nodes.inputNames(statement));
        assertEquals(List.of(""), Nodes.flowOutputNames(statement));
    }
}
