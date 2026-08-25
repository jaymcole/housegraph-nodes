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
import java.util.Map;

/**
 * Appends one row to a table. Wire a <b>Database</b> node in, name the <b>Table</b>, and build the
 * row with <b>Build Map</b> — each key becomes a column.
 * <p>
 * <b>There is no schema to set up.</b> The table is created on the first insert, and a key the table
 * has not seen becomes a new column as part of the same write. Adding a field to the map later — a
 * humidity reading next to the temperature you have been logging for a month — needs no migration
 * and no downtime: the rows already stored simply have no value there, and read back without that
 * entry. See {@link Database} for why that is affordable rather than reckless, and
 * {@code docs/design/local-database-storage.md} for the schema changes it deliberately does not do.
 * <p>
 * <b>Every row gets an Id and a created_at.</b> <b>Id</b> is the row's primary key, published here so
 * a later node can address exactly this row; ids are never reused, so one you stored last month
 * still means the row it meant then. {@code created_at} is filled in with the current time in
 * milliseconds unless the row supplies its own value for it.
 * <p>
 * <b>Columns Added</b> reports how many columns this insert had to create — normally zero. It is
 * worth branching on or logging when a graph writes rows built from something outside it (a webhook
 * body, a chat message): a column count that keeps creeping up is a typo'd key quietly becoming its
 * own column, and that is the one failure mode inferred columns have.
 * <p>
 * <b>Being pulled for data writes nothing.</b> Nothing is inserted unless flow arrives at this
 * node's flow input — a downstream node resolving <b>Id</b> reads the last one and stops. That is
 * the same rule <b>Stored Value</b> follows and here it is load-bearing rather than tidy: a node
 * that inserted whenever something read its output would append a row for every downstream reader,
 * silently, forever.
 * <p>
 * <b>A blank key or a null value is skipped</b>, not stored — the trailing empty pair of a
 * half-built <b>Build Map</b> must not put a mystery column in the table.
 */
@Display.Name("Insert Row")
@Display.Description("Appends a row to a table, creating the table and any new columns as it goes.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"insert", "add", "append", "row", "record", "database", "table", "write", "save", "log"})
@Node.Type("database.InsertRowNode")
public class InsertRowNode extends BaseNode {

    private final NodeVariable<Database> databaseInput =
            new NodeVariable<>("Database", Database.class).transientValue().required();
    private final NodeVariable<String> tableInput = new NodeVariable<>("Table", String.class, true).required();
    private final NodeVariable<Map<?, ?>> rowInput = new NodeVariable<>("Row", Rows.ROW_TYPE).required();

    private final NodeVariable<Long> id = new NodeVariable<>("Id", Long.class);
    private final NodeVariable<Integer> columnsAdded = new NodeVariable<>("Columns Added", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        if (!ctx.wasTriggeredVia(in)) {
            // A pull, not a firing. Leave the outputs holding the last insert's answer rather than
            // clearing them: a downstream node reading Id right after this node ran wants that id.
            return;
        }
        insert(Inputs.requireDatabase(databaseInput.getValue(), getName()),
                Inputs.requireTable(tableInput.getValue(), getName()),
                rowInput.getValue());
    }

    /**
     * Writes one row and publishes what it wrote. Package-private so a test can exercise it without
     * a live {@code NodeGraph}: only the engine can build the {@code ProcessContext} that carries
     * "which flow port fired", so the gate in {@link #process} is observable only in a running graph
     * — but what it gates is testable here, which is the same split
     * {@code StoredValueNodeTest} makes.
     */
    void insert(Database database, String table, Map<?, ?> row) {
        // Read the column count before and after rather than having Database report it: the number
        // that means something is "columns this table did not have", which is a property of the
        // table rather than of the write.
        int before = database.hasTable(table) ? database.columns(table).size() : 0;
        long written = database.insert(table, row);
        List<String> after = database.columns(table);

        id.setValue(written);
        columnsAdded.setValue(Math.max(0, after.size() - before));
    }

    @Override
    public void configureInputs() {
        addInput(databaseInput);
        addInput(tableInput);
        addInput(rowInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(id);
        addOutput(columnsAdded);
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
