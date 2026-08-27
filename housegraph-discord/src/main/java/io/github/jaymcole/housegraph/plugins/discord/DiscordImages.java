package io.github.jaymcole.housegraph.plugins.discord;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * The one thing in this library that knows what a JavaFX image is: it encodes one to PNG bytes so
 * {@link DiscordAttachments} can stay a set of rules about paths and lists, testable on a class
 * path with no JavaFX on it.
 * <p>
 * <b>Nothing here may be called from a test in this repository.</b> The convention plugin puts
 * JavaFX on {@code compileOnly}, so it is absent at test runtime, and a method that so much as
 * mentions {@link Image} throws {@link NoClassDefFoundError} the moment it runs — even down a
 * branch where the value was a string. Handing {@link #ENCODER} around is safe (creating a method
 * reference does not load the class it points into); invoking it is what needs the real thing.
 * That is the whole reason the encoder is passed to {@code DiscordAttachments.read} rather than
 * imported by it.
 * <p>
 * <b>PNG rather than JPEG</b> because these images are screenshots and diagrams — a drawing of a
 * graph, a camera frame with boxes on it — where a lossy encoder's ringing around text and thin
 * lines is exactly the artefact you would notice.
 */
public final class DiscordImages {

    /**
     * The encoder every node in this library hands to {@link DiscordAttachments#read}. A constant
     * rather than a method reference at each call site so there is one place to find, and one
     * place to change if a second image type ever needs handling.
     */
    public static final DiscordAttachments.ImageEncoder ENCODER = DiscordImages::encode;

    private DiscordImages() {
    }

    /**
     * One JavaFX image as a PNG attachment.
     *
     * @param value         the value to try; anything that is not an {@link Image} is not ours
     * @param suggestedName what to call it, without an extension
     * @return the attachment, or null if {@code value} is not an image at all
     * @throws DiscordAttachmentException if the image never loaded, or PNG encoding failed
     */
    static DiscordAttachment encode(Object value, String suggestedName) {
        if (!(value instanceof Image image)) {
            return null;
        }
        if (image.isError()) {
            throw new DiscordAttachmentException("Attachments: an image wired in never loaded"
                    + (image.getException() == null ? "." : ": " + image.getException().getMessage()));
        }
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        if (buffered == null) {
            throw new DiscordAttachmentException("Attachments: an image wired in has no pixels to send.");
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(buffered, "png", encoded)) {
                throw new DiscordAttachmentException("Attachments: this machine has no PNG encoder installed.");
            }
        } catch (IOException e) {
            // Writing to memory, so this is not a disk failure; an encoder that refuses the image
            // is what is left.
            throw new DiscordAttachmentException("Attachments: could not encode an image as PNG: "
                    + e.getMessage(), e);
        }
        return new DiscordAttachment.OfBytes(suggestedName + ".png", encoded.toByteArray());
    }
}
