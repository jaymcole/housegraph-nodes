package io.github.jaymcole.housegraph.plugins.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-field ordering notation. */
class SortTest {

    @Test
    void blankMeansNoOrdering() {
        assertNull(Sort.parse(null));
        assertNull(Sort.parse(" "));
    }

    @Test
    void aBareColumnIsAscending() {
        Sort sort = Sort.parse(" name ");
        assertEquals("name", sort.column());
        assertFalse(sort.descending());
    }

    @Test
    void readsTheDirectionSuffix() {
        assertTrue(Sort.parse("created_at desc").descending());
        assertTrue(Sort.parse("created_at DESCENDING").descending());
        assertFalse(Sort.parse("created_at asc").descending());
        assertEquals("created_at", Sort.parse("created_at desc").column());
    }

    @Test
    void leavesAColumnThatMerelyEndsInDescAlone() {
        // "desc" only reads as a direction when it is its own word.
        assertEquals("desc", Sort.parse("desc").column());
        assertFalse(Sort.parse("desc").descending());
    }
}
