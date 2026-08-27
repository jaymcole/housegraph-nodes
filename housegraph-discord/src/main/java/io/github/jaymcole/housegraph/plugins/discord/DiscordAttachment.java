package io.github.jaymcole.housegraph.plugins.discord;

import java.nio.file.Path;

/**
 * One file on its way to Discord, in whichever of the two forms it arrived: a file already on
 * disk, or bytes held in memory.
 * <p>
 * <b>The two are not collapsed into one</b> on purpose. A picture a node made — a camera frame, a
 * drawing of the graph — exists only as bytes, and writing it to a temporary file just to hand it
 * to an uploader would be work and litter for nothing. A file a graph already has, meanwhile,
 * should be streamed from where it lies rather than read whole into a heap that may be holding
 * several of them at once. Keeping the distinction to the last moment lets each take the shorter
 * path.
 * <p>
 * The {@link #name} is what Discord shows and is the only thing the recipient sees of either
 * form, so it carries the extension that decides whether Discord previews the file or offers it
 * as a download.
 */
public sealed interface DiscordAttachment {

    /** What Discord will call this file. Never null or blank, and never a path — just a name. */
    String name();

    /** A file to upload from where it already is. */
    record OfFile(String name, Path path) implements DiscordAttachment {
    }

    /** Bytes to upload from memory — an encoded image, or whatever a node built. */
    record OfBytes(String name, byte[] data) implements DiscordAttachment {
    }
}
