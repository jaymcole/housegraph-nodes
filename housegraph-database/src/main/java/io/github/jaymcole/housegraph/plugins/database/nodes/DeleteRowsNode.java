package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.database.Criterion;
import io.github.jaymcole.housegraph.plugins.database.Database;

import java.util.List;

/**
 * Removes the rows of a table that match some conditions, and reports how many went. Conditions work
 * exactly as they do on <b>Find Rows</b> — try them there first, on the same table with the same
 * conditions, and delete once the list is the one you meant.
 * <p>
 * <b>At least one condition is required.</b> SQL reads a missing {@code WHERE} as "every row", which
 * is the correct reading and the wrong default for a node that a timer might fire at three in the
 * morning against six months of history, with no undo. To mean every row, say so deliberately with a
 * condition that matches everything — {@code id > 0}.
 * <p>
 * <b>Deleted and None are the outcome of this one firing</b>, and exactly one fires: <b>None</b> when
 * nothing matched, which is a useful branch in its own right ("there was no chore by that name").
 * <b>Deleted</b> also comes out as a count.
 * <p>
 * <b>Being pulled for data deletes nothing</b> — nothing happens unless flow arrives, for the reason
 * <b>Insert Row</b> spells out. A table that doesn't exist yet deletes nothing and is not an error.
 */
@Display.Name("Delete Rows")
@Display.Description("Removes the rows of a table matching some conditions.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"delete", "remove", "drop", "rows", "records", "database", "table", "where", "clear", "purge"})
@Node.Type("database.DeleteRowsNode")
public class DeleteRowsNode extends ConditionsNode {

    private final NodeVariable<Integer> deletedCount = new NodeVariable<>("Deleted", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort deleted = new FlowPort("Deleted", FlowPort.Direction.OUT);
    private final FlowPort none = new FlowPort("None", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        if (!ctx.wasTriggeredVia(in)) {
            // A pull, not a firing: leave the last count published and change nothing.
            return;
        }
        String table = table();
        int removed = delete(database(), table,
                Inputs.requireConditions(criteria(), getName(), "delete", table));
        activate(removed == 0 ? none : deleted);
    }

    /**
     * Deletes and publishes the count. Package-private so a test can exercise it without a live
     * {@code NodeGraph} — only the engine can build the {@code ProcessContext} the gate above reads.
     */
    int delete(Database database, String table, List<Criterion> criteria) {
        int removed = database.delete(table, criteria);
        deletedCount.setValue(removed);
        return removed;
    }

    @Override
    public void configureInputs() {
        addInput(databaseInput);
        addInput(tableInput);
        addConditionInputs();
    }

    @Override
    public void configureOutputs() {
        addOutput(deletedCount);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(deleted);
        addFlowOutput(none);
    }
}
