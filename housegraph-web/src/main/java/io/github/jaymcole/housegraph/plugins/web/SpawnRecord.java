package io.github.jaymcole.housegraph.plugins.web;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.AppDirectories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A note on disk of the process tree a Node-server node spawned, so that a <em>later JVM</em> can
 * find and reap it.
 *
 * <h2>Why this exists</h2>
 * {@link NodeProcessServer#stop()} kills the tree it started, and that is the normal path. But it
 * can only run if the JVM gets far enough to run it — and a supervised HouseGraph is restarted by
 * being signalled, which puts a whole chain of things between the signal and the teardown: the
 * shutdown hook, {@code Platform.exit()}, the FX thread, {@code NodeGraph.dispose()} walking every
 * node serially, and a fifteen-second budget over the lot. Anything that breaks that chain — a hard
 * kill, a crash, a budget overrun, one slow node ahead of this one — leaves a {@code node} process
 * running with nobody's hand on it. Nothing reparents it to the new JVM, so the port it holds is
 * simply gone, and the replacement dies on {@code EADDRINUSE} forever after.
 *
 * <p>An in-process guard cannot fix that, because the process that would run it is the one that
 * died. The record has to outlive the JVM, which means disk.
 *
 * <h2>Only ever our own process</h2>
 * Reaping by port would be easy and wrong: it would kill whatever happened to want that port. This
 * records the pid <em>and</em> its start instant, and reaps only when both still match, so a pid the
 * OS has since recycled onto something unrelated is left alone. If the identity check cannot be made
 * the process is left alone too — an orphan costs one clear error message, and killing the wrong
 * process costs much more.
 */
final class SpawnRecord {

    private static final Logger log = Log.get(SpawnRecord.class);

    /** How long a reaped tree gets to exit before it is killed outright. */
    private static final Duration REAP_GRACE = Duration.ofSeconds(5);

    private SpawnRecord() {
    }

    /**
     * Records the tree rooted at {@code process} as belonging to {@code name}. Best-effort: a
     * failure here costs the safety net, not the start.
     *
     * @param name    the server's resource name, which keys the record
     * @param process the shell process at the root of the spawned tree
     * @param port    the port it was started on, for the log message a reap prints
     */
    static void write(String name, Process process, int port) {
        Optional<Instant> started = process.info().startInstant();
        if (started.isEmpty()) {
            // Without a start instant there is no safe identity check later, so there is no point
            // writing a record we would refuse to act on.
            log.debug("No start instant for pid {}; not recording it for orphan reaping", process.pid());
            return;
        }
        try {
            Path file = recordFile(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    process.pid() + " " + started.get().toEpochMilli() + " " + port,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not record the Node server process for later cleanup: {}", e.getMessage());
        }
    }

    /** Forgets {@code name}'s record, after its process has been stopped properly. */
    static void clear(String name) {
        try {
            Files.deleteIfExists(recordFile(name));
        } catch (IOException e) {
            log.debug("Could not delete the spawn record for {}", name, e);
        }
    }

    /**
     * Kills anything still running from {@code name}'s previous run, and forgets the record either
     * way. A no-op in the normal case, where the last run stopped cleanly and cleared its record.
     *
     * @param name the server's resource name
     */
    static void reapOrphan(String name) {
        Path file = recordFile(name);
        String contents;
        try {
            if (!Files.exists(file)) {
                return;
            }
            contents = Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.debug("Could not read the spawn record for {}", name, e);
            return;
        }

        // Clear first: a record we could not act on must not be retried forever, and one we are
        // about to act on is about to be obsolete.
        clear(name);

        String[] parts = contents.split("\\s+");
        if (parts.length < 3) {
            log.debug("Ignoring a malformed spawn record for {}: {}", name, contents);
            return;
        }
        long pid;
        long startedAtMillis;
        int port;
        try {
            pid = Long.parseLong(parts[0]);
            startedAtMillis = Long.parseLong(parts[1]);
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            log.debug("Ignoring a malformed spawn record for {}: {}", name, contents);
            return;
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return;
        }
        Optional<Instant> started = handle.get().info().startInstant();
        if (started.isEmpty() || started.get().toEpochMilli() != startedAtMillis) {
            // Same pid, different process — the OS recycled the number. Not ours; leave it.
            log.debug("pid {} is no longer the Node server we started; not touching it", pid);
            return;
        }

        log.warn("A Node server '{}' from a previous run is still alive (pid {}, port {}); "
                + "stopping it before starting a new one", name, pid, port);

        List<ProcessHandle> tree = new ArrayList<>(handle.get().descendants().toList());
        tree.add(handle.get());
        tree.forEach(ProcessHandle::destroy);
        if (!awaitExit(tree)) {
            tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            awaitExit(tree);
        }
        log.info("Reaped the orphaned Node server '{}'", name);
    }

    private static boolean awaitExit(List<ProcessHandle> handles) {
        long deadline = System.nanoTime() + REAP_GRACE.toNanos();
        while (handles.stream().anyMatch(ProcessHandle::isAlive)) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Test seam: where records live. Tests point this at a temp directory so the suite never writes
     * into (or reaps from) the real user profile.
     */
    private static volatile Path directoryOverride;

    /** Test seam — see {@link #directoryOverride}. Pass null to restore the real location. */
    static void useDirectoryForTest(Path directory) {
        directoryOverride = directory;
    }

    private static Path recordFile(String name) {
        Path base = directoryOverride != null
                ? directoryOverride
                : AppDirectories.get().cache().resolve("node-servers");
        return base.resolve(sanitize(name) + ".pid");
    }

    /** Keeps a user-chosen resource name from escaping the directory or upsetting the filesystem. */
    private static String sanitize(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isBlank() ? "unnamed" : cleaned;
    }
}
