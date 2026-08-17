package io.github.jaymcole.housegraph.plugins.web;

import java.util.Map;

/**
 * One inbound request to a declared {@code /hooks/...} route, delivered to trigger nodes through
 * {@link io.github.jaymcole.housegraph.resource.ResourceRegistry#publish}. {@code headers} and
 * {@code query} carry one value per name — a request repeating a header or query key has all but
 * the first kept, which is enough for the automation triggers this is for.
 * <p>
 * {@code reply} is non-null only for a route declared with {@code awaitReply} (see
 * {@link WebHookRoute}); a fire-and-forget route's events carry {@code null}, since the HTTP
 * response was already sent (202 Accepted) before the event was published.
 *
 * @param method  the HTTP method, e.g. {@code POST}
 * @param path    the route path under {@code /hooks}, e.g. {@code /doorbell}
 * @param headers request headers, first value per name
 * @param query   query-string parameters, first value per name
 * @param body    the raw request body
 * @param reply   the handle to answer the caller with, or {@code null} for a fire-and-forget route
 */
public record WebHookEvent(String method, String path, Map<String, String> headers,
                            Map<String, String> query, String body, WebHookReply reply) {
}
