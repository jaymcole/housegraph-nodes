package io.github.jaymcole.housegraph.plugins.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Quoting, which is the whole defence between a typed-in name and the statement it lands in. */
class SqlTest {

    @Test
    void quotesAnIdentifierAndDoublesEmbeddedQuotes() {
        assertEquals("\"name\"", Sql.identifier("name"));
        assertEquals("\"Room Temperature\"", Sql.identifier("Room Temperature"));
        assertEquals("\"say \"\"hi\"\"\"", Sql.identifier("say \"hi\""));
    }

    @Test
    void refusesNamesSqlCannotQuote() {
        assertThrows(DatabaseException.class, () -> Sql.identifier(null));
        assertThrows(DatabaseException.class, () -> Sql.identifier("  "));
        assertThrows(DatabaseException.class, () -> Sql.identifier("na\0me"));
    }

    @Test
    void escapesLikeWildcards() {
        assertEquals("50\\% off", Sql.escapeLike("50% off"));
        assertEquals("a\\_b", Sql.escapeLike("a_b"));
        assertEquals("back\\\\slash", Sql.escapeLike("back\\slash"));
    }
}
