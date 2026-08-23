package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Texts;

/**
 * Shortens text to a maximum length, marking what it cut with an ellipsis.
 * <p>
 * <b>This is the node that stops a graph failing at 3am.</b> Anything that forwards text it did
 * not author — a log line into Discord, a web hook body onto the Squirrel sign, an error message
 * into a chat reply — is one unusually long message away from being rejected by whatever it is
 * sending to. Truncating first turns that from a failed send into a shortened one. <b>Max
 * Length</b> defaults to 2000 for that reason: it is Discord's per-message limit, the ceiling most
 * likely to be hit first here.
 * <p>
 * <b>The ellipsis counts toward the limit</b>, so the result never exceeds Max Length — the point
 * of the node would be lost if it handed back something one character too long. A limit shorter
 * than the ellipsis yields as much of the ellipsis as fits. <b>Truncated</b> reports whether
 * anything was actually cut, so a graph can say "(truncated)" or link to the full text only when
 * there is more to see.
 */
@Display.Name("Truncate Text")
@Display.Description("Shortens text to a maximum length, adding an ellipsis.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"truncate", "shorten", "limit", "clip", "ellipsis", "length", "text", "string"})
@Node.Type("string.TruncateTextNode")
public class TruncateTextNode extends BaseNode {

    /** Discord's per-message limit — the ceiling a graph here is most likely to hit first. */
    private static final int DEFAULT_MAX_LENGTH = 2000;

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<Integer> maxLength = new NodeVariable<>("Max Length", Integer.class, true);
    private final NodeVariable<String> ellipsis = new NodeVariable<>("Ellipsis", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Result", String.class);
    private final NodeVariable<Boolean> truncated = new NodeVariable<>("Truncated", Boolean.class);

    public TruncateTextNode() {
        maxLength.setValue(DEFAULT_MAX_LENGTH);
        ellipsis.setValue("...");
    }

    @Override
    public void process(ProcessContext ctx) {
        String source = Texts.orEmpty(text.getValue());
        Integer authored = maxLength.getValue();
        int limit = Math.max(0, authored == null ? DEFAULT_MAX_LENGTH : authored);

        if (source.length() <= limit) {
            result.setValue(source);
            truncated.setValue(false);
            return;
        }

        String suffix = Texts.orEmpty(ellipsis.getValue());
        // The ellipsis has to fit inside the limit too, so a very small limit keeps only as much
        // of it as there is room for - never a result longer than the caller asked for.
        String shortened = suffix.length() >= limit
                ? suffix.substring(0, limit)
                : source.substring(0, limit - suffix.length()) + suffix;
        result.setValue(shortened);
        truncated.setValue(true);
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(maxLength);
        addInput(ellipsis);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(truncated);
    }
}
