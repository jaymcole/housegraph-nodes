package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Keeps the entries whose text matches a regular expression. This is the precise counterpart to
 * <b>Filter by Text</b>: case-sensitive unless the pattern says otherwise ({@code (?i)} at the
 * front makes it case-insensitive), and able to express the things a plain substring can't —
 * {@code ^front-.*-camera$}, {@code \d{4}}, an alternation of names.
 * <p>
 * The pattern is <b>searched for</b>, not anchored: it matches an entry if it occurs anywhere in
 * it, which is what people expect from a filter box. Anchor it with {@code ^} and {@code $} when
 * you mean the whole entry.
 * <p>
 * A malformed pattern fails the node with the regex engine's own message, which names the position
 * it choked on. That is deliberately not a silent "matched nothing": an empty result and a broken
 * pattern look identical downstream, and only one of them is worth waking up to fix.
 */
@Display.Name("Filter by Pattern")
@Display.Description("Keeps the entries whose text matches a regular expression.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"filter", "regex", "pattern", "match", "regular expression", "search", "where", "list"})
@Node.Type("collections.FilterByPatternNode")
public class FilterByPatternNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> pattern = new NodeVariable<>("Pattern", String.class, true).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> kept = new NodeVariable<>("Kept", Integer.class);
    private final NodeVariable<Integer> removed = new NodeVariable<>("Removed", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Pattern compiled = compile(pattern.getValue());
        List<Object> entries = Lists.copyOf(list.getValue());
        List<Object> matching = new ArrayList<>();
        for (Object entry : entries) {
            Matcher matcher = compiled.matcher(Lists.text(entry));
            if (matcher.find()) {
                matching.add(entry);
            }
        }
        result.setValue(Lists.frozen(matching));
        kept.setValue(matching.size());
        removed.setValue(entries.size() - matching.size());
    }

    /** Compiles the authored pattern, restating a syntax error as something a node's error tooltip can carry. */
    private static Pattern compile(String text) {
        try {
            return Pattern.compile(text == null ? "" : text);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Filter by Pattern can't read the pattern: " + e.getMessage(), e);
        }
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(pattern);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(kept);
        addOutput(removed);
    }
}
