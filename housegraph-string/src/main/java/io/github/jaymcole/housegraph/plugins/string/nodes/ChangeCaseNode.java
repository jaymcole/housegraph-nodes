package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.CaseMode;

/**
 * Converts text to upper, lower, title or sentence case. The <b>Case</b> input is authored as
 * text; leaving it blank selects upper case, and an unrecognised one fails the node rather than
 * quietly picking a default (see {@link CaseMode}).
 * <p>
 * Case conversion is locale-independent, which matters more than it sounds: a graph that
 * upper-cases a device name to match against must produce the same answer whatever the host
 * machine's language is set to.
 */
@Display.Name("Change Case")
@Display.Description("Converts text to upper, lower, title or sentence case.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"case", "upper", "lower", "uppercase", "lowercase", "title", "capitalise", "capitalize", "text", "string"})
@Node.Type("string.ChangeCaseNode")
public class ChangeCaseNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> mode = new NodeVariable<>("Case", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Result", String.class);

    public ChangeCaseNode() {
        // Pre-filled rather than left blank: the field is how a user discovers the vocabulary,
        // and every other mode is one word away from the one already showing.
        mode.setValue(CaseMode.UPPER.label());
    }

    @Override
    public void process(ProcessContext ctx) {
        result.setValue(CaseMode.parse(mode.getValue()).apply(text.getValue()));
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
