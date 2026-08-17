package io.github.jaymcole.housegraph.plugins.web;

/**
 * A one-shot handle for answering an HTTP request a {@code Web Hook Request} node is holding
 * open. Backed by the HTTP exchange itself, so it's valid until that request's declared timeout
 * elapses (see {@link WebHookRoute#timeoutSeconds()}). Flows through the graph as a value from
 * the request node to a {@code Web Hook Reply} node, exactly like the Discord library's
 * {@code DiscordReply}.
 */
@FunctionalInterface
public interface WebHookReply {

    void reply(int status, String contentType, String body);
}
