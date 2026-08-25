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
 * The storage rules, against a real SQLite file in a temporary directory — which also makes this the
 * test that proves the bundled native library loads at all. Everything here is reachable without a
 * running graph, which is the point of keeping it out of the nodes.
 */
class DatabaseTest {

    @TempDir
    Path directory;

    private Database database;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("database.db"));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    @Test
    void createsTheTableAndTheFileOnFirstInsert() {
        database.insert("readings", row("temperature", 21));

        assertTrue(Files.exists(directory.resolve("database.db")));
        assertEquals(List.of("readings"), database.tables());
        assertEquals(List.of("id", "created_at", "temperature"), database.columns("readings"));
    }

    @Test
    void returnsTheRowsId() {
        long first = database.insert("readings", row("temperature", 21));
        long second = database.insert("readings", row("temperature", 22));

        assertEquals(first + 1, second);
        assertEquals(List.of(first, second), database.find("readings", List.of(), new Sort("id", false), 0)
                .stream().map(entry -> entry.get("id")).toList());
    }

    @Test
    void stampsCreatedAtUnlessTheRowSuppliesOne() {
        long before = System.currentTimeMillis();
        database.insert("readings", row("temperature", 21));
        database.insert("readings", row("temperature", 22, "created_at", 1L));

        List<Map<String, Object>> rows = database.find("readings", List.of(), new Sort("id", false), 0);
        assertTrue((Long) rows.get(0).get("created_at") >= before);
        assertEquals(1L, rows.get(1).get("created_at"));
    }

    @Test
    void addsAColumnForAKeyItHasNotSeen() {
        database.insert("readings", row("temperature", 21));
        database.insert("readings", row("temperature", 22, "humidity", 55));

        assertEquals(List.of("id", "created_at", "temperature", "humidity"), database.columns("readings"));
    }

    @Test
    void leavesRowsWrittenBeforeAColumnExistedWithoutThatEntry() {
        database.insert("readings", row("temperature", 21));
        database.insert("readings", row("temperature", 22, "humidity", 55));

        List<Map<String, Object>> rows = database.find("readings", List.of(), new Sort("id", false), 0);
        assertFalse(rows.get(0).containsKey("humidity"), "a missing value is an absent entry, not a null");
        assertEquals(55L, rows.get(1).get("humidity"));
    }

    @Test
    void keepsValueTypes() {
        database.insert("things", row("text", "hello", "whole", 21, "fractional", 0.5, "flag", true));

        Map<String, Object> stored = database.find("things", List.of(), null, 0).get(0);
        assertEquals("hello", stored.get("text"));
        assertEquals(21L, stored.get("whole"), "integral numbers read back as Long");
        assertEquals(0.5, stored.get("fractional"));
        assertEquals(1L, stored.get("flag"), "SQLite has no boolean; true stores as 1");
    }

    @Test
    void ordersNumbersNumericallyRatherThanAlphabetically() {
        database.insert("things", row("size", 9));
        database.insert("things", row("size", 10));

        List<Map<String, Object>> rows = database.find("things", List.of(), new Sort("size", false), 0);
        assertEquals(List.of(9L, 10L), rows.stream().map(entry -> entry.get("size")).toList());
    }

    @Test
    void skipsBlankKeysAndNullValues() {
        Map<String, Object> row = row("temperature", 21, "", "ignored");
        row.put("humidity", null);

        database.insert("readings", row);

        assertEquals(List.of("id", "created_at", "temperature"), database.columns("readings"));
    }

    @Test
    void readsAnEmptyListFromATableNobodyHasWrittenTo() {
        assertEquals(List.of(), database.find("nothing-here", List.of(), null, 0));
        assertEquals(0, database.rowCount("nothing-here"));
        assertFalse(database.hasTable("nothing-here"));
    }

    @Test
    void matchesOnEveryCriterion() {
        database.insert("chores", row("name", "dishes", "who", "ada"));
        database.insert("chores", row("name", "bins", "who", "ada"));
        database.insert("chores", row("name", "dishes", "who", "grace"));

        List<Map<String, Object>> rows = database.find("chores", List.of(
                Criterion.of("name", Match.EQUALS, "dishes"),
                Criterion.of("who", Match.EQUALS, "ada")), null, 0);

        assertEquals(1, rows.size());
        assertEquals("ada", rows.get(0).get("who"));
    }

    @Test
    void notEqualsStillMatchesRowsWrittenBeforeTheColumnExisted() {
        database.insert("chores", row("name", "dishes"));
        database.insert("chores", row("name", "bins", "who", "ada"));

        List<Map<String, Object>> rows = database.find("chores",
                List.of(Criterion.of("who", Match.NOT_EQUALS, "ada")), null, 0);

        assertEquals(1, rows.size());
        assertEquals("dishes", rows.get(0).get("name"), "the row with no who at all is still 'not ada'");
    }

    @Test
    void comparesTextWithLikeAndTakesWildcardsLiterally() {
        database.insert("notes", row("body", "50% off"));
        database.insert("notes", row("body", "50 pence"));

        assertEquals(1, database.find("notes", List.of(Criterion.of("body", Match.CONTAINS, "50%")), null, 0).size());
        assertEquals(2, database.find("notes", List.of(Criterion.of("body", Match.STARTS_WITH, "50")), null, 0).size());
        assertEquals(1, database.find("notes", List.of(Criterion.of("body", Match.ENDS_WITH, "pence")), null, 0).size());
    }

    @Test
    void treatsAMissingValueAndAnEmptyStringAsEmpty() {
        database.insert("chores", row("name", "dishes", "who", ""));
        database.insert("chores", row("name", "bins"));
        database.insert("chores", row("name", "post", "who", "ada"));

        assertEquals(2, database.find("chores", List.of(Criterion.of("who", Match.IS_EMPTY, null)), null, 0).size());
        assertEquals(1, database.find("chores", List.of(Criterion.of("who", Match.IS_NOT_EMPTY, null)), null, 0).size());
    }

    @Test
    void limitsAndOrders() {
        database.insert("readings", row("temperature", 21));
        database.insert("readings", row("temperature", 22));
        database.insert("readings", row("temperature", 23));

        List<Map<String, Object>> rows = database.find("readings", List.of(), new Sort("id", true), 2);

        assertEquals(List.of(23L, 22L), rows.stream().map(entry -> entry.get("temperature")).toList());
    }

    @Test
    void refusesAConditionThatNamesAColumnButHasNoValue() {
        DatabaseException failure = assertThrows(DatabaseException.class,
                () -> Criterion.of("who", Match.EQUALS, null));
        assertTrue(failure.getMessage().contains("who"));
    }

    @Test
    void survivesAColumnNameThatIsNotAnIdentifier() {
        database.insert("readings", row("Room \"Temperature\"", 21));

        assertEquals(21L, database.find("readings", List.of(
                Criterion.of("Room \"Temperature\"", Match.EQUALS, 21)), null, 0).get(0).get("Room \"Temperature\""));
    }

    @Test
    void handsBackUnmodifiableRows() {
        database.insert("readings", row("temperature", 21));
        Map<String, Object> stored = database.find("readings", List.of(), null, 0).get(0);

        assertThrows(UnsupportedOperationException.class, () -> stored.put("temperature", 99));
    }

    @Test
    void reopensTheSameDataFromTheSameFile() {
        database.insert("readings", row("temperature", 21));
        database.close();

        Database reopened = new Database(directory.resolve("database.db"));
        try {
            assertEquals(1, reopened.find("readings", List.of(), null, 0).size());
        } finally {
            reopened.close();
        }
    }

    @Test
    void picksUpAColumnAddedFromOutsideThisInstance() {
        database.insert("readings", row("temperature", 21));

        // Stands in for a database browser, or a second HouseGraph: the cache must not make the
        // second instance try to add a column that already exists.
        Database other = new Database(directory.resolve("database.db"));
        try {
            other.insert("readings", row("temperature", 22, "humidity", 55));
        } finally {
            other.close();
        }

        database.insert("readings", row("temperature", 23, "humidity", 60));
        assertEquals(3, database.rowCount("readings"));
    }
}
