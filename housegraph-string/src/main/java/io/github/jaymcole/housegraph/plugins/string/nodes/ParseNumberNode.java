package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Texts;

import java.util.regex.Pattern;

/**
 * Reads a number out of text.
 * <p>
 * <b>This closes a one-way street in the host.</b> Its built-in converter nodes all go from a
 * number to text, and the hidden conversion matrix deliberately does not cover the way back — so
 * without this node, a number that arrives as text (a web hook field, a chat command's argument, a
 * sensor line) can never be compared, added to, or used as a threshold. It can only be displayed.
 * <p>
 * <b>Number is a Double, and that is enough for both cases.</b> The host converts Double to
 * Integer implicitly (truncating, and it colours the connection to say so), so an integer input
 * downstream can be fed from this output directly without a second parse node for whole numbers.
 * <p>
 * <b>Invalid text does not fail the node</b>, it reports {@code Valid = false} and a Number of 0.
 * Text that fails to parse is ordinary data here, not a configuration mistake — a message a person
 * typed, a field that was blank this time — and a graph should be able to branch on that with an
 * If Bool rather than being knocked over by it. Surrounding whitespace is ignored; anything else
 * that is not a plain number, including thousands separators and a trailing percent sign, is
 * invalid.
 */
@Display.Name("Parse Number")
@Display.Description("Reads a number out of text, reporting whether it was one.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"parse", "number", "numeric", "double", "integer", "convert", "cast", "text", "string"})
@Node.Type("string.ParseNumberNode")
public class ParseNumberNode extends BaseNode {

    /**
     * What counts as a number here: an optional sign, digits with an optional decimal part, and an
     * optional exponent. Deliberately stricter than {@link Double#valueOf}, which also accepts
     * {@code NaN}, {@code Infinity}, hexadecimal floats and a trailing {@code d} or {@code f} — so
     * without this gate a chat message of "Infinity" or a version string of "10f" would parse as a
     * number and quietly take a numeric branch nobody intended.
     */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();

    private final NodeVariable<Double> number = new NodeVariable<>("Number", Double.class);
    private final NodeVariable<Boolean> valid = new NodeVariable<>("Valid", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        String source = Texts.orEmpty(text.getValue()).strip();
        if (!NUMBER.matcher(source).matches()) {
            number.setValue(0.0);
            valid.setValue(false);
            return;
        }
        number.setValue(Double.valueOf(source));
        valid.setValue(true);
    }

    @Override
    public void configureInputs() {
        addInput(text);
    }

    @Override
    public void configureOutputs() {
        addOutput(number);
        addOutput(valid);
    }
}
