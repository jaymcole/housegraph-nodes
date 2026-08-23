package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Patterns;
import io.github.jaymcole.housegraph.plugins.string.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Finds every match of a regular expression in text and collects them into a list — for a
 * <b>For Each</b> to walk, or a <b>Join Text</b> to reassemble.
 * <p>
 * <b>This is also how you split on a pattern.</b> The Collections library's Split Text splits on a
 * plain separator, which is the right tool when the separator is literally a comma; when what
 * separates the pieces varies — runs of whitespace, a comma with unpredictable spacing around it —
 * describing the <em>pieces</em> is easier than describing the gaps. A pattern of {@code \S+}
 * splits a log line into its words in one step.
 * <p>
 * <b>Group</b> chooses what each entry is: 0, the default, is the whole match, and a higher number
 * is that capture group of each match — so {@code (\w+)=(\d+)} with Group 1 lists the names and
 * with Group 2 lists the values. Asking for a group the pattern does not have fails the node
 * rather than returning an empty list, because an empty list looks exactly like "nothing matched"
 * and would send you hunting the wrong problem.
 * <p>
 * Matching is case-sensitive; prefix the pattern with {@code (?i)} for insensitive. A group that
 * took part in no match reads as empty text, and a pattern that will not compile fails the node
 * (see {@link Patterns}). <b>Matches</b> is never null — nothing found yields an empty list and a
 * Count of 0.
 */
@Display.Name("Regex Find All")
@Display.Description("Finds every match of a regular expression and collects them into a list.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"regex", "regexp", "pattern", "find", "all", "matches", "extract", "split", "list", "text", "string"})
@Node.Type("string.RegexFindAllNode")
public class RegexFindAllNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> pattern = new NodeVariable<>("Pattern", String.class, true).required();
    private final NodeVariable<Integer> group = new NodeVariable<>("Group", Integer.class, true);

    private final NodeVariable<List<?>> matches = new NodeVariable<>("Matches", Texts.LIST_TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    public RegexFindAllNode() {
        group.setValue(0);
    }

    @Override
    public void process(ProcessContext ctx) {
        Integer authored = group.getValue();
        int wanted = authored == null ? 0 : authored;
        Matcher matcher = Patterns.compile(pattern.getValue()).matcher(Texts.orEmpty(text.getValue()));
        if (wanted < 0 || wanted > matcher.groupCount()) {
            throw new IllegalArgumentException("Group " + wanted + " was asked for, but \"" + pattern.getValue()
                    + "\" has " + matcher.groupCount() + " capture group(s)");
        }

        List<Object> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(Texts.orEmpty(matcher.group(wanted)));
        }

        matches.setValue(List.copyOf(found));
        count.setValue(found.size());
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(pattern);
        addInput(group);
    }

    @Override
    public void configureOutputs() {
        addOutput(matches);
        addOutput(count);
    }
}
