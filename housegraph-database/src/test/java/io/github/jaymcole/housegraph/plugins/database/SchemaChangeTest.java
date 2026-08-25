package io.github.jaymcole.housegraph.plugins.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The destructive half: renaming and dropping a column, the copy taken first, and the things this
 * refuses to do. These are what the Database node's column editor drives — nothing a graph runs
 * reaches them, which is the point.
 */
class SchemaChangeTest {

    @TempDir
    Path directory;

    private Database database;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("database.db"));
        database.insert("chores", row("name", "dishes", "who", "ada"));
        database.insert("chores", row("name", "bins"));
    }

    /**
     * An ordered row. {@code Map.of} would do for the values, but its iteration order is unspecified
     * and randomised per JVM run, so the columns would be created in a different order each time and
     * anything asserting on that order would pass or fail by luck.
     */
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void renamingAColumnKeepsItsValues() {
        database.renameColumn("chores", "who", "assignee");

        assertTrue(database.columns("chores").contains("assignee"));
        assertFalse(database.columns("chores").contains("who"));
        assertEquals("ada", database.find("chores",
                List.of(Criterion.of("name", Match.EQUALS, "dishes")), null, 0).get(0).get("assignee"));
    }

    @Test
    void droppingAColumnTakesItsValues() {
        database.dropColumn("chores", "who");

        assertFalse(database.columns("chores").contains("who"));
        assertEquals(2, database.rowCount("chores"), "the rows themselves stay");
    }

    @Test
    void countsWhatADropWouldCost() {
        assertEquals(1, database.valuesIn("chores", "who"), "only one row has a value there");
        assertEquals(0, database.valuesIn("chores", "nothing-like-this"));
        assertEquals(0, database.valuesIn("no-such-table", "who"));
    }

    @Test
    void doesNotCountEmptyTextAsAValue() {
        database.insert("chores", row("name", "post", "who", ""));

        assertEquals(1, database.valuesIn("chores", "who"));
    }

    @Test
    void copiesTheDatabaseBeforeChangingIt() {
        Path copy = database.renameColumn("chores", "who", "assignee");

        assertTrue(Files.exists(copy), "the copy is where it says it is");
        Database restored = new Database(copy);
        try {
            assertTrue(restored.columns("chores").contains("who"), "the copy predates the rename");
            assertEquals("ada", restored.find("chores",
                    List.of(Criterion.of("name", Match.EQUALS, "dishes")), null, 0).get(0).get("who"));
        } finally {
            restored.close();
        }
    }

    @Test
    void writesTheCopyThroughSqliteSoRecentWritesAreInIt() {
        // The rows above were written in WAL mode and may still be in the -wal rather than the .db.
        // A file copy would miss them; VACUUM INTO does not.
        Path copy = database.backup();

        Database restored = new Database(copy);
        try {
            assertEquals(2, restored.rowCount("chores"));
        } finally {
            restored.close();
        }
    }

    @Test
    void keepsAHistoryOfWhatWasChanged() {
        database.renameColumn("chores", "who", "assignee");
        database.dropColumn("chores", "assignee");

        List<String> history = database.migrations();
        assertEquals(2, history.size());
        assertTrue(history.get(0).contains("drop assignee"), "newest first");
        assertTrue(history.get(1).contains("rename who to assignee"));
    }

    @Test
    void keepsItsOwnBookkeepingOutOfTheTableList() {
        database.renameColumn("chores", "who", "assignee");

        assertEquals(List.of("chores"), database.tables());
    }

    @Test
    void refusesToTouchTheColumnsItDependsOn() {
        assertThrows(DatabaseException.class, () -> database.dropColumn("chores", "id"));
        assertThrows(DatabaseException.class, () -> database.renameColumn("chores", "id", "key"));
        assertThrows(DatabaseException.class, () -> database.dropColumn("chores", "created_at"));
    }

    @Test
    void refusesAColumnOrTableThatIsNotThere() {
        assertThrows(DatabaseException.class, () -> database.dropColumn("chores", "nothing-like-this"));
        assertThrows(DatabaseException.class, () -> database.dropColumn("no-such-table", "who"));
    }

    @Test
    void refusesToRenameOntoAnExistingColumn() {
        assertThrows(DatabaseException.class, () -> database.renameColumn("chores", "who", "name"));
        assertThrows(DatabaseException.class, () -> database.renameColumn("chores", "who", "  "));
    }

    @Test
    void insertsIntoTheRenamedColumnAfterwards() {
        database.renameColumn("chores", "who", "assignee");

        // The column cache described the old schema; an insert that still believed it would try to
        // add "assignee" a second time.
        database.insert("chores", row("name", "post", "assignee", "grace"));

        assertEquals(3, database.rowCount("chores"));
        assertEquals(List.of("id", "created_at", "name", "assignee"), database.columns("chores"));
    }
}
