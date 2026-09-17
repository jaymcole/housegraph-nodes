package io.github.jaymcole.housegraph.plugins.datetime.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/**
 * Reads the current moment as epoch milliseconds.
 * <p>
 * A pure data node like {@link MillisToDateTimeNode} — no flow ports. It has no input to read
 * stale, so every pull reflects the instant something downstream asked for it.
 */
@Display.Name("Current Date Time")
@Display.Description("The current moment as epoch milliseconds.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"now", "today", "current", "date", "time", "datetime", "clock"})
@Node.Type("datetime.CurrentDateTimeNode")
public class CurrentDateTimeNode extends BaseNode {

    private final NodeVariable<Long> milliseconds = new NodeVariable<>("Milliseconds", Long.class)
            .describedAs("Milliseconds since the Unix epoch, UTC.");

    @Override
    public void process(ProcessContext ctx) {
        milliseconds.setValue(System.currentTimeMillis());
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(milliseconds);
    }
}
