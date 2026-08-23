package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.TextMatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the entries whose text stands in a chosen relation to some text: contains it, starts with
 * it, ends with it, equals it, or the negation of any of those. <b>Mode</b> is typed in as a word
 * ({@code contains}, {@code not contains}, {@code starts with}, {@code ends with}, {@code equals},
 * {@code not equals}); see {@link TextMatch} for why a word and not a checkbox, and for the note
 * that matching is case-insensitive.
 * <p>
 * Entries are compared by their {@link Lists#text text form}, so this works on a list of anything,
 * not only on a list of strings. <b>Kept</b> and <b>Removed</b> report the two counts, which is
 * usually what a message about the result wants to say.
 * <p>
 * <b>This is what "filter" means here.</b> There is no callback-driven filter — no subgraph run
 * per element to decide — because that node would be a control node, a second <b>For Each</b> with
 * a boolean read back out of the body, and this library holds no control nodes on purpose (see the
 * package documentation). Between this, <b>Filter by Pattern</b> and <b>Filter by Number</b>, the
 * predicate is a parameter instead.
 */
@Display.Name("Filter by Text")
@Display.Description("Keeps the entries whose text contains, starts with, ends with or equals some text.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"filter", "text", "contains", "starts with", "ends with", "search", "where", "keep", "list"})
@Node.Type("collections.FilterByTextNode")
public class FilterByTextNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> mode = new NodeVariable<>("Mode", String.class, true);

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> kept = new NodeVariable<>("Kept", Integer.class);
    private final NodeVariable<Integer> removed = new NodeVariable<>("Removed", Integer.class);

    public FilterByTextNode() {
        mode.setValue(TextMatch.CONTAINS.label);
    }

    @Override
    public void process(ProcessContext ctx) {
        TextMatch match = TextMatch.parse(mode.getValue());
        String needle = text.getValue();
        List<Object> entries = Lists.copyOf(list.getValue());
        List<Object> matching = new ArrayList<>();
        for (Object entry : entries) {
            if (match.matches(Lists.text(entry), needle)) {
                matching.add(entry);
            }
        }
        result.setValue(Lists.frozen(matching));
        kept.setValue(matching.size());
        removed.setValue(entries.size() - matching.size());
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(text);
        addInput(mode);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(kept);
        addOutput(removed);
    }
}
