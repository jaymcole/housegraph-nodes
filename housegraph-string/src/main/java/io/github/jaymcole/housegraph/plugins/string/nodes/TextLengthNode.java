package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Texts;

/**
 * How long a piece of text is, and whether there is anything in it.
 * <p>
 * <b>Is Empty and Is Blank are not the same question</b>, and the difference is the one that
 * catches people out: a chat message of three spaces is not empty, but it says nothing.
 * <b>Is Empty</b> is true only for no characters at all; <b>Is Blank</b> is true for that and for
 * text that is entirely whitespace. Wire whichever one matches what you meant into an If Bool —
 * "did the user actually type anything" is almost always Is Blank.
 * <p>
 * An unwired Text input reads as empty text rather than failing, so all three outputs are set on
 * every run: length 0, empty, blank.
 */
@Display.Name("Text Length")
@Display.Description("Reports how long text is, and whether it is empty or blank.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"length", "size", "count", "characters", "empty", "blank", "text", "string"})
@Node.Type("string.TextLengthNode")
public class TextLengthNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();

    private final NodeVariable<Integer> length = new NodeVariable<>("Length", Integer.class);
    private final NodeVariable<Boolean> isEmpty = new NodeVariable<>("Is Empty", Boolean.class);
    private final NodeVariable<Boolean> isBlank = new NodeVariable<>("Is Blank", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        String source = Texts.orEmpty(text.getValue());
        length.setValue(source.length());
        isEmpty.setValue(source.isEmpty());
        isBlank.setValue(source.isBlank());
    }

    @Override
    public void configureInputs() {
        addInput(text);
    }

    @Override
    public void configureOutputs() {
        addOutput(length);
        addOutput(isEmpty);
        addOutput(isBlank);
    }
}
