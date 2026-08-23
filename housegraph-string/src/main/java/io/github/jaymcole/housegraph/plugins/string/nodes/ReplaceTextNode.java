package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Texts;

/**
 * Replaces every occurrence of one piece of text with another, and reports how many it replaced.
 * <p>
 * <b>Find is a plain string, not a pattern</b> — a {@code "."} replaces full stops rather than
 * every character. Replacing by pattern is <b>Regex Replace</b>'s job, and keeping the two apart
 * means neither node needs a mode input that changes what its other fields mean. The match is
 * case-sensitive for the same reason: replacement is surgery on text, and a case-insensitive
 * replace that also rewrites the casing of what it found is almost never what was wanted.
 * <p>
 * <b>Replacements</b> exists so a graph can tell "nothing needed changing" from "the text was
 * rewritten" without comparing before and after — wire it into an If Bool through a comparison,
 * or just watch it while debugging. A blank <b>Find</b> replaces nothing and reports zero, rather
 * than {@link String#replace}'s surprising answer of inserting the replacement between every pair
 * of characters.
 */
@Display.Name("Replace Text")
@Display.Description("Replaces every occurrence of one piece of text with another.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"replace", "substitute", "swap", "find", "text", "string", "rewrite"})
@Node.Type("string.ReplaceTextNode")
public class ReplaceTextNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> find = new NodeVariable<>("Find", String.class, true).required();
    private final NodeVariable<String> replaceWith = new NodeVariable<>("Replace With", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Result", String.class);
    private final NodeVariable<Integer> replacements = new NodeVariable<>("Replacements", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        String source = Texts.orEmpty(text.getValue());
        String target = Texts.orEmpty(find.getValue());
        if (target.isEmpty()) {
            result.setValue(source);
            replacements.setValue(0);
            return;
        }
        result.setValue(source.replace(target, Texts.orEmpty(replaceWith.getValue())));
        replacements.setValue(countOccurrences(source, target));
    }

    /** Counts non-overlapping occurrences, matching what {@link String#replace} actually replaces. */
    private static int countOccurrences(String source, String target) {
        int count = 0;
        int at = source.indexOf(target);
        while (at >= 0) {
            count++;
            at = source.indexOf(target, at + target.length());
        }
        return count;
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(find);
        addInput(replaceWith);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(replacements);
    }
}
