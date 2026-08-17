package io.github.jaymcole.housegraph.plugins.web;

/**
 * A route a {@code Web Hook} or {@code Web Hook Request} node has declared into
 * {@link RouteRegistry}: which path and method it answers, and whether the HTTP response waits
 * for a graph-produced reply. Declaring does not itself bind an HTTP context —
 * {@link LocalWebServer} mounts one fixed {@code /hooks/*} handler that consults the registry per
 * request, so a route becomes live (or stops being reachable) the moment it's declared or
 * withdrawn, with no reconnect needed.
 *
 * @param path           the route path under {@code /hooks}, e.g. {@code /doorbell}
 * @param method         the HTTP method this route answers, e.g. {@code POST}
 * @param awaitReply     true to hold the HTTP response open for a {@code Web Hook Reply} node;
 *                       false to answer {@code 202 Accepted} immediately and run fire-and-forget
 * @param timeoutSeconds how long to hold the response open before answering {@code 504}; ignored
 *                       when {@code awaitReply} is false
 */
public record WebHookRoute(String path, String method, boolean awaitReply, int timeoutSeconds) {
}
