package io.github.jaymcole.housegraph.plugins.discord;

import net.dv8tion.jda.api.utils.FileUpload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place {@link DiscordAttachment} meets JDA's {@link FileUpload}, so everything either
 * side of it stays free of the other.
 * <p>
 * <b>Who closes these.</b> A {@code FileUpload} holds an open handle on the file it streams, and
 * JDA closes it once the request it was attached to has been sent — success or failure alike. So
 * the uploads made here are deliberately <em>not</em> closed by their caller; closing them before
 * the request goes out would send an empty file.
 */
final class DiscordUploads {

    private DiscordUploads() {
    }

    /**
     * Opens each attachment for upload.
     *
     * @param attachments what to send
     * @return uploads in the same order, for JDA to close after the request
     * @throws DiscordAttachmentException if a file cannot be opened, so nothing is sent claiming
     *                                    an attachment that was never read
     */
    static List<FileUpload> open(List<DiscordAttachment> attachments) {
        List<FileUpload> uploads = new ArrayList<>(attachments.size());
        try {
            for (DiscordAttachment attachment : attachments) {
                uploads.add(switch (attachment) {
                    case DiscordAttachment.OfFile file -> FileUpload.fromData(file.path().toFile(), file.name());
                    case DiscordAttachment.OfBytes bytes -> FileUpload.fromData(bytes.data(), bytes.name());
                });
            }
        } catch (RuntimeException e) {
            // Half-opened uploads hold file handles that nothing downstream will ever close, since
            // the request they were being built for is not going to be sent.
            close(uploads);
            throw new DiscordAttachmentException("Attachments: could not open a file to upload: "
                    + e.getMessage(), e);
        }
        return uploads;
    }

    private static void close(List<FileUpload> uploads) {
        for (FileUpload upload : uploads) {
            try {
                upload.close();
            } catch (IOException | RuntimeException e) {
                // Already failing; a handle that will not close is not the news here.
            }
        }
    }
}
