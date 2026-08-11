package io.github.jaymcole.housegraph.plugins.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates a subdirectory under a root folder, on demand, without ever escaping that root.
 * <p>
 * The subdirectory is given as a caller-typed string that may contain several nested segments
 * (e.g. {@code "photos/2026"}) and may use either {@code /} or {@code \} as a separator — so the
 * same input works unchanged regardless of which OS it was authored on. A {@code ..} segment is
 * rejected outright rather than collapsed or ignored, since letting it through would mean the
 * resolved path could climb out of {@code root}.
 */
public final class RelativeFolder {

    private RelativeFolder() {
    }

    /** The outcome of one {@link #ensure} call. */
    public record Result(boolean created, Path path) {
    }

    /**
     * Resolves {@code subdirectory} under {@code root} and creates it (and any missing parent
     * segments) if it doesn't already exist.
     *
     * @param root        the folder {@code subdirectory} is resolved under
     * @param subdirectory a {@code /} or {@code \} separated path, relative to {@code root}
     * @return whether the folder was newly created, and the resolved path it now exists at
     * @throws IOException if {@code root}/{@code subdirectory} already exists and isn't a directory,
     *                      or the directory couldn't be created
     */
    public static Result ensure(Path root, String subdirectory) throws IOException {
        Path resolved = resolve(root, subdirectory);
        if (Files.isDirectory(resolved)) {
            return new Result(false, resolved);
        }
        if (Files.exists(resolved)) {
            throw new IOException("Path " + resolved + " already exists and is not a directory");
        }
        Files.createDirectories(resolved);
        return new Result(true, resolved);
    }

    /**
     * Computes the resolved path without touching the filesystem, for {@link #ensure} and unit
     * tests.
     */
    static Path resolve(Path root, String subdirectory) {
        if (subdirectory == null || subdirectory.isBlank()) {
            throw new IllegalArgumentException("Folder must not be blank");
        }
        Path normalizedRoot = root.normalize();
        Path candidate = normalizedRoot;
        for (String segment : subdirectory.split("[/\\\\]+")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Folder must not contain '..': " + subdirectory);
            }
            candidate = candidate.resolve(segment);
        }
        Path normalizedCandidate = candidate.normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Folder escapes its root: " + subdirectory);
        }
        return normalizedCandidate;
    }
}
