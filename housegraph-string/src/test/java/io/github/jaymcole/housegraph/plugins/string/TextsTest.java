package io.github.jaymcole.housegraph.plugins.string;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the rules every node in this library reads its inputs through — the null handling, the
 * positional convention, and the escape sequences an inline field cannot otherwise carry.
 */
class TextsTest {

    @Test
    void absentTextReadsAsEmptyTextRatherThanNull() {
        assertEquals("", Texts.orEmpty(null));
        assertEquals("kitchen", Texts.orEmpty("kitchen"));
    }

    @Test
    void aNonTextValueIsStringifiedAndANullOneRendersEmpty() {
        assertEquals("3", Texts.text(3));
        assertEquals("true", Texts.text(true));
        assertEquals("[a, b]", Texts.text(List.of("a", "b")));
        assertEquals("", Texts.text(null));
    }

    @Test
    void anUnauthoredPositionSelectsTheFallback() {
        assertEquals(0, Texts.resolvePosition(null, 10, 0));
        assertEquals(10, Texts.resolvePosition(null, 10, 10));
    }

    @Test
    void aNegativePositionCountsBackFromTheEnd() {
        assertEquals(6, Texts.resolvePosition(-4, 10, 0));
        assertEquals(0, Texts.resolvePosition(-10, 10, 0));
    }

    @Test
    void aPositionPastEitherEndSaturatesInsteadOfThrowing() {
        assertEquals(10, Texts.resolvePosition(99, 10, 0));
        assertEquals(0, Texts.resolvePosition(-99, 10, 0));
    }

    @Test
    void theEscapesAnInlineFieldCannotCarryAreInterpreted() {
        assertEquals("\n", Texts.unescape("\\n"));
        assertEquals("\t", Texts.unescape("\\t"));
        assertEquals("\r", Texts.unescape("\\r"));
        assertEquals("\\", Texts.unescape("\\\\"));
    }

    @Test
    void anythingElseAfterABackslashSurvivesExactlyAsTypedSoAPathIsUsableAsASeparator() {
        assertEquals("C:\\Users", Texts.unescape("C:\\Users"));
        assertEquals("\\", Texts.unescape("\\"));
        assertEquals(", ", Texts.unescape(", "));
        assertEquals("", Texts.unescape(null));
    }
}
