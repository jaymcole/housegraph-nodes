package io.github.jaymcole.housegraph.plugins.datetime.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Checks whether a moment fell on the same calendar date as another moment.
 * <p>
 * Both inputs are epoch milliseconds, compared as calendar dates in
 * {@link ZoneId#systemDefault()} — the same convention {@link MillisToDateTimeNode} uses, so "the
 * 5th" means the same thing everywhere in this library. Wire {@link CurrentDateTimeNode} into
 * Date to ask "did this happen today?".
 * <p>
 * <b>Yes and No are the outcome of this one comparison</b>, and exactly one of them fires, the
 * same shape {@code housegraph-database}'s Find Rows uses for Found/None. There is also an
 * Occurred data output for the same question asked as a value.
 * <p>
 * Unwired inputs read as epoch 0 rather than failing, matching {@link MillisToDateTimeNode}.
 */
@Display.Name("Occurred On Date")
@Display.Description("Checks whether a datetime fell on the same calendar date as another datetime.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"date", "time", "datetime", "today", "same day", "compare", "occurred", "happened"})
@Node.Type("datetime.OccurredOnDateNode")
public class OccurredOnDateNode extends BaseNode {

    private final NodeVariable<Long> millis = new NodeVariable<>("Milliseconds", Long.class, true).required();
    private final NodeVariable<Long> dateMillis = new NodeVariable<>("Date", Long.class, true).required();

    private final NodeVariable<Boolean> occurred = new NodeVariable<>("Occurred", Boolean.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort yes = new FlowPort("Yes", FlowPort.Direction.OUT);
    private final FlowPort no = new FlowPort("No", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        LocalDate moment = dateOf(millis.getValue());
        LocalDate target = dateOf(dateMillis.getValue());

        boolean matches = moment.equals(target);
        occurred.setValue(matches);
        activate(matches ? yes : no);
    }

    private static LocalDate dateOf(Long epochMillis) {
        long value = epochMillis == null ? 0L : epochMillis;
        return Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    @Override
    public void configureInputs() {
        addInput(millis);
        addInput(dateMillis);
    }

    @Override
    public void configureOutputs() {
        addOutput(occurred);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(yes);
        addFlowOutput(no);
    }
}
