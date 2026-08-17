package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.web.WebHookReply;

/**
 * Answers a held HTTP request. Wire a {@link WebHookRequestNode}'s {@code Reply} output into this
 * node's {@code Reply} input and give it a status, content type and body; when triggered, it sends
 * that response to the waiting caller. The counterpart to the Discord library's Reply node — the
 * response goes to the one request that's holding open, so no route is needed here. Control flows
 * through.
 */
@Display.Name("Web Hook Reply")
@Node.Type("web.WebHookReplyNode")
public class WebHookReplyNode extends BaseNode {

    private static final String DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final int DEFAULT_STATUS = 200;

    private final NodeVariable<WebHookReply> reply = new NodeVariable<>("Reply", WebHookReply.class).transientValue().required();
    private final NodeVariable<Integer> status = new NodeVariable<>("Status", Integer.class, true);
    private final NodeVariable<String> contentType = new NodeVariable<>("Content-Type", String.class, true);
    private final NodeVariable<String> body = new NodeVariable<>("Body", String.class, true);
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    public WebHookReplyNode() {
        status.setValue(DEFAULT_STATUS);
        contentType.setValue(DEFAULT_CONTENT_TYPE);
    }

    @Override
    public void process(ProcessContext ctx) {
        WebHookReply handle = reply.getValue();
        if (handle == null) {
            return;
        }
        Integer code = status.getValue();
        String type = contentType.getValue();
        String text = body.getValue();
        handle.reply(code == null ? DEFAULT_STATUS : code,
                type == null ? DEFAULT_CONTENT_TYPE : type,
                text == null ? "" : text);
    }

    @Override
    public void configureInputs() {
        addInput(reply);
        addInput(status);
        addInput(contentType);
        addInput(body);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
