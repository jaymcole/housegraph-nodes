package io.github.jaymcole.housegraph.plugins.discord;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns whatever a graph wired into an <b>Attachments</b> input into files Discord can be handed.
 * <p>
 * <b>Why the input takes anything at all.</b> A data port's type is a bare {@link Class}, and a
 * list's element type is erased — so a port typed for images could not accept the {@code List} a
 * node like Graph Images emits, and a port typed {@code List} could not accept the single image a
 * Camera Snapshot emits. The one port that accepts both is one typed {@link Object}, which the
 * type system allows to receive anything. The cost is that nothing is checked when the edge is
 * drawn, so it is all checked here, with a message per way of being wrong.
 * <p>
 * <b>What it accepts</b>, in the order it tries them:
 * <ul>
 *   <li>a {@link DiscordAttachment}, already prepared;</li>
 *   <li>a {@link Path} or {@link File} — uploaded from where it lies, under its own name;</li>
 *   <li>a {@link String} — read as a path to a file, since that is what the filesystem and
 *       database nodes hand downstream; blank strings are skipped rather than failing, so an
 *       unwired or empty optional input sends nothing;</li>
 *   <li>a {@code byte[]} — uploaded as-is;</li>
 *   <li>any {@link Collection} or array of the above, flattened, so a list of images is a message
 *       with several pictures on it;</li>
 *   <li>anything else — handed to the {@link ImageEncoder}, which is how a JavaFX image becomes a
 *       PNG without this class ever mentioning JavaFX.</li>
 * </ul>
 * <b>The encoder is a parameter rather than an import for a reason that is easy to undo by
 * accident.</b> The libraries in this repository compile against JavaFX but never get it on the
 * test classpath, and a method merely <em>mentioning</em> {@code javafx.scene.image.Image} throws
 * {@link NoClassDefFoundError} the moment it runs there — even when the value it was given is a
 * string. Passing the encoder in keeps every rule below testable; see {@link DiscordImages} for
 * the half that cannot be.
 */
public final class DiscordAttachments {

    /**
     * Discord's own ceiling on files in one message. Enforced here so ten-plus fails with a
     * sentence naming the limit, rather than as a rejected request after the upload.
     */
    public static final int MAX_PER_MESSAGE = 10;

    /** How deep a list of lists is followed before it is treated as a mistake rather than data. */
    private static final int MAX_DEPTH = 8;

    /**
     * Turns one value this class does not recognise into an attachment — in practice, a JavaFX
     * image into a PNG.
     */
    @FunctionalInterface
    public interface ImageEncoder {

        /**
         * @param value       the unrecognised value
         * @param suggestedName a name to use, without an extension, when the value has none of its own
         * @return the attachment, or null if this encoder does not know what the value is either
         */
        DiscordAttachment encode(Object value, String suggestedName);
    }

    private DiscordAttachments() {
    }

    /**
     * Everything wired into an Attachments input, as files ready to upload.
     *
     * @param value   whatever the port was handed; null or blank means no attachments
     * @param encoder what to try for a value none of the rules below recognise
     * @return the attachments in the order they were given, never null
     * @throws DiscordAttachmentException if a path names no readable file, a value is of a kind
     *                                    nothing here can send, or there are more than
     *                                    {@value #MAX_PER_MESSAGE}
     */
    public static List<DiscordAttachment> read(Object value, ImageEncoder encoder) {
        List<DiscordAttachment> attachments = new ArrayList<>();
        collect(value, encoder, attachments, 0);
        if (attachments.size() > MAX_PER_MESSAGE) {
            throw new DiscordAttachmentException("Discord takes at most " + MAX_PER_MESSAGE
                    + " attachments on one message, and " + attachments.size() + " were wired in."
                    + " Send them over more than one message, or trim the list first.");
        }
        return List.copyOf(attachments);
    }

    private static void collect(Object value, ImageEncoder encoder, List<DiscordAttachment> into, int depth) {
        if (value == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new DiscordAttachmentException("The Attachments input is a list nested more than "
                    + MAX_DEPTH + " deep. Flatten it first.");
        }
        if (value instanceof DiscordAttachment attachment) {
            into.add(attachment);
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                collect(element, encoder, into, depth + 1);
            }
            return;
        }
        if (value instanceof Object[] array) {
            for (Object element : array) {
                collect(element, encoder, into, depth + 1);
            }
            return;
        }
        if (value instanceof byte[] bytes) {
            into.add(new DiscordAttachment.OfBytes("attachment-" + (into.size() + 1), bytes));
            return;
        }
        if (value instanceof Path path) {
            into.add(fromPath(path));
            return;
        }
        if (value instanceof File file) {
            into.add(fromPath(file.toPath()));
            return;
        }
        if (value instanceof String text) {
            // Blank is "nothing wired in", not "a file called nothing" - an optional input left
            // empty, or a text node that produced nothing, should send no attachment rather than
            // fail the message it was going along with.
            if (!text.isBlank()) {
                into.add(fromPath(asPath(text.trim())));
            }
            return;
        }
        DiscordAttachment encoded = encoder == null ? null : encoder.encode(value, "image-" + (into.size() + 1));
        if (encoded == null) {
            throw new DiscordAttachmentException("Attachments cannot send a "
                    + value.getClass().getSimpleName() + ". Wire in an image, a file path, or a list of either.");
        }
        into.add(encoded);
    }

    private static DiscordAttachment fromPath(Path path) {
        if (!Files.isReadable(path) || Files.isDirectory(path)) {
            throw new DiscordAttachmentException("Attachments: there is no readable file at " + path + ".");
        }
        Path name = path.getFileName();
        return new DiscordAttachment.OfFile(name == null ? "attachment" : name.toString(), path);
    }

    private static Path asPath(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new DiscordAttachmentException("Attachments: \"" + text
                    + "\" is not a file name this machine can use.", e);
        }
    }
}
