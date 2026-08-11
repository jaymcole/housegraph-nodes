package io.github.jaymcole.housegraph.plugins.web;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runs a one-shot build command (e.g. {@code npm run build}) to completion in a project
 * directory — the piece behind {@code WebServerNode}'s Rebuild flow-in. Unlike
 * {@link NodeProcessServer}, which supervises a long-lived server process, this blocks until the
 * command exits and manages nothing afterward: a static site only needs its bundle regenerated on
 * disk, not a process kept running, since {@link LocalWebServer} rereads files from disk on every
 * request.
 */
public final class SiteBuilder {

    private static final Logger log = Log.get(SiteBuilder.class);

    /** {@code true} on Windows, where the launcher shell is {@code cmd /c} rather than {@code sh -c}. */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private SiteBuilder() {
    }

    /**
     * Runs {@code command} to completion in {@code workingDir} through the platform shell,
     * streaming its combined stdout/stderr into the log, and blocks until it exits.
     *
     * @param workingDir the project directory to run the build in (must be an existing directory)
     * @param command    the shell command that builds the site (e.g. {@code npm run build}); must be non-blank
     * @throws IOException if the process can't be spawned, waiting for it is interrupted, or it exits non-zero
     */
    public static void run(Path workingDir, String command) throws IOException {
        if (workingDir == null || !Files.isDirectory(workingDir)) {
            throw new IllegalArgumentException("Build directory does not exist: " + workingDir);
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Build command must not be blank");
        }
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command))
                .directory(workingDir.toAbsolutePath().normalize().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[build] {}", line);
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Build command interrupted: " + command, e);
        }
        if (exitCode != 0) {
            throw new IOException("Build command exited with status " + exitCode + ": " + command);
        }
    }

    /** Wraps a user command in the platform shell so PATH-resolved launchers (npm, npx) work as typed. */
    private static List<String> shellCommand(String command) {
        return IS_WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);
    }
}
