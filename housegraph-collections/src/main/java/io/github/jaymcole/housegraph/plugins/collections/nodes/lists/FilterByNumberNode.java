package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Comparison;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the entries that stand in a chosen numeric relation to a value — everything above 20,
 * everything at or below zero. <b>Comparison</b> is typed in as a symbol ({@code >}, {@code >=},
 * {@code <}, {@code <=}, {@code ==}, {@code !=}); see {@link Comparison}.
 * <p>
 * <b>Entries that don't read as numbers are dropped</b>, not passed through and not fatal (see
 * {@link Lists#number} — a {@code Number} counts, so does a string that parses as one, nothing
 * else does). Keeping them would mean emitting a list that a numeric filter has just said nothing
 * about; failing on them would make this unusable on the mixed lists that erasure makes ordinary.
 * <b>Skipped</b> reports how many were unreadable, so a list that quietly wasn't numeric at all
 * says so rather than just coming back empty.
 */
@Display.Name("Filter by Number")
@Display.Description("Keeps the entries above, below or equal to a number.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"filter", "number", "numeric", "greater", "less", "threshold", "compare", "where", "list"})
@Node.Type("collections.FilterByNumberNode")
public class FilterByNumberNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();
    private final NodeVariable<String> comparison = new NodeVariable<>("Comparison", String.class, true);
    private final NodeVariable<Float> value = new NodeVariable<>("Value", Float.class, true).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> kept = new NodeVariable<>("Kept", Integer.class);
    private final NodeVariable<Integer> skipped = new NodeVariable<>("Skipped", Integer.class);

    public FilterByNumberNode() {
        comparison.setValue(Comparison.GREATER.label);
        value.setValue(0f);
    }

    @Override
    public void process(ProcessContext ctx) {
        Comparison test = Comparison.parse(comparison.getValue());
        double threshold = ctx.get(value, 0f);

        List<Object> matching = new ArrayList<>();
        int unreadable = 0;
        for (Object entry : Lists.copyOf(list.getValue())) {
            Double number = Lists.number(entry);
            if (number == null) {
                unreadable++;
            } else if (test.test(number, threshold)) {
                matching.add(entry);
            }
        }
        result.setValue(Lists.frozen(matching));
        kept.setValue(matching.size());
        skipped.setValue(unreadable);
    }

    @Override
    public void configureInputs() {
        addInput(list);
        addInput(comparison);
        addInput(value);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
        addOutput(kept);
        addOutput(skipped);
    }
}
