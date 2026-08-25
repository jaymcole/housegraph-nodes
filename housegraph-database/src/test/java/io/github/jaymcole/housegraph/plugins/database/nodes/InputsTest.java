package io.github.jaymcole.housegraph.plugins.database.nodes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks every node runs before touching a database. They matter more than they look: for
 * storage, "nothing wired" and "nothing stored" are the two answers it is most dangerous to confuse.
 */
class InputsTest {

    @Test
    void refusesAMissingDatabaseRatherThanReportingNoRows() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Inputs.requireDatabase(null, "Find Rows"));
        assertTrue(failure.getMessage().contains("Find Rows"));
        assertTrue(failure.getMessage().contains("Database node"), "the message says how to fix it");
    }

    @Test
    void refusesABlankTable() {
        assertThrows(IllegalStateException.class, () -> Inputs.requireTable(null, "Insert Row"));
        assertThrows(IllegalStateException.class, () -> Inputs.requireTable("  ", "Insert Row"));
    }

    @Test
    void trimsTheTableName() {
        assertEquals("readings", Inputs.requireTable("  readings ", "Insert Row"));
    }
}
