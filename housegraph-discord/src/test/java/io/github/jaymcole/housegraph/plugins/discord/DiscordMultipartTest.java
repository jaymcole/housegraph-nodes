package io.github.jaymcole.housegraph.plugins.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact bytes of a webhook upload. Asserted whole rather than sampled: multipart is a format
 * where a missing blank line or a stray newline is the difference between a working upload and a
 * 400 from Discord, and neither is visible in a body read by eye.
 */
class DiscordMultipartTest {

    @TempDir
    Path tempDir;

    private static final String BOUNDARY = "testboundary";

    @Test
    void aMessageWithOneFileHasAPayloadPartAndAFilePart() {
        DiscordMultipart multipart = DiscordMultipart.of("{\"content\":\"hi\"}",
                List.of(new DiscordAttachment.OfBytes("porch.png", "PNGDATA".getBytes(StandardCharsets.UTF_8))),
                BOUNDARY);

        assertEquals("--testboundary\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n"
                + "{\"content\":\"hi\"}\r\n"
                + "--testboundary\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"porch.png\"\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "\r\n"
                + "PNGDATA\r\n"
                + "--testboundary--\r\n", body(multipart));
    }

    @Test
    void theContentTypeCarriesTheBoundaryDiscordWillSplitOn() {
        DiscordMultipart multipart = DiscordMultipart.of("{}", List.of(), BOUNDARY);

        assertEquals("multipart/form-data; boundary=testboundary", multipart.contentType());
    }

    @Test
    void eachFileIsNumberedAsDiscordExpects() {
        DiscordMultipart multipart = DiscordMultipart.of("{}", List.of(
                new DiscordAttachment.OfBytes("a.png", new byte[]{1}),
                new DiscordAttachment.OfBytes("b.png", new byte[]{2}),
                new DiscordAttachment.OfBytes("c.png", new byte[]{3})), BOUNDARY);

        String body = body(multipart);
        assertTrue(body.contains("name=\"files[0]\"; filename=\"a.png\""), body);
        assertTrue(body.contains("name=\"files[1]\"; filename=\"b.png\""), body);
        assertTrue(body.contains("name=\"files[2]\"; filename=\"c.png\""), body);
    }

    @Test
    void aFileOnDiskIsReadIntoItsPart() throws IOException {
        Path file = tempDir.resolve("nightly.png");
        Files.writeString(file, "ON DISK");

        DiscordMultipart multipart = DiscordMultipart.of("{}",
                List.of(new DiscordAttachment.OfFile("nightly.png", file)), BOUNDARY);

        assertTrue(body(multipart).contains("\r\n\r\nON DISK\r\n"), body(multipart));
    }

    @Test
    void aFileThatVanishedBeforeTheUploadFailsByName() {
        Path missing = tempDir.resolve("gone.png");

        DiscordAttachmentException failure = assertThrows(DiscordAttachmentException.class,
                () -> DiscordMultipart.of("{}", List.of(new DiscordAttachment.OfFile("gone.png", missing)), BOUNDARY));

        assertTrue(failure.getMessage().contains("gone.png"), failure.getMessage());
    }

    @Test
    void aQuoteInAFileNameCannotEndTheHeaderEarly() {
        // The name reaches this from a file on disk or from an upstream node, so it is not
        // this library's to trust. A quote would close the filename value and the rest of the
        // name would be read as more header.
        DiscordMultipart multipart = DiscordMultipart.of("{}",
                List.of(new DiscordAttachment.OfBytes("od\"d\r\nname.png", new byte[]{1})), BOUNDARY);

        String body = body(multipart);
        assertTrue(body.contains("filename=\"oddname.png\""), body);
        assertFalse(body.contains("od\"d"), body);
    }

    @Test
    void bytesSurviveExactlyRatherThanBeingRecoded() {
        // Every byte a PNG can hold has to come out the other side; a body built as text would
        // mangle anything that is not valid UTF-8.
        byte[] awkward = {0, (byte) 0x80, (byte) 0xFF, 0x0D, 0x0A, 0x2D};
        DiscordMultipart multipart = DiscordMultipart.of("{}",
                List.of(new DiscordAttachment.OfBytes("raw.bin", awkward)), BOUNDARY);

        byte[] body = multipart.body();
        int at = indexOf(body, awkward);
        assertTrue(at > 0, "the raw bytes are not in the body unchanged");
    }

    /** ISO-8859-1 so every byte maps to one character and the body can be asserted as text. */
    private static String body(DiscordMultipart multipart) {
        return new String(multipart.body(), StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
