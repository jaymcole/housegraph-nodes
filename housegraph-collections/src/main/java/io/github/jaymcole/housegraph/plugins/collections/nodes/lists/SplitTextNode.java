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
 * Splits text into a list, on a separator. Entries are trimmed and blank ones dropped, so
 * {@code "kitchen, hallway,  , porch"} yields three entries and a trailing comma costs nothing.
 * <p>
 * <b>This is also how you write a list literal.</b> Wire a Constant String in — or just type into
 * the Text field — and you have an authored list. It has to work this way round: a list value
 * cannot survive a save, because the graph writer hands values straight to {@code org.json} (which
 * turns a {@code List} into a {@code JSONArray}) and the reader only coerces numbers, so a
 * manually-editable list input would reload as a {@code JSONArray} under a {@code List}-typed
 * variable and blow up on first read. Text round-trips perfectly, so the authored form is text and
 * the list is computed from it.
 * <p>
 * The separator is a plain string, not a regular expression — a {@code "."} splits on full stops
 * rather than on everything. Splitting on a pattern is the regex-aware job of a text node, not of
 * this one.
 */
@Display.Name("Split Text")
@Display.Description("Splits text into a list on a separator, trimming entries and dropping blanks.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"split", "text", "string", "list", "separator", "delimiter", "parse", "literal", "csv"})
@Node.Type("collections.SplitTextNode")
public class SplitTextNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> separator = new NodeVariable<>("Separator", String.class, true);

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    public SplitTextNode() {
        separator.setValue(",");
    }

    @Override
    public void process(ProcessContext ctx) {
        String source = text.getValue();
        if (source == null || source.isEmpty()) {
            result.setValue(List.of());
            return;
        }
        String on = separator.getValue();
        List<Object> entries = new ArrayList<>();
        for (String entry : split(source, on)) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        result.setValue(Lists.frozen(entries));
    }

    /**
     * Splits on a literal separator. A null or empty separator splits into single characters,
     * which is the only reading of "separated by nothing" that isn't simply an error — and is
     * occasionally what someone wanted.
     */
    private static String[] split(String source, String on) {
        if (on == null || on.isEmpty()) {
            return source.split("");
        }
        return source.split(java.util.regex.Pattern.quote(on), -1);
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(separator);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
