package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;

/**
 * The numbers about a list of numbers: <b>Sum</b>, <b>Minimum</b>, <b>Maximum</b>, <b>Average</b>,
 * and <b>Numeric Count</b> — how many entries could actually be read as numbers (see
 * {@link Lists#number}; unreadable entries are skipped rather than failing the node).
 * <p>
 * One node with five outputs rather than five nodes, because they are one pass over the same list
 * and a graph asking for an average nearly always wants the count beside it. Nothing is computed
 * that isn't wired up anyway — an unwired output costs a field write.
 * <p>
 * <b>An empty list reports a sum of zero and nulls elsewhere.</b> Zero is the honest total of
 * nothing; a minimum, maximum or average of nothing is not zero, it is unanswerable, and emitting
 * {@code 0} would put a wrong number into whatever displays it. A null reads as "no value" at the
 * anchor and leaves the decision to the graph.
 */
@Display.Name("List Statistics")
@Display.Description("Sum, minimum, maximum and average of a list of numbers.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"sum", "total", "average", "mean", "minimum", "maximum", "min", "max", "statistics", "numbers"})
@Node.Type("collections.ListStatisticsNode")
public class ListStatisticsNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<Double> sum = new NodeVariable<>("Sum", Double.class);
    private final NodeVariable<Double> minimum = new NodeVariable<>("Minimum", Double.class);
    private final NodeVariable<Double> maximum = new NodeVariable<>("Maximum", Double.class);
    private final NodeVariable<Double> average = new NodeVariable<>("Average", Double.class);
    private final NodeVariable<Integer> numericCount = new NodeVariable<>("Numeric Count", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        double total = 0;
        Double lowest = null;
        Double highest = null;
        int counted = 0;

        for (Object entry : Lists.copyOf(list.getValue())) {
            Double number = Lists.number(entry);
            if (number == null) {
                continue;
            }
            counted++;
            total += number;
            if (lowest == null || number < lowest) {
                lowest = number;
            }
            if (highest == null || number > highest) {
                highest = number;
            }
        }

        sum.setValue(total);
        minimum.setValue(lowest);
        maximum.setValue(highest);
        average.setValue(counted == 0 ? null : total / counted);
        numericCount.setValue(counted);
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(sum);
        addOutput(minimum);
        addOutput(maximum);
        addOutput(average);
        addOutput(numericCount);
    }
}
