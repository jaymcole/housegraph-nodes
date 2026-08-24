package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites every entry through a template, giving a list of strings. The template may use
 * <b>{item}</b> for the entry, <b>{index}</b> for its zero-based position and <b>{number}</b> for
 * its one-based position, so {@code "{number}. {item}"} turns a list of camera names into a
 * numbered list ready for <b>Join List</b>.
 * <p>
 * <b>This is the map node, in the only form a node graph can offer one</b> without a callback: the
 * transformation is a parameter rather than a subgraph. That covers the case that actually comes
 * up — reshaping entries for something a person will read — and leaves genuine per-element
 * computation to a <b>For Each</b> with <b>Collect Items</b> at the far end, which is the composed
 * form of the same thing.
 * <p>
 * A placeholder that isn't one of the three is left alone, so a template containing {@code {}} or
 * some other brace comes through intact. Escapes ({@code \n} and friends) are resolved as they are
 * in <b>Join List</b>.
 */
@Display.Name("Format Each")
@Display.Description("Rewrites every entry through a template with {item} and {index}.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"format", "template", "map", "transform", "rewrite", "each", "prefix", "list"})
@Node.Type("collections.FormatEachNode")
public class FormatEachNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> template = new NodeVariable<>("Template", String.class, true).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    public FormatEachNode() {
        template.setValue("{item}");
    }

    @Override
    public void process(ProcessContext ctx) {
        String pattern = Lists.unescape(ctx.get(template, "{item}"));
        List<Object> entries = Lists.copyOf(list.getValue());
        List<Object> formatted = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            formatted.add(apply(pattern, entries.get(i), i));
        }
        result.setValue(Lists.frozen(formatted));
    }

    /** Fills the three placeholders in one entry's copy of the template. */
    private static String apply(String pattern, Object entry, int index) {
        return pattern.replace("{item}", Lists.text(entry))
                .replace("{index}", String.valueOf(index))
                .replace("{number}", String.valueOf(index + 1));
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(template);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
