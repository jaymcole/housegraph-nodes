package io.github.jaymcole.housegraph.plugins.datetime.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Breaks an epoch-millisecond timestamp into its calendar fields.
 * <p>
 * Fields are read in {@link ZoneId#systemDefault()}, the same convention the Daily Trigger node
 * uses — a house's automation graph should read "3 PM" the way the house does, not in UTC.
 * <p>
 * An unwired Milliseconds input reads as epoch 0 rather than failing, so every output is set on
 * every run.
 */
@Display.Name("Milliseconds To Date Time")
@Display.Description("Breaks an epoch-millisecond timestamp into year, month, day, hour, minute and second.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"millis", "milliseconds", "epoch", "timestamp", "date", "time", "datetime", "convert", "calendar"})
@Node.Type("datetime.MillisToDateTimeNode")
public class MillisToDateTimeNode extends BaseNode {

    private final NodeVariable<Long> millis = new NodeVariable<>("Milliseconds", Long.class, true).required();

    private final NodeVariable<Integer> year = new NodeVariable<>("Year", Integer.class);
    private final NodeVariable<Integer> month = new NodeVariable<>("Month", Integer.class);
    private final NodeVariable<Integer> day = new NodeVariable<>("Day", Integer.class);
    private final NodeVariable<Integer> hour = new NodeVariable<>("Hour", Integer.class);
    private final NodeVariable<Integer> minute = new NodeVariable<>("Minute", Integer.class);
    private final NodeVariable<Integer> second = new NodeVariable<>("Second", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Long source = millis.getValue();
        ZonedDateTime dateTime = Instant.ofEpochMilli(source == null ? 0L : source).atZone(ZoneId.systemDefault());
        year.setValue(dateTime.getYear());
        month.setValue(dateTime.getMonthValue());
        day.setValue(dateTime.getDayOfMonth());
        hour.setValue(dateTime.getHour());
        minute.setValue(dateTime.getMinute());
        second.setValue(dateTime.getSecond());
    }

    @Override
    public void configureInputs() {
        addInput(millis);
    }

    @Override
    public void configureOutputs() {
        addOutput(year);
        addOutput(month);
        addOutput(day);
        addOutput(hour);
        addOutput(minute);
        addOutput(second);
    }
}
