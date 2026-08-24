package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;
import java.util.StringJoiner;

/**
 * Flattens a list into one piece of text with a separator of your choosing, and optional text
 * before and after. {@code ", "} gives you a sentence, {@code "\n"} gives you a block of lines,
 * and a prefix of {@code "- "} with a separator of {@code "\n- "} gives you a bullet list ready to
 * post to Discord.
 * <p>
 * <b>The separator is unescaped:</b> {@code \n} and {@code \t} typed into the field become a real
 * newline and tab, since a text field is the only way to author one and a literal backslash-n in
 * the middle of a message is never what was meant. Write {@code \\} for an actual backslash.
 * <p>
 * The host's built-in <b>List to String</b> does the same job with the separator fixed at a
 * newline; this exists for every other shape of output. Entries render with
 * {@link Lists#text} — null entries as nothing rather than as the word "null", which is the
 * difference between a stray blank line and a stray {@code null} in a message someone reads.
 * An empty list joins to an empty string, prefix and suffix included.
 */
@Display.Name("Join List")
@Display.Description("Joins a list into one piece of text with a chosen separator.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"join", "concatenate", "text", "string", "separator", "list", "lines", "format"})
@Node.Type("collections.JoinListNode")
public class JoinListNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> separator = new NodeVariable<>("Separator", String.class, true);
    private final NodeVariable<String> prefix = new NodeVariable<>("Prefix", String.class, true);
    private final NodeVariable<String> suffix = new NodeVariable<>("Suffix", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Text", String.class);

    public JoinListNode() {
        separator.setValue(", ");
    }

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.copyOf(list.getValue());
        if (entries.isEmpty()) {
            result.setValue("");
            return;
        }
        StringJoiner joiner = new StringJoiner(
                Lists.unescape(separator.getValue()),
                Lists.unescape(prefix.getValue()),
                Lists.unescape(suffix.getValue()));
        for (Object entry : entries) {
            joiner.add(Lists.text(entry));
        }
        result.setValue(joiner.toString());
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(separator);
        addInput(prefix);
        addInput(suffix);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
