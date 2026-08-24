package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Flattens a map into one piece of text: every entry through a template, glued together with a
 * separator. This is <b>Format Each</b> and <b>Join List</b> in one node, because a map has no
 * intermediate list to hand between them — <b>Map Entries</b> would give two lists that a
 * hypothetical two-node version would then have to re-pair.
 * <p>
 * The template may use <b>{key}</b> and <b>{value}</b>, so {@code "{key}: {value}"} with a
 * separator of {@code "\n"} gives a block of lines ready to post to Discord, and
 * {@code "- {key} ({value})"} gives a bullet list. A placeholder that is neither is left alone, so
 * a template containing some other brace comes through intact.
 * <p>
 * <b>The separator and template are unescaped:</b> {@code \n} and {@code \t} typed into a field
 * become a real newline and tab, since a text field is the only way to author one. Write
 * {@code \\} for an actual backslash. Entries render in the map's own order — the order they went
 * in — so sort them before this node if you want them alphabetical: <b>Map Entries</b>, <b>Sort
 * List</b> on the keys, then read each value back with <b>Map Get</b>.
 * <p>
 * An empty map joins to an empty string, prefix and suffix included.
 */
@Display.Name("Join Map")
@Display.Description("Renders a map as text, one entry per template, glued with a separator.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"join", "map", "text", "string", "format", "template", "render", "lines", "separator"})
@Node.Type("collections.JoinMapNode")
public class JoinMapNode extends BaseNode {

    private final NodeVariable<Map<?, ?>> map = new NodeVariable<>("Map", Maps.TYPE).required();
    private final NodeVariable<String> template = new NodeVariable<>("Template", String.class, true).required();
    private final NodeVariable<String> separator = new NodeVariable<>("Separator", String.class, true);
    private final NodeVariable<String> prefix = new NodeVariable<>("Prefix", String.class, true);
    private final NodeVariable<String> suffix = new NodeVariable<>("Suffix", String.class, true);

    private final NodeVariable<String> result = new NodeVariable<>("Text", String.class);

    public JoinMapNode() {
        template.setValue("{key}: {value}");
        separator.setValue("\\n");
    }

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> entries = Maps.copyOf(map.getValue());
        if (entries.isEmpty()) {
            result.setValue("");
            return;
        }
        String pattern = Lists.unescape(ctx.get(template, "{key}: {value}"));
        StringJoiner joiner = new StringJoiner(
                Lists.unescape(separator.getValue()),
                Lists.unescape(prefix.getValue()),
                Lists.unescape(suffix.getValue()));
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            joiner.add(apply(pattern, entry.getKey(), entry.getValue()));
        }
        result.setValue(joiner.toString());
    }

    /** Fills the two placeholders in one entry's copy of the template. */
    private static String apply(String pattern, String key, Object value) {
        return pattern.replace("{key}", key).replace("{value}", Lists.text(value));
    }

    @Override
    public void configureInputs() {
        addInput(map);
        addInput(template);
        addInput(separator);
        addInput(prefix);
        addInput(suffix);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
