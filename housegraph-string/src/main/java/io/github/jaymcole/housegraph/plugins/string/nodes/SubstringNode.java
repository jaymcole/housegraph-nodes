package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Texts;

/**
 * Takes a slice of text between two character positions — <b>Start</b> included, <b>End</b>
 * excluded, both counting from zero.
 * <p>
 * <b>Nothing here throws.</b> {@link String#substring} is famously happy to fail on a position
 * past the end of the text, which in a graph would mean a node that works all week and then fails
 * the night a message arrives shorter than usual. Both positions are clamped into the text
 * instead, and an End before the Start yields empty text.
 * <p>
 * <b>An unset position means "the natural end".</b> Leave Start blank for the beginning of the
 * text and End blank for its end, so taking "everything from character 8 on" needs no Length node
 * to fill in a number. <b>A negative position counts back from the end</b> — Start {@code -4} is
 * the last four characters — which is the same convention the Collections library uses for list
 * indices.
 */
@Display.Name("Substring")
@Display.Description("Takes a slice of text between two character positions.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"substring", "slice", "cut", "extract", "range", "characters", "text", "string"})
@Node.Type("string.SubstringNode")
public class SubstringNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<Integer> start = new NodeVariable<>("Start", Integer.class, true);
    private final NodeVariable<Integer> end = new NodeVariable<>("End", Integer.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Result", String.class);

    @Override
    public void process(ProcessContext ctx) {
        String source = Texts.orEmpty(text.getValue());
        int from = Texts.resolvePosition(start.getValue(), source.length(), 0);
        int to = Texts.resolvePosition(end.getValue(), source.length(), source.length());
        result.setValue(to <= from ? "" : source.substring(from, to));
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(start);
        addInput(end);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
