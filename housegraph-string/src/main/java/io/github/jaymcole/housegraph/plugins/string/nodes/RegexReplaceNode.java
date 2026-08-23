package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Patterns;
import io.github.jaymcole.housegraph.plugins.string.Texts;

import java.util.regex.Matcher;

/**
 * Replaces everything matching a regular expression, and reports how many replacements it made.
 * <p>
 * <b>The replacement can refer back to what matched</b>: {@code $1} is the first capture group,
 * {@code $0} the whole match. That is what makes this a reformatter rather than just a deleter —
 * {@code (\d{4})-(\d{2})-(\d{2})} replaced with {@code $3/$2/$1} turns an ISO date around, in one
 * node. To put a literal dollar sign or backslash in the replacement, escape it as {@code \$} or
 * {@code \\}.
 * <p>
 * <b>Deleting is replacing with nothing</b>: leave Replacement blank to strip every match out,
 * which is the shortest way to scrub markup, control characters or an unwanted prefix.
 * <p>
 * Matching is case-sensitive; prefix the pattern with {@code (?i)} for insensitive. A pattern that
 * will not compile fails the node (see {@link Patterns}), and so does a replacement referring to a
 * group the pattern does not have — both are configuration mistakes that would otherwise show up
 * as text that quietly comes back unchanged.
 * <p>
 * For plain, non-pattern replacement, use <b>Replace Text</b> instead: it needs no escaping, so a
 * {@code "."} there means a full stop rather than any character at all.
 */
@Display.Name("Regex Replace")
@Display.Description("Replaces everything matching a regular expression, with group references.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"regex", "regexp", "pattern", "replace", "substitute", "rewrite", "strip", "remove", "text", "string"})
@Node.Type("string.RegexReplaceNode")
public class RegexReplaceNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> pattern = new NodeVariable<>("Pattern", String.class, true).required();
    private final NodeVariable<String> replacement = new NodeVariable<>("Replacement", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Result", String.class);
    private final NodeVariable<Integer> replacements = new NodeVariable<>("Replacements", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Matcher matcher = Patterns.compile(pattern.getValue()).matcher(Texts.orEmpty(text.getValue()));
        String with = Texts.orEmpty(replacement.getValue());

        // Appended by hand rather than via replaceAll() so the count comes out of the same pass
        // that does the work, instead of a second one that could disagree with it.
        StringBuilder rewritten = new StringBuilder();
        int count = 0;
        try {
            while (matcher.find()) {
                matcher.appendReplacement(rewritten, with);
                count++;
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Replacement \"" + with + "\" does not fit pattern \""
                    + pattern.getValue() + "\": " + e.getMessage(), e);
        }
        matcher.appendTail(rewritten);

        result.setValue(rewritten.toString());
        replacements.setValue(count);
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(pattern);
        addInput(replacement);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(replacements);
    }
}
