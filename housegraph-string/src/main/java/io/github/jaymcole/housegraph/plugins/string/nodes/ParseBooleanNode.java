package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Texts;

import java.util.List;
import java.util.Locale;

/**
 * Reads a yes-or-no answer out of text, for an If Bool to branch on.
 * <p>
 * <b>People and machines do not spell this the same way</b>, which is the whole reason the node
 * exists: a config file says {@code true}, a chat message says {@code yes}, a switch reports
 * {@code on}, and a CSV column says {@code 1}. All of them mean the same thing, and a graph should
 * not need three comparisons wired in parallel to find that out. Case and surrounding whitespace
 * are ignored.
 * <p>
 * <b>Accepted:</b> {@code true}, {@code yes}, {@code y}, {@code on}, {@code 1} for true, and
 * {@code false}, {@code no}, {@code n}, {@code off}, {@code 0} for false.
 * <p>
 * <b>Anything else is not a failure</b>, it reports {@code Valid = false} with a Value of false —
 * the same reasoning as Parse Number. Text a person typed is data, not a misconfiguration, and
 * "they answered something I do not understand" is a case a graph should be able to branch on and
 * reply to rather than a reason to knock the node over. Branch on <b>Valid</b> first when the
 * difference between "no" and "no answer" matters.
 */
@Display.Name("Parse Boolean")
@Display.Description("Reads yes/no, true/false or on/off out of text.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"parse", "boolean", "bool", "true", "false", "yes", "no", "flag", "convert", "text", "string"})
@Node.Type("string.ParseBooleanNode")
public class ParseBooleanNode extends BaseNode {

    private static final List<String> TRUE_WORDS = List.of("true", "yes", "y", "on", "1");
    private static final List<String> FALSE_WORDS = List.of("false", "no", "n", "off", "0");

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();

    private final NodeVariable<Boolean> value = new NodeVariable<>("Value", Boolean.class);
    private final NodeVariable<Boolean> valid = new NodeVariable<>("Valid", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        String answer = Texts.orEmpty(text.getValue()).strip().toLowerCase(Locale.ROOT);
        boolean isTrue = TRUE_WORDS.contains(answer);
        boolean isFalse = FALSE_WORDS.contains(answer);

        value.setValue(isTrue);
        valid.setValue(isTrue || isFalse);
    }

    @Override
    public void configureInputs() {
        addInput(text);
    }

    @Override
    public void configureOutputs() {
        addOutput(value);
        addOutput(valid);
    }
}
