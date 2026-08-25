package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Rows;

import java.util.List;

/**
 * Runs one statement you write yourself that changes something, and reports how many rows it
 * changed. The writing counterpart to <b>SQL Query</b>, and the same escape hatch: <b>Insert
 * Row</b>, <b>Update Rows</b> and <b>Delete Rows</b> cover the ordinary cases and refuse the
 * dangerous ones. This refuses nothing.
 * <p>
 * <b>Values go in through Params, never into the text</b> — write {@code ?} and wire the values in
 * order, for the reason <b>SQL Query</b> gives at length.
 * <p>
 * <b>Nothing here protects you.</b> A {@code DELETE} with no {@code WHERE} empties the table, and
 * that is exactly why this node exists alongside <b>Delete Rows</b>, which will not: this is the
 * place to mean it. There is no undo. Take a copy first from the <b>Database</b> node's
 * <b>Columns…</b> window, which writes one before any schema change and will write one for you here
 * too.
 * <p>
 * <b>One statement per firing</b> — not a script. <b>Changed</b> is the number of rows affected, and
 * is 0 for a statement that changes the schema rather than rows.
 * <p>
 * <b>Being pulled for data changes nothing</b>: this runs only when flow arrives, for the reason
 * <b>Insert Row</b> spells out. A node that ran a {@code DELETE} every time something read its
 * output would be unusable.
 */
@Display.Name("SQL Statement")
@Display.Description("Runs one INSERT, UPDATE, DELETE or schema statement you write yourself.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"sql", "statement", "insert", "update", "delete", "alter", "create", "database", "advanced", "raw"})
@Node.Type("database.SqlStatementNode")
public class SqlStatementNode extends BaseNode {

    private final NodeVariable<Database> databaseInput =
            new NodeVariable<>("Database", Database.class).transientValue().required();
    private final NodeVariable<String> sqlInput = new NodeVariable<>("SQL", String.class, true).required();
    private final NodeVariable<List<?>> paramsInput = new NodeVariable<>("Params", Rows.ROWS_TYPE);

    private final NodeVariable<Integer> changed = new NodeVariable<>("Changed", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        if (!ctx.wasTriggeredVia(in)) {
            // A pull, not a firing: leave the last count published and change nothing.
            return;
        }
        run(Inputs.requireDatabase(databaseInput.getValue(), getName()),
                sqlInput.getValue(), Inputs.parameters(paramsInput.getValue()));
    }

    /** Runs the statement and publishes the count. Package-private for the reason {@code DeleteRowsNode#delete} is. */
    int run(Database database, String sql, List<Object> parameters) {
        int rows = database.execute(sql, parameters);
        changed.setValue(rows);
        return rows;
    }

    @Override
    public void configureInputs() {
        addInput(databaseInput);
        addInput(sqlInput);
        addInput(paramsInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(changed);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
