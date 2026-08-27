package io.github.jaymcole.housegraph.plugins.discord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * A {@code multipart/form-data} body for a Discord webhook that carries files.
 * <p>
 * <b>Why this exists at all.</b> A webhook with no files is one JSON POST, which
 * {@link java.net.http.HttpClient} writes in a line. Add a file and Discord's API stops taking
 * JSON: the message becomes a {@code payload_json} part alongside a {@code files[n]} part per
 * upload. There is no smaller way to say that over plain HTTP, and pulling in an HTTP client that
 * builds multipart for us would mean bundling and relocating one for a single request shape.
 * <p>
 * <b>The bytes are assembled whole rather than streamed.</b> Discord caps an upload at a size
 * that comfortably fits in memory, the request has to know its own length anyway, and a body that
 * exists as one array is a body a test can assert on exactly — which for a format this fiddly is
 * worth more than the allocation.
 */
final class DiscordMultipart {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    private final String boundary;
    private final byte[] body;

    private DiscordMultipart(String boundary, byte[] body) {
        this.boundary = boundary;
        this.body = body;
    }

    /**
     * The body for one webhook post.
     *
     * @param payloadJson the message as Discord's {@code payload_json} part
     * @param attachments the files to upload, in the order their ids were assigned in the payload
     * @return the assembled body
     * @throws DiscordAttachmentException if a file cannot be read
     */
    static DiscordMultipart of(String payloadJson, List<DiscordAttachment> attachments) {
        // A boundary must not occur in any part. Random and long enough that it cannot, rather
        // than scanned for, which would mean holding every file twice to check.
        return of(payloadJson, attachments, "housegraph" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** As {@link #of(String, List)}, with the boundary fixed — so a test can assert on the whole body. */
    static DiscordMultipart of(String payloadJson, List<DiscordAttachment> attachments, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writePart(out, boundary, "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n", payloadJson.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < attachments.size(); i++) {
                DiscordAttachment attachment = attachments.get(i);
                writePart(out, boundary,
                        "Content-Disposition: form-data; name=\"files[" + i + "]\"; filename=\""
                                + escape(attachment.name()) + "\"\r\n"
                                + "Content-Type: application/octet-stream\r\n",
                        bytesOf(attachment));
            }
            out.write(("--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw; a file that cannot be read is raised as itself
            // by bytesOf, so reaching here means something genuinely unexpected.
            throw new DiscordAttachmentException("Could not assemble the webhook upload: " + e.getMessage(), e);
        }
        return new DiscordMultipart(boundary, out.toByteArray());
    }

    /** The {@code Content-Type} header this body must be sent with, boundary and all. */
    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /** The assembled body. */
    byte[] body() {
        return body;
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String headers, byte[] content)
            throws IOException {
        out.write(("--" + boundary).getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
        out.write(content);
        out.write(CRLF);
    }

    private static byte[] bytesOf(DiscordAttachment attachment) {
        return switch (attachment) {
            case DiscordAttachment.OfBytes bytes -> bytes.data();
            case DiscordAttachment.OfFile file -> {
                try {
                    yield Files.readAllBytes(file.path());
                } catch (IOException e) {
                    throw new DiscordAttachmentException("Attachments: could not read " + file.path()
                            + " to upload it: " + e.getMessage(), e);
                }
            }
        };
    }

    /**
     * A file name safe to put inside a quoted header. A quote would end the value early and a line
     * break would end the header, so both are removed rather than encoded — this is a display name
     * Discord shows, not an identifier anything resolves.
     */
    private static String escape(String name) {
        return name.replace("\\", "").replace("\"", "").replace("\r", "").replace("\n", "");
    }
}
