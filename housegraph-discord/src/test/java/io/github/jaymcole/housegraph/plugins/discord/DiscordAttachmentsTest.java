package io.github.jaymcole.housegraph.plugins.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules an Attachments input plays by. All of them, because the port is typed {@link Object}
 * and so nothing here is checked when the edge is drawn — this file is the only thing standing
 * between a graph and a {@link ClassCastException} inside a node.
 * <p>
 * The image branch is the one case absent below: it needs JavaFX, which the libraries in this
 * repository compile against but never get on the test class path. It is reached through an
 * encoder passed in, so a stub stands in for it here and the real one
 * ({@link DiscordImages}) is exercised by using the node.
 */
class DiscordAttachmentsTest {

    @TempDir
    Path tempDir;

    /** Stands in for the JavaFX encoder: turns anything at all into a one-byte PNG. */
    private static final DiscordAttachments.ImageEncoder STUB =
            (value, name) -> new DiscordAttachment.OfBytes(name + ".png", new byte[]{1});

    /** An encoder that recognises nothing, like the real one meeting a value that is not an image. */
    private static final DiscordAttachments.ImageEncoder RECOGNISES_NOTHING = (value, name) -> null;

    @Test
    void nothingWiredInIsNoAttachments() {
        assertEquals(List.of(), DiscordAttachments.read(null, STUB));
    }

    @Test
    void anEmptyStringIsNoAttachmentsRatherThanAFileCalledNothing() {
        // An optional input left blank, or an upstream text node that produced nothing, should let
        // the message go out with no file - not fail it.
        assertEquals(List.of(), DiscordAttachments.read("   ", STUB));
        assertEquals(List.of(), DiscordAttachments.read(List.of("", "  "), STUB));
    }

    @Test
    void aPathIsUploadedUnderItsOwnName() throws IOException {
        Path file = write("porch.png");

        List<DiscordAttachment> read = DiscordAttachments.read(file, STUB);

        assertEquals(1, read.size());
        DiscordAttachment.OfFile only = assertInstanceOf(DiscordAttachment.OfFile.class, read.get(0));
        assertEquals("porch.png", only.name());
        assertEquals(file, only.path());
    }

    @Test
    void aStringIsReadAsAPathBecauseThatIsWhatTheFileNodesEmit() throws IOException {
        Path file = write("nightly.png");

        List<DiscordAttachment> read = DiscordAttachments.read(file.toString(), STUB);

        assertEquals("nightly.png", read.get(0).name());
    }

    @Test
    void aFileIsTakenTooAndIsSurroundingWhitespaceTolerant() throws IOException {
        Path file = write("kitchen.png");

        assertEquals("kitchen.png", DiscordAttachments.read(file.toFile(), STUB).get(0).name());
        assertEquals("kitchen.png", DiscordAttachments.read("  " + file + "  ", STUB).get(0).name());
    }

    @Test
    void aPathWithNoFileAtItFailsRatherThanSendingAMessageWithoutIt() {
        DiscordAttachmentException failure = assertThrows(DiscordAttachmentException.class,
                () -> DiscordAttachments.read(tempDir.resolve("never-written.png"), STUB));

        assertTrue(failure.getMessage().contains("never-written.png"), failure.getMessage());
    }

    @Test
    void aFolderIsNotAFile() {
        assertThrows(DiscordAttachmentException.class, () -> DiscordAttachments.read(tempDir, STUB));
    }

    @Test
    void bytesGoUpAsTheyAre() {
        byte[] data = {1, 2, 3};

        DiscordAttachment.OfBytes only = assertInstanceOf(DiscordAttachment.OfBytes.class,
                DiscordAttachments.read(data, STUB).get(0));

        assertSame(data, only.data());
        assertEquals("attachment-1", only.name());
    }

    @Test
    void aListIsFlattenedSoAListOfImagesIsOneMessageWithSeveralPictures() throws IOException {
        List<Object> wired = List.of(write("a.png"), write("b.png").toString(), new Object());

        List<DiscordAttachment> read = DiscordAttachments.read(wired, STUB);

        assertEquals(List.of("a.png", "b.png", "image-3.png"), read.stream().map(DiscordAttachment::name).toList());
    }

    @Test
    void anArrayIsAListToo() throws IOException {
        Object[] wired = {write("a.png"), write("b.png")};

        assertEquals(2, DiscordAttachments.read(wired, STUB).size());
    }

    @Test
    void aListOfListsIsFollowed() throws IOException {
        List<Object> wired = List.of(List.of(write("a.png")), Set.of(write("b.png")));

        assertEquals(2, DiscordAttachments.read(wired, STUB).size());
    }

    @Test
    void aListNestedAbsurdlyDeepIsAMistakeRatherThanData() {
        Object nested = "leaf";
        for (int i = 0; i < 12; i++) {
            nested = List.of(nested);
        }
        Object deep = nested;

        assertThrows(DiscordAttachmentException.class, () -> DiscordAttachments.read(deep, STUB));
    }

    @Test
    void anAlreadyPreparedAttachmentPassesStraightThrough() {
        DiscordAttachment prepared = new DiscordAttachment.OfBytes("ready.png", new byte[]{9});

        assertSame(prepared, DiscordAttachments.read(prepared, STUB).get(0));
    }

    @Test
    void moreThanDiscordAcceptsFailsWithTheLimitRatherThanAtDiscord() throws IOException {
        List<Object> wired = new ArrayList<>();
        for (int i = 0; i <= DiscordAttachments.MAX_PER_MESSAGE; i++) {
            wired.add(write("file-" + i + ".png"));
        }

        DiscordAttachmentException failure = assertThrows(DiscordAttachmentException.class,
                () -> DiscordAttachments.read(wired, STUB));

        assertTrue(failure.getMessage().contains(String.valueOf(DiscordAttachments.MAX_PER_MESSAGE)),
                failure.getMessage());
    }

    @Test
    void exactlyTheLimitIsFine() throws IOException {
        List<Object> wired = new ArrayList<>();
        for (int i = 0; i < DiscordAttachments.MAX_PER_MESSAGE; i++) {
            wired.add(write("file-" + i + ".png"));
        }

        assertEquals(DiscordAttachments.MAX_PER_MESSAGE, DiscordAttachments.read(wired, STUB).size());
    }

    @Test
    void somethingNoOneCanSendNamesWhatItWas() {
        // What a graph gets for wiring, say, a number in - the message has to say what to wire
        // instead, since the port accepts anything and so the canvas never objected.
        DiscordAttachmentException failure = assertThrows(DiscordAttachmentException.class,
                () -> DiscordAttachments.read(42, RECOGNISES_NOTHING));

        assertTrue(failure.getMessage().contains("Integer"), failure.getMessage());
        assertTrue(failure.getMessage().contains("file path"), failure.getMessage());
    }

    @Test
    void theEncoderIsOnlyAskedAboutValuesNothingElseRecognised() throws IOException {
        // Load-bearing: the real encoder mentions a JavaFX type, so invoking it on a class path
        // without JavaFX throws NoClassDefFoundError. Every rule above has to come first.
        List<String> asked = new ArrayList<>();
        DiscordAttachments.ImageEncoder recording = (value, name) -> {
            asked.add(String.valueOf(value));
            return new DiscordAttachment.OfBytes(name + ".png", new byte[]{1});
        };

        DiscordAttachments.read(List.of(write("a.png"), write("b.png").toString(), new byte[]{1}, "  "), recording);

        assertEquals(List.of(), asked);
    }

    private Path write(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "not really a png");
        return file;
    }
}
