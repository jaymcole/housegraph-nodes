package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Texts;

import java.util.List;
import java.util.StringJoiner;

/**
 * Joins a list into one piece of text, with a separator of your choosing.
 * <p>
 * <b>How this differs from the host's built-in List to String</b>, which does the same job: that
 * one always joins with a newline. This one takes the separator, which is what a sentence
 * ({@code ", "}), a path ({@code "/"}) or a CSV row needs. Reach for the built-in when one entry
 * per line is what you want.
 * <p>
 * <b>The separator understands escapes</b> — {@code \n}, {@code \t}, {@code \r} and {@code \\} —
 * because a node's inline field is a single line and there is no way to type a real newline into
 * it. Anything else after a backslash is left exactly as typed, so a Windows path survives being
 * used as a separator.
 * <p>
 * The list's element type is erased (see {@code ForEachNode} for why), so entries are rendered
 * with {@link String#valueOf} and any list at all can be wired in — a list of numbers joins as
 * readily as a list of text. A null entry renders as empty rather than as the word {@code "null"},
 * and a null or empty list yields empty text.
 */
@Display.Name("Join Text")
@Display.Description("Joins a list into one piece of text, with a separator of your choosing.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"join", "concatenate", "combine", "merge", "separator", "delimiter", "list", "text", "string", "csv"})
@Node.Type("string.JoinTextNode")
public class JoinTextNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Texts.LIST_TYPE).required();
    private final NodeVariable<String> separator = new NodeVariable<>("Separator", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Result", String.class);

    public JoinTextNode() {
        separator.setValue(", ");
    }

    @Override
    public void process(ProcessContext ctx) {
        List<?> entries = list.getValue();
        if (entries == null || entries.isEmpty()) {
            result.setValue("");
            return;
        }
        StringJoiner joiner = new StringJoiner(Texts.unescape(separator.getValue()));
        for (Object entry : entries) {
            joiner.add(Texts.text(entry));
        }
        result.setValue(joiner.toString());
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(separator);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
