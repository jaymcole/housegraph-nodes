package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.TrimMode;

/**
 * Removes whitespace from text — from both ends, one end, or (with the <b>collapse</b> mode) from
 * both ends and out of every run of whitespace in the middle.
 * <p>
 * <b>Where this earns its place.</b> Text arriving from outside — a web hook body, a chat message,
 * a git commit subject — routinely carries trailing whitespace or a stray newline that nothing
 * shows on the canvas, and which then makes an equality test fail for no visible reason. Trimming
 * before comparing is the fix, and <b>collapse</b> is what makes a multi-line blob safe to put
 * through a one-line display like the Squirrel sign.
 */
@Display.Name("Trim Text")
@Display.Description("Removes whitespace from the ends of text, or collapses it throughout.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"trim", "strip", "whitespace", "space", "clean", "collapse", "text", "string"})
@Node.Type("string.TrimTextNode")
public class TrimTextNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> mode = new NodeVariable<>("Trim", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Result", String.class);

    public TrimTextNode() {
        mode.setValue(TrimMode.BOTH.label());
    }

    @Override
    public void process(ProcessContext ctx) {
        result.setValue(TrimMode.parse(mode.getValue()).apply(text.getValue()));
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(mode);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
