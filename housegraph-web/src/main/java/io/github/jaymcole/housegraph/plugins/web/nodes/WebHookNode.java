package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;

/**
 * Fires whenever its declared route on a Web Server node's {@code /hooks/<path>} is requested.
 * The caller gets {@code 202 Accepted} the instant the request is matched — the graph run this
 * starts proceeds independently on background threads, so nothing downstream can hold the caller
 * up. Use {@link WebHookRequestNode} instead when the caller needs the graph's own answer back in
 * the HTTP response.
 */
@Display.Name("Web Hook")
@Node.Type("web.WebHookNode")
public class WebHookNode extends AbstractWebHookNode {

    @Override
    protected boolean awaitsReply() {
        return false;
    }
}
