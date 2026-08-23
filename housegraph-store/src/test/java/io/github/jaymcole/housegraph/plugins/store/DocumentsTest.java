package io.github.jaymcole.housegraph.plugins.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entry handling, exercised as the plain string function it is — no store, no file. */
class DocumentsTest {

    @Test
    void readsBackWhatItWrote() {
        String document = Documents.with("{}", "lastPayer", "ada");

        assertEquals("ada", Documents.read(document, "lastPayer"));
    }

    @Test
    void writingReplacesAnyPreviousEntry() {
        String document = Documents.with(Documents.with("{}", "lastPayer", "ada"), "lastPayer", "grace");

        assertEquals("grace", Documents.read(document, "lastPayer"));
    }

    @Test
    void writingLeavesOtherEntriesAlone() {
        String document = Documents.with(Documents.with("{}", "lastPayer", "ada"), "lastCurry", "friday");

        assertEquals("ada", Documents.read(document, "lastPayer"),
                "a write must not be a whole-document replacement");
        assertEquals("friday", Documents.read(document, "lastCurry"));
    }

    @Test
    void anEntryThatIsntThereReadsAsNull() {
        assertNull(Documents.read("{}", "lastPayer"));
        assertNull(Documents.read(Documents.with("{}", "other", "x"), "lastPayer"));
    }

    @Test
    void anEmptyOrMissingDocumentIsAFirstRunRatherThanAFailure() {
        assertNull(Documents.read(null, "lastPayer"));
        assertNull(Documents.read("", "lastPayer"));
        assertNull(Documents.read("   ", "lastPayer"));
        assertEquals("ada", Documents.read(Documents.with(null, "lastPayer", "ada"), "lastPayer"));
    }

    @Test
    void jsonNullReadsAsNothingStored() {
        assertNull(Documents.read("{\"lastPayer\": null}", "lastPayer"),
                "a graph cannot tell JSON null from an absent entry, and should not have to");
    }

    @Test
    void readsValuesSomeoneElseWroteAsText() {
        assertEquals("3", Documents.read("{\"count\": 3}", "count"));
        assertEquals("true", Documents.read("{\"on\": true}", "on"));
    }

    @Test
    void removesAnEntryAndLeavesTheRest() {
        String document = Documents.with(Documents.with("{}", "lastPayer", "ada"), "lastCurry", "friday");

        String without = Documents.without(document, "lastPayer");

        assertNull(Documents.read(without, "lastPayer"));
        assertEquals("friday", Documents.read(without, "lastCurry"));
    }

    @Test
    void removingSomethingThatIsntThereIsNotAnError() {
        assertNull(Documents.read(Documents.without("{}", "lastPayer"), "lastPayer"));
    }

    @Test
    void aNullValueIsRejectedRatherThanTreatedAsARemoval() {
        // org.json would quietly delete the key; that would make a Set with nothing wired behave
        // like a Clear, which is exactly the state loss this node exists to prevent.
        assertThrows(NullPointerException.class, () -> Documents.with("{}", "lastPayer", null));
    }

    @Test
    void aDocumentThatIsntAnObjectFailsLoudlyRatherThanBeingOverwritten() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Documents.read("[1, 2, 3]", "lastPayer"));

        assertTrue(thrown.getMessage().contains("JSON object"), thrown.getMessage());
        assertThrows(IllegalStateException.class, () -> Documents.with("[1, 2, 3]", "lastPayer", "ada"));
    }

    @Test
    void keepsTheDocumentReadableForWhoeverOpensTheFile() {
        // org.json puts a single-entry object on one line whatever indent you ask for, so the
        // indenting is only observable — and only matters — once there is more than one entry.
        String document = Documents.with(Documents.with("{}", "lastPayer", "ada"), "lastCurry", "friday");

        assertTrue(document.contains("\n"), "the store's file is one a person may well edit by hand");
    }
}
