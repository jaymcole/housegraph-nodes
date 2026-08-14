package io.github.jaymcole.housegraph.plugins.web;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches and supervises an external <b>Node.js</b> server as a child process — the long-lived
 * resource behind a Node-server node, the sibling of {@link LocalWebServer}. Where
 * {@code LocalWebServer} serves static files from the JVM itself, this class hands hosting to a
 * user-supplied Node program (an Express app, a Vite dev server, whatever {@code npm start}
 * runs) and only owns its <em>lifecycle</em>: spawn it in the chosen project directory, stream
 * its console output into HouseGraph's log, and kill it (and its child tree) on stop.
 * <p>
 * It <em>does not</em> bind the HTTP port — the Node process does. HouseGraph still advertises
 * {@code <name>.local} over jmdns multicast DNS pointing at the declared port, so the site is
 * reachable at {@code http://<name>.local:<port>/} from any mDNS-aware device — but the Node app
 * is responsible for actually listening on that port (typically via {@code process.env.PORT},
 * which this class sets). If they disagree, the advertisement points nowhere.
 * <p>
 * Like {@code LocalWebServer}, this class keeps no UI concerns: {@link #start} spawns the process
 * and joins the multicast group (call it off the UI thread — process launch and mDNS setup touch
 * the OS and network), {@link #stop} tears both halves down and is idempotent. Instances are
 * single-use per run but reusable after {@link #stop}.
 *
 * <h2>Both halves of the lifecycle are synchronous, and that is the point</h2>
 * A child process is not a socket: it does not release its port the moment it is asked to stop, and
 * it does not report failure through the call that spawned it. Both halves here therefore
 * <em>wait</em>, because the two things that go wrong are consequences of not waiting:
 * <ul>
 *   <li>{@link #stop} sends a signal and then <b>blocks until the tree is gone and the port is
 *       free</b>, escalating to a kill if it will not go. A well-behaved server handles
 *       {@code SIGTERM} by draining connections first — DesktopBridge, the server this was written
 *       against, takes about three seconds — so a {@code stop()} that returned immediately handed a
 *       still-bound port to whatever started next. That is exactly what
 *       {@code NodeServerNode.process()} does on a Restart flow-in (stop, then start), and what a
 *       supervised restart does across a JVM boundary. The <em>port</em> is the postcondition rather
 *       than the process, because a dead process still leaves its socket up for a moment.</li>
 *   <li>{@link #start} <b>waits for the port to come free before spawning, and for the server to
 *       actually accept a connection afterwards</b>. {@code ProcessBuilder.start()} succeeding only
 *       means the shell launched; a Node app that dies on {@code EADDRINUSE} does so seconds later
 *       and entirely silently. Without the readiness check the node reported "Running at …", stayed
 *       green, and advertised over mDNS while nothing was listening at all.</li>
 * </ul>
 * The mDNS advertisement is registered only once the server is confirmed listening, so an
 * advertisement never outlives the thing it points at.
 */
public final class NodeProcessServer {

    private static final Logger log = Log.get(NodeProcessServer.class);

    /** {@code true} on Windows, where the launcher shell is {@code cmd /c} rather than {@code sh -c}. */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * How long the child tree gets to exit on its own after being signalled, before it is killed.
     * Sized against a graceful HTTP shutdown (drain connections, then exit) plus room to spare, and
     * kept under {@code App}'s 15-second shutdown budget — {@code onRemoved()} runs inside it.
     */
    private static final Duration STOP_GRACE = Duration.ofSeconds(6);

    /** How long a survivor gets after being killed outright. A formality; nothing survives this. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(2);

    /**
     * How long to keep waiting for the port after the process tree is gone.
     * <p>
     * A dead process is not a free port: the kernel takes a moment to tear the listening socket
     * down, measurably tens of milliseconds even locally. Small, but {@code NodeServerNode.process()}
     * starts the replacement on the very next line, so "the tree exited" is the wrong postcondition
     * to hand it — "the port is usable" is the one it needs.
     */
    private static final Duration PORT_RELEASE_AFTER_STOP = Duration.ofSeconds(3);

    /** How long to wait for a previous occupant to release the port before refusing to start. */
    private static final Duration PORT_RELEASE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * How long to wait for a freshly spawned server to accept a connection.
     * <p>
     * Generous because the start command usually installs first ({@code npm install && npm start}),
     * and a cold dependency cache is slow. It is only a backstop: the signal that actually catches
     * the common failure is the child <em>exiting</em>, which {@link #awaitListening} notices at once,
     * so a port clash surfaces in seconds rather than at this deadline.
     */
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(3);

    /** How long a single connect probe gets, and how long to pause between probes. */
    private static final int PROBE_TIMEOUT_MILLIS = 250;
    private static final long PROBE_INTERVAL_MILLIS = 100;

    private final Object lock = new Object();
    private Process process;
    private Thread outputPump;
    private JmDNS jmdns;
    private volatile String url;
    /** The port of the current (or most recent) run, so teardown can name it in a warning. */
    private int lastPort;

    /**
     * Spawns {@code command} as a Node.js server rooted at {@code workingDir} and advertises it as
     * {@code name.local:port} via mDNS. The command runs through the platform shell so PATH-resolved
     * launchers work as typed ({@code npm start}, {@code node server.js}, {@code npx vite}); its
     * combined stdout/stderr is pumped into the log. {@code PORT} is exported into the child's
     * environment so a well-behaved Node app binds the advertised port.
     * <p>
     * <b>Blocks until the server is listening</b> — potentially for as long as the start command
     * takes to install dependencies and boot (see {@link #READY_TIMEOUT}). Call it from a background
     * thread; {@code NodeServerNode} reaches it from the engine's execution thread. All-or-nothing:
     * anything that fails leaves no process running and nothing advertised.
     *
     * @param workingDir the Node project directory to run in (must be an existing directory)
     * @param command    the shell command that starts the server (e.g. {@code npm start}); must be non-blank
     * @param name       the mDNS host/service name; the site is advertised at {@code http://name.local:port/}
     * @param port       the TCP port the Node app is expected to listen on (advertised + exported as {@code PORT})
     * @throws IOException              if the port never came free, the process can't be spawned, it
     *                                  exited or never began listening, or mDNS can't start
     * @throws IllegalArgumentException if {@code workingDir} is not a directory, or {@code command}/{@code name} is blank
     */
    public void start(Path workingDir, String command, String name, int port) throws IOException {
        if (workingDir == null || !Files.isDirectory(workingDir)) {
            throw new IllegalArgumentException("Node project directory does not exist: " + workingDir);
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Start command must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Server name must not be blank");
        }
        Path base = workingDir.toAbsolutePath().normalize();

        synchronized (lock) {
            // A previous run may still be draining its connections and holding the port. Spawning
            // into that loses the race silently: the new server dies on EADDRINUSE seconds later,
            // long after start() has returned success.
            requirePortFree(port);

            spawnLocked(base, command, port);

            // ProcessBuilder.start() only tells us the shell launched. Wait for something to answer
            // on the port — or for the child to exit, which is how a port clash or a crashed start
            // command actually manifests — before calling this a running server.
            awaitListening(port);

            // Advertise <name>.local (A record) and an _http._tcp service on the same name, exactly
            // like LocalWebServer — even though it's the Node process, not us, that binds the port.
            try {
                InetAddress advertiseAddr = LanAddress.siteLocal();
                JmDNS dns = JmDNS.create(advertiseAddr, name);
                ServiceInfo info = ServiceInfo.create("_http._tcp.local.", name, port, "path=/");
                dns.registerService(info);
                this.jmdns = dns;
                this.url = "http://" + name + ".local:" + port + "/";
                log.info("Node server '{}' running `{}` in {}, advertised at {}", name, command, base, url);
            } catch (IOException e) {
                // mDNS failed, but the process is up — kill it so start() is all-or-nothing.
                stopProcessLocked();
                throw e;
            }
        }
    }

    /**
     * Test seam: the process half of {@link #start} — wait for the port, spawn, wait for it to
     * listen — without the mDNS half, which needs multicast and is environment-dependent (the same
     * split {@code LocalWebServer.startHttpForTest} makes).
     *
     * @param workingDir the directory to run in
     * @param command    the shell command that starts the server
     * @param port       the port it is expected to listen on
     * @throws IOException on the same conditions as {@link #start}
     */
    void startProcessForTest(Path workingDir, String command, int port) throws IOException {
        synchronized (lock) {
            requirePortFree(port);
            spawnLocked(workingDir.toAbsolutePath().normalize(), command, port);
            awaitListening(port);
        }
    }

    /**
     * Spawns the child process on the platform shell, redirecting stderr into stdout and pumping the
     * merged stream into the log on a daemon thread. Caller holds {@link #lock}.
     */
    private void spawnLocked(Path base, String command, int port) throws IOException {
        if (process != null) {
            throw new IllegalStateException("Node server already running");
        }
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command))
                .directory(base.toFile())
                .redirectErrorStream(true);
        builder.environment().put("PORT", Integer.toString(port));

        Process started = builder.start();
        Thread pump = new Thread(() -> pumpOutput(started), "node-server-output");
        pump.setDaemon(true);
        pump.start();

        this.process = started;
        this.outputPump = pump;
        this.lastPort = port;
    }

    /** Wraps a user command in the platform shell so PATH-resolved launchers (npm, npx) work as typed. */
    private static List<String> shellCommand(String command) {
        return IS_WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);
    }

    /** Streams the child's merged stdout/stderr into the log until the stream closes (process exit). */
    private void pumpOutput(Process proc) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[node] {}", line);
            }
        } catch (IOException e) {
            log.warn("Node server output stream closed: {}", e.getMessage());
        }
    }

    /**
     * Idempotent teardown of both the mDNS advertisement and the child process (whole tree).
     *
     * <p>Blocks until the process tree has actually exited — see the class notes. Callers on the FX
     * thread should hand this to a background thread; {@code onRemoved()} is the exception, because
     * app shutdown must not race ahead of it.
     */
    public void stop() {
        synchronized (lock) {
            if (jmdns != null) {
                try {
                    jmdns.unregisterAllServices();
                    jmdns.close();
                } catch (IOException e) {
                    log.warn("Error closing mDNS: {}", e.getMessage());
                }
                jmdns = null;
            }
            stopProcessLocked();
            url = null;
        }
    }

    private void stopProcessLocked() {
        if (process != null) {
            // Snapshot the tree before signalling anything: once the shell exits its children are
            // reparented away and descendants() can no longer find them.
            List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
            tree.add(process.toHandle());

            // Signal the descendants first: `npm start` forks node, and destroying only the shell
            // would orphan the server still holding the port.
            tree.forEach(ProcessHandle::destroy);

            // Then wait. destroy() is a request, not an outcome — a server draining its connections
            // keeps the port bound for as long as that takes, and returning early is what handed a
            // bound port to the next start.
            if (!awaitExit(tree, STOP_GRACE)) {
                List<ProcessHandle> survivors = tree.stream().filter(ProcessHandle::isAlive).toList();
                log.warn("Node server did not stop within {}s; killing {} process(es)",
                        STOP_GRACE.toSeconds(), survivors.size());
                survivors.forEach(ProcessHandle::destroyForcibly);
                if (!awaitExit(survivors, KILL_GRACE)) {
                    // Nothing left to try. Say so plainly — the next start will fail on the port and
                    // this line is what explains why.
                    log.error("Node server process(es) survived being killed; port {} may stay bound",
                            lastPort);
                }
            }
            process = null;

            // Finally, wait for the socket itself. See PORT_RELEASE_AFTER_STOP: the caller's next
            // move is usually to bind this port again.
            if (lastPort > 0 && !awaitPortFree(lastPort, PORT_RELEASE_AFTER_STOP)) {
                log.warn("Port {} was still bound {}s after the Node server stopped; something else "
                        + "may be holding it", lastPort, PORT_RELEASE_AFTER_STOP.toSeconds());
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

    /**
     * Waits for {@code port} to have no listener, so the spawn that follows can bind it.
     *
     * @throws IOException if something is still listening at the deadline
     */
    private static void requirePortFree(int port) throws IOException {
        if (!awaitPortFree(port, PORT_RELEASE_TIMEOUT)) {
            throw new IOException("Port " + port + " is still in use after "
                    + PORT_RELEASE_TIMEOUT.toSeconds() + "s; another process is holding it");
        }
    }

    /**
     * Waits for {@code port} to have no listener, up to {@code timeout}.
     *
     * @return true if the port is free, false if something is still listening at the deadline
     */
    private static boolean awaitPortFree(int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean waited = false;
        while (isListening(port)) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            waited = true;
            if (!pause()) {
                return !isListening(port);
            }
        }
        if (waited) {
            log.debug("Port {} came free", port);
        }
        return true;
    }

    /**
     * Waits for the freshly spawned server to accept a connection on {@code port}. Caller holds
     * {@link #lock}, so {@link #process} is stable for the duration.
     *
     * <p>Watching the child for an early exit is what makes this quick in the failing case: a port
     * clash or a broken start command kills it within seconds, and there is no point waiting out
     * {@link #READY_TIMEOUT} for a process that is already gone.
     *
     * @throws IOException if the child exited or never began listening; the tree is torn down first
     */
    private void awaitListening(int port) throws IOException {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (!isListening(port)) {
            if (!process.isAlive()) {
                int code = process.exitValue();
                stopProcessLocked();
                throw new IOException("The start command exited with " + code + " without listening on port "
                        + port + " — check the [node] lines in the log (a port already in use is the usual cause)");
            }
            if (System.nanoTime() >= deadline) {
                stopProcessLocked();
                throw new IOException("Nothing was listening on port " + port + " after "
                        + READY_TIMEOUT.toMinutes() + " minute(s); gave up and stopped the process");
            }
            if (!pause()) {
                stopProcessLocked();
                throw new IOException("Interrupted waiting for port " + port + " to start listening");
            }
        }
    }

    /** Whether anything accepts a loopback connection on {@code port} right now. */
    private static boolean isListening(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
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

    public boolean isRunning() {
        synchronized (lock) {
            return process != null && process.isAlive();
        }
    }

    /** The advertised {@code http://<name>.local:<port>/} URL while running, else {@code null}. */
    public String url() {
        return url;
    }
}
