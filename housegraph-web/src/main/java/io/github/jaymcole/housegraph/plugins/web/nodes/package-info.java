/**
 * Nodes exposing the local web-hosting integration to the graph: the web-server resource node
 * ({@code WebServerNode}), which serves a directory of static files as {@code <name>.local} on
 * the LAN, and the webhook trigger nodes built on top of it — {@code WebHookNode}
 * (fire-and-forget), {@code WebHookRequestNode} (holds the HTTP response for a reply) and
 * {@code WebHookReplyNode} (answers one). Backs onto the {@code plugins.web} package and
 * {@code ResourceRegistry}.
 */
package io.github.jaymcole.housegraph.plugins.web.nodes;
