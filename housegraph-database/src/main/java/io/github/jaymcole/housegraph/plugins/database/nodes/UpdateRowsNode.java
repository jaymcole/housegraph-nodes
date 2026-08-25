package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.database.Criterion;
import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Rows;

import java.util.List;
import java.util.Map;

/**
 * Changes columns on the rows of a table that match some conditions, and reports how many changed.
 * <b>Set</b> is a map — build it with <b>Build Map</b>, exactly as you build a row for <b>Insert
 * Row</b> — and each key names the column to change.
 * <p>
 * <b>A column that doesn't exist yet is created</b>, the same inference <b>Insert Row</b> performs,
 * so "record who last did this chore" works on a table that never had a {@code who} column. The rows
 * this update doesn't match keep having no value there.
 * <p>
 * <b>At least one condition is required</b>, for the reason <b>Delete Rows</b> gives: overwriting
 * every row by accident is as unrecoverable as deleting them. To mean every row, say so with a
 * condition that matches everything — {@code id > 0}.
 * <p>
 * <b>A null value changes nothing.</b> An unwired input must not be able to overwrite stored data
 * with a null, which is the rule <b>Stored Value</b> follows for the same reason. Set a column to
 * empty text to clear it — that is what <b>is empty</b> matches anyway.
 * <p>
 * <b>Updated and None are the outcome of this one firing</b>, and exactly one fires. <b>Being pulled
 * for data changes nothing</b>; a table that doesn't exist yet updates nothing and is not an error.
 */
@Display.Name("Update Rows")
@Display.Description("Changes columns on the rows of a table matching some conditions.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"update", "change", "set", "edit", "modify", "rows", "records", "database", "table", "where"})
@Node.Type("database.UpdateRowsNode")
public class UpdateRowsNode extends ConditionsNode {

    private final NodeVariable<Map<?, ?>> setInput = new NodeVariable<>("Set", Rows.ROW_TYPE).required();

    private final NodeVariable<Integer> updatedCount = new NodeVariable<>("Updated", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort updated = new FlowPort("Updated", FlowPort.Direction.OUT);
    private final FlowPort none = new FlowPort("None", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        if (!ctx.wasTriggeredVia(in)) {
            // A pull, not a firing: leave the last count published and change nothing.
            return;
        }
        String table = table();
        int changed = update(database(), table,
                Inputs.requireConditions(criteria(), getName(), "change", table), setInput.getValue());
        activate(changed == 0 ? none : updated);
    }

    /** Updates and publishes the count. Package-private for the reason {@link DeleteRowsNode#delete} is. */
    int update(Database database, String table, List<Criterion> criteria, Map<?, ?> values) {
        int changed = database.update(table, criteria, values);
        updatedCount.setValue(changed);
        return changed;
    }

    @Override
    public void configureInputs() {
        addInput(databaseInput);
        addInput(tableInput);
        addInput(setInput);
        addConditionInputs();
    }

    @Override
    public void configureOutputs() {
        addOutput(updatedCount);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(updated);
        addFlowOutput(none);
    }
}
