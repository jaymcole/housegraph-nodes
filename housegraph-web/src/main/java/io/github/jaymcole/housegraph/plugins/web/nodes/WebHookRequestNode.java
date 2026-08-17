package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.web.WebHookEvent;
import io.github.jaymcole.housegraph.plugins.web.WebHookReply;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.Map;

/**
 * Fires whenever its declared route on a Web Server node's {@code /hooks/<path>} is requested,
 * and holds the HTTP response open until a {@link WebHookReplyNode} downstream answers it —
 * through this node's {@code Reply} output — or {@link #timeoutSeconds} elapses, at which point
 * the caller gets {@code 504}. Use the plain {@link WebHookNode} instead for a webhook that
 * doesn't need to answer the caller.
 */
@Display.Name("Web Hook Request")
@Node.Type("web.WebHookRequestNode")
public class WebHookRequestNode extends AbstractWebHookNode {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final NodeVariable<WebHookReply> reply = new NodeVariable<>("Reply", WebHookReply.class).transientValue();

    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private TextField timeoutField;

    @Override
    protected boolean awaitsReply() {
        return true;
    }

    @Override
    protected int replyTimeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    protected void configureExtraOutputs() {
        addOutput(reply);
    }

    @Override
    protected void onMatched(WebHookEvent event) {
        reply.setValue(event.reply());
    }

    @Override
    protected void saveExtraState(Map<String, String> state) {
        state.put("timeoutSeconds", Integer.toString(timeoutSeconds));
    }

    @Override
    protected void loadExtraState(Map<String, String> state) {
        timeoutSeconds = parseTimeout(state.get("timeoutSeconds"));
    }

    @Override
    protected VBox createExtraContent() {
        Label label = new Label("Reply timeout (s)");
        label.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        timeoutField = new TextField(Integer.toString(timeoutSeconds));
        timeoutField.textProperty().addListener((obs, old, value) -> {
            timeoutSeconds = parseTimeout(value);
            redeclare();
        });

        return new VBox(2, label, timeoutField);
    }

    /** Test seam: the currently configured reply timeout. */
    int timeoutSeconds() {
        return timeoutSeconds;
    }

    private static int parseTimeout(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : DEFAULT_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
