/**
 * Local web-hosting integration.
 * <p>
 * {@link io.github.jaymcole.housegraph.plugins.web.LocalWebServer} serves a directory of static
 * files over the JDK's built-in HTTP server and advertises it on the LAN as
 * {@code <name>.local} via jmdns multicast DNS — the long-lived resource behind a
 * web-server node (see {@code nodes.WebServerNode}).
 * <p>
 * {@link io.github.jaymcole.housegraph.plugins.web.NodeProcessServer} is its Node.js sibling: instead of
 * serving files from the JVM it launches an external Node server (an Express app, a Vite dev
 * server, {@code npm start}) as a child process, streams its output to the log, and advertises the
 * same {@code <name>.local} — the resource behind {@code nodes.NodeServerNode}. Both
 * share {@link io.github.jaymcole.housegraph.plugins.web.LanAddress} for picking the mDNS advertise address.
 * <p>
 * {@link io.github.jaymcole.housegraph.plugins.web.LocalWebServer} also mounts a fixed
 * {@code /hooks/*} dispatcher, turning inbound HTTP requests into graph triggers: a
 * {@code Web Hook} or {@code Web Hook Request} node <em>declares</em> the path and method it
 * answers into {@link io.github.jaymcole.housegraph.plugins.web.RouteRegistry} and is driven by
 * the resulting {@link io.github.jaymcole.housegraph.plugins.web.WebHookEvent}s published through
 * {@code ResourceRegistry} — the same declare-then-subscribe shape as the Discord library's slash
 * commands. {@link io.github.jaymcole.housegraph.plugins.web.WebHookReply} is the one-shot handle
 * a held request (declared with {@code awaitReply}) is answered through, from a
 * {@code Web Hook Reply} node.
 */
package io.github.jaymcole.housegraph.plugins.web;
