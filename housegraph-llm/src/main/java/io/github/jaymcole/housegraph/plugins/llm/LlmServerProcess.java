package io.github.jaymcole.housegraph.plugins.llm;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches and supervises a local model server as a child process — {@code ollama serve},
 * {@code llama-server -m …}, {@code lms server start}, {@code vllm serve …} — and hands the rest of
 * this library a server that is actually answering. The long-lived resource behind a Local LLM
 * Server node, and the reason a graph no longer needs a terminal open beside it.
 * <p>
 * The shape is {@code housegraph-web}'s {@code NodeProcessServer}: spawn through the platform shell
 * so PATH-resolved launchers work as typed, pump the child's console into HouseGraph's log, kill the
 * whole tree on stop, and keep a {@link LlmServerRecord} on disk so a JVM that never got to run its
 * teardown can still be cleaned up after. What differs is everything that follows from a model
 * server not being a web server.
 *
 * <h2>Readiness is an API answer, not a bound port</h2>
 * {@code ProcessBuilder.start()} succeeding only means the shell launched, so both classes wait for
 * the thing they started to be usable. A web server is usable the moment it accepts a connection; a
 * model server is not. It binds its port, then reads a model index, and a TCP probe goes green in
 * between — so {@link #start} polls {@link LlmModels#status} instead, and calls the server up only
 * once its API has listed its models. That also makes the wait <em>tell the difference between</em>
 * the server being slow and something else already sitting on the address, which a connect probe
 * cannot.
 *
 * <h2>A server that is already running is adopted, not fought with</h2>
 * This is the one behaviour with no counterpart in the web node, and it is not an optimisation.
 * Ollama is normally installed as a background service — the macOS and Windows apps start one at
 * login, most Linux packages install a systemd unit — so on a great many machines something is
 * <em>already</em> serving {@code localhost:11434}. Spawning into that gets "address already in
 * use" and a node that is permanently red on a machine where local LLMs work perfectly well.
 * <p>
 * So {@link #start} looks before it spawns. If the address already answers, that server is adopted:
 * no process is launched, and the node reports running. What is adopted is not owned —
 * {@link #stop()} leaves it alone and says so, because a node has no business killing a system
 * service it did not start, and doing so on a Restart would take the machine's Ollama down with the
 * graph. The consequence worth knowing is that Restart on an adopted server does nothing;
 * {@link #isAdopted()} is how the node says which of the two it has.
 *
 * <h2>And stopping waits</h2>
 * {@link #stop()} blocks until the tree is gone <em>and</em> the address has stopped answering,
 * because a Restart's next move is a probe — and a probe that catches the old server still draining
 * would adopt the corpse and report a successful restart of a process that is on its way out.
 */
public final class LlmServerProcess {

    private static final Logger log = Log.get(LlmServerProcess.class);

    /** {@code true} on Windows, where the launcher shell is {@code cmd /c} rather than {@code sh -c}. */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * How long the child tree gets to exit on its own after being signalled, before it is killed.
     * Sized for a server that finishes the request it is on and unloads a model from memory first,
     * and kept under HouseGraph's 15-second shutdown budget — teardown runs inside it.
     */
    private static final Duration STOP_GRACE = Duration.ofSeconds(8);

    /** How long a survivor gets after being killed outright. A formality; nothing survives this. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(2);

    /**
     * How long to keep probing the address after the tree is gone, waiting for it to fall silent.
     * See the class notes: the caller's next move on a Restart is a probe that must not adopt the
     * server it just stopped.
     */
    private static final Duration ADDRESS_QUIET_AFTER_STOP = Duration.ofSeconds(5);

    /** How long to pause between readiness probes while a server starts. */
    private static final long PROBE_INTERVAL_MILLIS = 250;

    private final Object lock = new Object();

    private Process process;
    private Thread outputPump;
    /** True when the address was already answering at {@link #start}, so nothing here spawned it. */
    private boolean adopted;
    /** What the last start or stop found, for the node's status line. Never null. */
    private volatile LlmServerStatus status = LlmServerStatus.down("Stopped");
    /** The spec of the current (or most recent) run, so teardown can name what it is stopping. */
    private volatile LlmServerSpec spec;

    /**
     * Makes sure a model server is answering at {@code spec}'s address, launching one if nothing is.
     * <p>
     * <b>Blocks until the server's API answers</b> — up to {@code spec.startupTimeoutSeconds()},
     * which for a command that loads weights before it listens can be minutes. Call it from a
     * background thread; the node reaches it from the engine's execution thread. All-or-nothing: a
     * start that fails leaves no process of ours running.
     * <p>
     * <b>Idempotent, and deliberately so.</b> Called against a server this class already started
     * and that is still answering, it does nothing and returns — where the web node's equivalent
     * tears down and relaunches unconditionally. Relaunching a model server costs the seconds or
     * minutes of reading a multi-gigabyte model back off disk, so "Start" on something that is
     * already up should not silently pay that; {@link #restart} is there for when relaunching is
     * the point.
     *
     * @param spec what to run, where, and the address it should answer on
     * @throws IOException  if the process cannot be spawned, exits without answering, or never
     *                      answers within the startup timeout
     * @throws LlmException if the address is unusable
     */
    public void start(LlmServerSpec spec) throws IOException {
        synchronized (lock) {
            this.spec = spec;
            String address = spec.address();

            if (process != null && process.isAlive()) {
                LlmServerStatus ours = probe(spec);
                if (ours.running()) {
                    // Ours, alive, still answering. Note this before the adoption check below, which
                    // would otherwise see its own server answering and mark it as somebody else's -
                    // and then refuse to stop it.
                    status = ours;
                    adopted = false;
                    log.debug("The LLM server at {} is already running; leaving it alone", address);
                    return;
                }
                // Alive but not answering: the shell is up and the server under it is not. Clear the
                // husk out rather than spawning a second one beside it.
                log.warn("The LLM server process at {} is alive but not answering; restarting it", address);
                stopProcessLocked();
            }

            // Before anything else: a previous JVM that died without running its teardown left its
            // server up, and it is still answering this address. Adopting it below would look like a
            // clean start and quietly serve every prompt from the old process, so it goes first.
            boolean reaped = LlmServerRecord.reapOrphan(spec.name());
            if (reaped) {
                awaitQuiet(spec, ADDRESS_QUIET_AFTER_STOP);
            }

            // Look before spawning. On most machines Ollama is already running as a service, and
            // launching a second one just collides with it - see the class notes.
            LlmServerStatus existing = probe(spec);
            if (existing.running()) {
                adopted = true;
                status = existing;
                log.info("An LLM server is already answering at {}; adopting it rather than starting one. "
                        + "It will be left running when this node stops.", address);
                return;
            }

            spawnLocked(spec);
            LlmServerRecord.write(spec.name(), process, address);
            try {
                awaitReady(spec);
            } catch (IOException e) {
                // All-or-nothing: a server that never answered is not left running in the background
                // with nobody's hand on it.
                stopProcessLocked();
                throw e;
            }
            adopted = false;
            log.info("LLM server '{}' running `{}` at {} - {}",
                    spec.name(), spec.command(), address, status.detail());
        }
    }

    /**
     * Stops whatever this class started and starts it again — the one call that <em>does</em> pay
     * the cost of reloading the model, for when that is the point: a new command, a different
     * model directory, a server that has wedged.
     * <p>
     * <b>On an {@link #isAdopted() adopted} server this does nothing</b>, since the stop half has
     * nothing it is allowed to stop and the start half then finds the same server still answering.
     * That is worth saying out loud rather than discovering: a graph that restarts its Ollama to
     * pick up a change will not do so on a machine where Ollama runs as a service.
     *
     * @param spec what to run, where, and the address it should answer on
     * @throws IOException  if the replacement cannot be spawned or never answers
     * @throws LlmException if the address is unusable
     */
    public void restart(LlmServerSpec spec) throws IOException {
        synchronized (lock) {
            stop();
            start(spec);
        }
    }

    /**
     * Idempotent teardown: signals the tree this class spawned, waits for it to go, and waits for
     * the address to fall silent. A server that was {@link #isAdopted() adopted} is left running —
     * see the class notes — and this returns at once.
     */
    public void stop() {
        synchronized (lock) {
            if (adopted) {
                LlmServerSpec current = spec;
                log.info("Not stopping the LLM server at {}: it was already running when this node "
                        + "started, so it is not ours to stop.",
                        current == null ? "its address" : current.address());
                adopted = false;
                status = LlmServerStatus.down("Released - the server was not ours to stop");
                return;
            }
            if (process == null) {
                status = LlmServerStatus.down("Stopped");
                return;
            }
            LlmServerSpec current = spec;
            log.info("Stopping the LLM server at {}", current == null ? "its address" : current.address());
            stopProcessLocked();
            if (current != null && !awaitQuiet(current, ADDRESS_QUIET_AFTER_STOP)) {
                log.warn("Something was still answering at {} {}s after the LLM server stopped; a "
                        + "restart will adopt it rather than start a new one",
                        current.address(), ADDRESS_QUIET_AFTER_STOP.toSeconds());
            }
            status = LlmServerStatus.down("Stopped");
        }
    }

    /** Whether a server is up as far as the last start could tell — see {@link #isAdopted()}. */
    public boolean isRunning() {
        synchronized (lock) {
            return adopted ? status.running() : process != null && process.isAlive();
        }
    }

    /**
     * Whether the running server was found rather than started, in which case {@link #stop()} will
     * leave it alone and a restart will not restart anything.
     */
    public boolean isAdopted() {
        synchronized (lock) {
            return adopted;
        }
    }

    /** The models the server listed when it came up; empty when it is down or had none. */
    public List<String> models() {
        return status.models();
    }

    /** One sentence about the last start or stop, for the node's status line. */
    public String detail() {
        return status.detail();
    }

    /** The address of the current (or most recent) run, or null if this has never been started. */
    public String address() {
        LlmServerSpec current = spec;
        return current == null ? null : current.address();
    }

    /**
     * Spawns the child on the platform shell, redirecting stderr into stdout and pumping the merged
     * stream into the log on a daemon thread. Caller holds {@link #lock}.
     */
    private void spawnLocked(LlmServerSpec spec) throws IOException {
        if (process != null) {
            throw new IllegalStateException("LLM server already running");
        }
        // Logged before the spawn, not after a successful start: it is the marker that says we got
        // this far, so an absence of [llm] output below means the command produced none rather than
        // that we never reached it.
        log.info("Spawning `{}`{} for {}", spec.command(),
                spec.directory() == null ? "" : " in " + spec.directory(), spec.address());

        ProcessBuilder builder = new ProcessBuilder(shellCommand(spec.command()))
                .redirectErrorStream(true);
        if (spec.directory() != null) {
            builder.directory(spec.directory().toFile());
        }
        exportAddress(builder, spec);

        Process started = builder.start();
        Thread pump = new Thread(() -> pumpOutput(started), "llm-server-output");
        pump.setDaemon(true);
        pump.start();

        this.process = started;
        this.outputPump = pump;
    }

    /**
     * Puts the address the node was configured with into the child's environment, so the Server
     * input actually decides where the server binds instead of merely where it is looked for.
     * <p>
     * {@code OLLAMA_HOST} is the one that does real work: Ollama reads it as the {@code host:port}
     * to listen on, so a node pointed at {@code http://localhost:11500} gets a server there rather
     * than one on 11434 that nothing then finds. {@code PORT} is set alongside it as the
     * conventional name a wrapper script is most likely to read — the same one
     * {@code housegraph-web}'s Node server node exports — and is ignored by every server this
     * library talks to directly.
     */
    private static void exportAddress(ProcessBuilder builder, LlmServerSpec spec) {
        URI address = URI.create(spec.address());
        String host = address.getHost();
        int port = address.getPort();
        if (host == null) {
            return;
        }
        builder.environment().put("OLLAMA_HOST", port > 0 ? host + ":" + port : host);
        if (port > 0) {
            builder.environment().put("PORT", Integer.toString(port));
        }
    }

    /** Wraps a user command in the platform shell so PATH-resolved launchers work as typed. */
    private static List<String> shellCommand(String command) {
        return IS_WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);
    }

    /** Streams the child's merged stdout/stderr into the log until the stream closes (process exit). */
    private static void pumpOutput(Process proc) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[llm] {}", line);
            }
        } catch (IOException e) {
            log.warn("LLM server output stream closed: {}", e.getMessage());
        }
    }

    /**
     * Waits for the freshly spawned server's API to answer. Caller holds {@link #lock}, so
     * {@link #process} is stable for the duration.
     * <p>
     * Watching the child for an early exit is what makes this quick in the failing case: a bad
     * command or an address already in use kills it within seconds, and there is no point waiting
     * out the whole startup timeout for a process that is already gone.
     *
     * @throws IOException if the child exited, or never answered before the deadline
     */
    private void awaitReady(LlmServerSpec spec) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(spec.startupTimeoutSeconds()).toNanos();
        while (true) {
            LlmServerStatus found = probe(spec);
            if (found.running()) {
                status = found;
                return;
            }
            if (!process.isAlive()) {
                throw new IOException("`" + spec.command() + "` exited with " + process.exitValue()
                        + " without answering at " + spec.address()
                        + " - check the [llm] lines in the log (a command that isn't installed, or an"
                        + " address already in use, are the usual causes)");
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("Nothing answered at " + spec.address() + " within "
                        + spec.startupTimeoutSeconds() + "s of starting `" + spec.command()
                        + "`. " + found.detail() + " Raise Startup Timeout (s) if the command loads a"
                        + " model before it starts listening.");
            }
            if (!pause()) {
                throw new IOException("Interrupted while waiting for " + spec.address() + " to answer");
            }
        }
    }

    /**
     * Waits for the address to stop answering, up to {@code timeout}.
     *
     * @return true if it fell silent, false if something was still answering at the deadline
     */
    private boolean awaitQuiet(LlmServerSpec spec, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (probe(spec).running()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            if (!pause()) {
                return !probe(spec).running();
            }
        }
        return true;
    }

    /**
     * One look at the address. Never throws for a server that is down — that is the answer being
     * looked for — but a cancelled run still propagates, so a graph being torn down does not sit
     * out a three-minute startup wait.
     */
    private static LlmServerStatus probe(LlmServerSpec spec) {
        return LlmModels.status(spec.api(), spec.server(), spec.apiKey(),
                LlmModels.DEFAULT_STATUS_TIMEOUT_SECONDS);
    }

    private void stopProcessLocked() {
        if (process != null) {
            // Snapshot the tree before signalling anything: once the shell exits its children are
            // reparented away and descendants() can no longer find them.
            List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
            tree.add(process.toHandle());

            // Signal the descendants first: `ollama serve` under `sh -c` is a child of the shell,
            // and destroying only the shell would orphan the server still holding the address.
            tree.forEach(ProcessHandle::destroy);

            if (!awaitExit(tree, STOP_GRACE)) {
                List<ProcessHandle> survivors = tree.stream().filter(ProcessHandle::isAlive).toList();
                log.warn("The LLM server did not stop within {}s; killing {} process(es)",
                        STOP_GRACE.toSeconds(), survivors.size());
                survivors.forEach(ProcessHandle::destroyForcibly);
                if (!awaitExit(survivors, KILL_GRACE)) {
                    // Nothing left to try. Say so plainly - the next start will find the address
                    // taken, and this line is what explains why.
                    log.error("LLM server process(es) survived being killed; {} may stay taken",
                            spec == null ? "its address" : spec.address());
                }
            }
            process = null;

            // The tree is gone, so the record has nothing left to point at. Dropping it here is what
            // keeps the next start's reap a no-op in the normal case.
            if (spec != null) {
                LlmServerRecord.clear(spec.name());
            }
        }
        if (outputPump != null) {
            outputPump.interrupt();
            outputPump = null;
        }
    }

    /**
     * Waits for every handle to exit, up to {@code timeout}.
     *
     * @return true if they all exited, false if any is still alive at the deadline
     */
    private static boolean awaitExit(List<ProcessHandle> handles, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (handles.stream().noneMatch(ProcessHandle::isAlive)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            if (!pause()) {
                // Interrupted: report honestly rather than claiming a clean exit we never saw.
                return handles.stream().noneMatch(ProcessHandle::isAlive);
            }
        }
    }

    /** Sleeps one probe interval. Returns false if interrupted (the flag is restored). */
    private static boolean pause() {
        try {
            Thread.sleep(PROBE_INTERVAL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
