package io.github.jaymcole.housegraph.plugins.web;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where Web Hook nodes <em>declare</em> the route they answer, keyed by web-server name, so
 * {@link LocalWebServer}'s single {@code /hooks/*} handler can tell a declared route from a dead
 * one and know whether to hold the response for a reply — the same "declare before connection"
 * shape as the Discord library's {@code SlashCommandRegistry}, adapted for local dispatch rather
 * than syncing to a remote API: declaring here does not talk to HTTP, it just records what the
 * handler should do the next time a matching request arrives.
 */
public final class RouteRegistry {

    private static final RouteRegistry SHARED = new RouteRegistry();

    /** serverName -> ("METHOD /path" -> route). */
    private final Map<String, Map<String, WebHookRoute>> byServer = new ConcurrentHashMap<>();

    public static RouteRegistry shared() {
        return SHARED;
    }

    public void declare(String serverName, WebHookRoute route) {
        byServer.computeIfAbsent(serverName, key -> new ConcurrentHashMap<>())
                .put(key(route.method(), route.path()), route);
    }

    public void withdraw(String serverName, String method, String path) {
        Map<String, WebHookRoute> routes = byServer.get(serverName);
        if (routes != null) {
            routes.remove(key(method, path));
        }
    }

    /** The route declared for {@code method}/{@code path} on {@code serverName}, if any. */
    public Optional<WebHookRoute> find(String serverName, String method, String path) {
        Map<String, WebHookRoute> routes = byServer.get(serverName);
        return routes == null ? Optional.empty() : Optional.ofNullable(routes.get(key(method, path)));
    }

    private static String key(String method, String path) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return method.toUpperCase(Locale.ROOT) + " " + normalizedPath;
    }
}
