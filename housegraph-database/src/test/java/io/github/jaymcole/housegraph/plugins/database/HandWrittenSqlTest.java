package io.github.jaymcole.housegraph.plugins.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The escape hatch: hand-written SQL, and the parameter binding that is the only way into it. */
class HandWrittenSqlTest {

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
        database = new Database(directory.resolve("database.db"));
        database.insert("chores", row("name", "dishes", "who", "ada"));
        database.insert("chores", row("name", "bins", "who", "ada"));
        database.insert("chores", row("name", "post", "who", "grace"));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void runsAQueryWithBoundValues() {
        List<Map<String, Object>> rows = database.query(
                "SELECT name FROM chores WHERE who = ? ORDER BY name", List.of("ada"));

        assertEquals(List.of("bins", "dishes"), rows.stream().map(r -> r.get("name")).toList());
    }

    @Test
    void returnsComputedColumnsUnderTheirAliases() {
        List<Map<String, Object>> rows = database.query(
                "SELECT who, COUNT(*) AS times FROM chores GROUP BY who ORDER BY times DESC", List.of());

        assertEquals("ada", rows.get(0).get("who"));
        assertEquals(2L, rows.get(0).get("times"));
    }

    @Test
    void treatsAValueAsAValueAndNotAsSql() {
        // The classic injection: if this were concatenated the table would be gone.
        List<Map<String, Object>> rows = database.query(
                "SELECT name FROM chores WHERE who = ?", List.of("ada'; DROP TABLE chores; --"));

        assertEquals(List.of(), rows);
        assertEquals(3, database.rowCount("chores"), "the table is still there");
    }

    @Test
    void bindsANullParameter() {
        database.insert("chores", row("name", "shed"));

        List<Map<String, Object>> rows = database.query(
                "SELECT name FROM chores WHERE who IS ?", Arrays.asList((Object) null));

        assertEquals(List.of("shed"), rows.stream().map(r -> r.get("name")).toList());
    }

    @Test
    void runsAStatementAndCountsWhatItChanged() {
        int changed = database.execute("UPDATE chores SET who = ? WHERE who = ?", List.of("grace", "ada"));

        assertEquals(2, changed);
        assertEquals(3, database.query("SELECT * FROM chores WHERE who = ?", List.of("grace")).size());
    }

    @Test
    void noticesWhenAStatementChangedTheSchemaUnderneathIt() {
        database.execute("ALTER TABLE chores ADD COLUMN \"notes\"", List.of());

        // The insert's column cache described the schema as it was; believing it would mean trying
        // to add "notes" a second time.
        database.insert("chores", row("name", "shed", "notes", "needs a lock"));

        assertEquals("needs a lock", database.query(
                "SELECT notes FROM chores WHERE name = ?", List.of("shed")).get(0).get("notes"));
    }

    @Test
    void refusesEmptySql() {
        assertThrows(DatabaseException.class, () -> database.query("  ", List.of()));
        assertThrows(DatabaseException.class, () -> database.execute(null, List.of()));
    }

    @Test
    void reportsWhatSqliteSaidAboutABrokenStatement() {
        DatabaseException failure = assertThrows(DatabaseException.class,
                () -> database.query("SELECT * FROM nowhere", List.of()));

        assertTrue(failure.getMessage().contains("nowhere"), "the driver's own words reach the user");
    }
}
