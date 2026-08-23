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

/**
 * A list of evenly spaced whole numbers: {@code Count} of them, starting at {@code Start}, each
 * {@code Step} apart. Wired into the host's <b>For Each</b> it is the "do this N times" the loop
 * itself doesn't provide — and a negative step counts down.
 * <p>
 * A count of zero or less yields an empty list rather than an error: a range computed from
 * something upstream reaching zero is an ordinary state of affairs, not a fault. A step of zero
 * <em>is</em> rejected, since it would ask for N copies of one number, which is a mistake often
 * enough that failing loudly beats guessing.
 */
@Display.Name("Number Range")
@Display.Description("A list of evenly spaced whole numbers.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"range", "sequence", "numbers", "count", "series", "loop", "times", "integers"})
@Node.Type("collections.NumberRangeNode")
public class NumberRangeNode extends BaseNode {

    /** A ceiling on the generated list, so a mistyped count can't exhaust the heap. */
    private static final int MAXIMUM_COUNT = 100_000;

    private final NodeVariable<Integer> start = new NodeVariable<>("Start", Integer.class, true);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class, true).required();
    private final NodeVariable<Integer> step = new NodeVariable<>("Step", Integer.class, true);

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    public NumberRangeNode() {
        start.setValue(0);
        count.setValue(10);
        step.setValue(1);
    }

    @Override
    public void process(ProcessContext ctx) {
        int first = ctx.get(start, 0);
        int howMany = ctx.get(count, 0);
        int by = ctx.get(step, 1);

        if (by == 0) {
            throw new IllegalArgumentException("Number Range needs a non-zero Step");
        }
        if (howMany > MAXIMUM_COUNT) {
            throw new IllegalArgumentException(
                    "Number Range refuses to build " + howMany + " entries (the limit is " + MAXIMUM_COUNT + ")");
        }
        if (howMany <= 0) {
            result.setValue(List.of());
            return;
        }

        List<Object> numbers = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) {
            numbers.add(first + i * by);
        }
        result.setValue(Lists.frozen(numbers));
    }

    @Override
    public void configureInputs() {
        addInput(start);
        addInput(count);
        addInput(step);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
