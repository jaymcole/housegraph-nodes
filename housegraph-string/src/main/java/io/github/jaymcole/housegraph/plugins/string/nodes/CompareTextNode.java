package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.CompareMode;

/**
 * Tests text against other text — contains, starts with, ends with, equals, and the negation of
 * each — and reports the answer as a boolean for the host's built-in <b>If Bool</b> to branch on.
 * <p>
 * <b>Why one node with a mode rather than four nodes.</b> All six comparisons have the same two
 * inputs and the same two outputs; splitting them would put four near-identical entries in the
 * palette that differ only in a word. Modes that would change what the other fields <em>mean</em>
 * do get their own node — that is why matching by pattern is <b>Regex Match</b> and not a seventh
 * mode here.
 * <p>
 * <b>It does not branch itself.</b> A node that both tested text and chose which flow ran next
 * would fuse a data question into a control decision, and could not be reused anywhere the answer
 * was wanted as a value. Comparison stays here; branching stays in If Bool.
 * <p>
 * <b>Index</b> is where the search text first appears, or -1 when it does not — set whichever mode
 * is selected, because it is the same answer either way and it is what a Substring downstream
 * needs. Comparison is case-insensitive; see {@link CompareMode}.
 */
@Display.Name("Compare Text")
@Display.Description("Tests whether text contains, starts with, ends with or equals other text.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"compare", "contains", "starts with", "ends with", "equals", "match", "test", "search", "text", "string"})
@Node.Type("string.CompareTextNode")
public class CompareTextNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> search = new NodeVariable<>("Search", String.class, true).required();
    private final NodeVariable<String> mode = new NodeVariable<>("Comparison", String.class, true);

    private final NodeVariable<Boolean> result = new NodeVariable<>("Result", Boolean.class);
    private final NodeVariable<Integer> index = new NodeVariable<>("Index", Integer.class);

    public CompareTextNode() {
        mode.setValue(CompareMode.CONTAINS.label());
    }

    @Override
    public void process(ProcessContext ctx) {
        result.setValue(CompareMode.parse(mode.getValue()).test(text.getValue(), search.getValue()));
        index.setValue(CompareMode.indexOf(text.getValue(), search.getValue()));
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(search);
        addInput(mode);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(index);
    }
}
