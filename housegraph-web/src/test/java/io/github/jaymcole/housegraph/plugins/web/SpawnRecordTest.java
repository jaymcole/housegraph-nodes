package io.github.jaymcole.housegraph.plugins.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cross-JVM safety net: a Node server whose owning JVM died without tearing it down must
 * be found and killed by the next run, and — just as importantly — nothing else ever must be.
 *
 * <p>The stand-in orphan is a plain sleeping process, since none of this cares what the child
 * actually does; only its identity matters.
 */
class SpawnRecordTest {

    @TempDir
    Path records;

    private Process orphan;

    @BeforeEach
    void useTempRecords() {
        SpawnRecord.useDirectoryForTest(records);
    }

    @AfterEach
    void cleanUp() {
        SpawnRecord.useDirectoryForTest(null);
        if (orphan != null) {
            orphan.descendants().forEach(ProcessHandle::destroyForcibly);
            orphan.destroyForcibly();
        }
    }

    @Test
    void reapKillsAProcessLeftBehindByAPreviousRun() throws Exception {
        orphan = sleeper();
        SpawnRecord.write("bridge", orphan, 3000);
        assertTrue(orphan.isAlive());

        SpawnRecord.reapOrphan("bridge");

        assertTrue(orphan.waitFor(10, TimeUnit.SECONDS), "the recorded orphan should have been killed");
        assertFalse(orphan.isAlive());
    }

    @Test
    void reapLeavesAProcessAloneWhenThePidHasBeenRecycled() throws Exception {
        orphan = sleeper();
        // Right pid, wrong start instant — what a recycled pid looks like. Whatever now owns that
        // number is somebody else's process and must not be touched.
        Files.writeString(records.resolve("bridge.pid"),
                orphan.pid() + " 1 3000", StandardCharsets.UTF_8);

        SpawnRecord.reapOrphan("bridge");

        assertFalse(orphan.waitFor(1, TimeUnit.SECONDS), "a pid we cannot positively identify must be left running");
        assertTrue(orphan.isAlive());
    }

    @Test
    void aClearedRecordMakesReapANoOp() throws Exception {
        orphan = sleeper();
        SpawnRecord.write("bridge", orphan, 3000);

        // What a clean stop() does — after which the process happens to still be alive only because
        // this test never killed it. Reap must not treat a cleared record as licence to hunt.
        SpawnRecord.clear("bridge");
        SpawnRecord.reapOrphan("bridge");

        assertFalse(orphan.waitFor(1, TimeUnit.SECONDS));
        assertTrue(orphan.isAlive());
    }

    @Test
    void reapForgetsARecordItCouldNotActOn() throws Exception {
        Files.writeString(records.resolve("bridge.pid"), "not a record", StandardCharsets.UTF_8);

        SpawnRecord.reapOrphan("bridge");

        assertFalse(Files.exists(records.resolve("bridge.pid")),
                "a record that cannot be parsed must be dropped, not retried on every start");
    }

    /** A process that stays up until it is killed, standing in for an orphaned Node server. */
    private static Process sleeper() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = windows
                ? List.of("cmd.exe", "/c", "ping -n 600 127.0.0.1 > NUL")
                : List.of("sh", "-c", "sleep 600");
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
}
