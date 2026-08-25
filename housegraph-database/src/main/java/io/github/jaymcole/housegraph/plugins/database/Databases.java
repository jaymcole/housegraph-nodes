package io.github.jaymcole.housegraph.plugins.database;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vends a single {@link Database} per backing file, process-wide — the same job
 * {@code DocumentStores} does for the JSON store, deliberately written the same way.
 * <p>
 * Two nodes naming the same database must operate on the <em>same</em> object, because that object
 * owns the connection, the transaction boundary and the monitor everything here synchronises on.
 * Two instances would mean two connections writing the same file with no shared lock between them,
 * so an insert that grows the schema could interleave with another node's insert and one of the two
 * would fail on a half-applied table. Keying by the normalised absolute path makes "the same name is
 * the same database" true even when two names sanitise to one folder.
 * <p>
 * Instances are never evicted. A database's identity outlives any single node — deleting a node and
 * recreating it with the same name must reopen the same data, not a second handle on it — and the
 * connection is one file handle, which is not worth reclaiming on a home machine.
 */
public final class Databases {

    private static final Map<Path, Database> CACHE = new ConcurrentHashMap<>();

    private Databases() {
    }

    /**
     * The shared database backing {@code file}, opening it on first use.
     *
     * @param file the SQLite file
     * @return the process-wide {@link Database} for that file
     */
    public static Database forFile(Path file) {
        return CACHE.computeIfAbsent(file.toAbsolutePath().normalize(), Database::new);
    }
}
