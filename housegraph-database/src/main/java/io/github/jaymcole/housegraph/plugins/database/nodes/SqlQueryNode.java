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
 * Runs a query you write yourself and hands back its rows. This is the escape hatch, not the front
 * door: <b>Find Rows</b> covers what a house's data usually needs, and reaches for no SQL knowledge
 * at all. Come here for the rest — a join across two tables, a {@code GROUP BY}, a
 * {@code SELECT COUNT(*)} that shouldn't drag every row into the graph to be counted.
 * <p>
 * <b>Values go in through Params, never into the text.</b> Write {@code ?} where a value belongs and
 * wire the values in as a list (<b>Build List</b> makes one), in the same order. This is not
 * ceremony: the tables in a house graph get filled from chat messages and webhook bodies, and text
 * pasted into a statement is how a message ends up being executed instead of stored. A bound value
 * cannot change what the statement does, whatever it contains.
 * <pre>
 *   SELECT name, COUNT(*) AS times FROM chores WHERE who = ? GROUP BY name ORDER BY times DESC
 * </pre>
 * <b>Rows is a list of maps</b>, exactly as <b>Find Rows</b> produces, so the collections library
 * works on it and computed columns come through under whatever you named them ({@code times} above).
 * <p>
 * <b>One statement.</b> <b>Found</b> and <b>None</b> are this query's outcome and exactly one fires.
 * <b>Being pulled for data runs the query</b>; use <b>SQL Statement</b> for anything that changes
 * something.
 */
@Display.Name("SQL Query")
@Display.Description("Runs a SELECT you write yourself, with values bound safely, and returns the rows.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"sql", "query", "select", "join", "group", "database", "rows", "advanced", "raw"})
@Node.Type("database.SqlQueryNode")
public class SqlQueryNode extends BaseNode {

    private final NodeVariable<Database> databaseInput =
            new NodeVariable<>("Database", Database.class).transientValue().required();
    private final NodeVariable<String> sqlInput = new NodeVariable<>("SQL", String.class, true).required();
    private final NodeVariable<List<?>> paramsInput = new NodeVariable<>("Params", Rows.ROWS_TYPE);

    private final NodeVariable<List<?>> rows = new NodeVariable<>("Rows", Rows.ROWS_TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> foundValue = new NodeVariable<>("Found", Boolean.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort found = new FlowPort("Found", FlowPort.Direction.OUT);
    private final FlowPort none = new FlowPort("None", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        List<Map<String, Object>> matches = Inputs.requireDatabase(databaseInput.getValue(), getName())
                .query(sqlInput.getValue(), Inputs.parameters(paramsInput.getValue()));

        rows.setValue(List.copyOf(matches));
        count.setValue(matches.size());
        foundValue.setValue(!matches.isEmpty());
        activate(matches.isEmpty() ? none : found);
    }

    @Override
    public void configureInputs() {
        addInput(databaseInput);
        addInput(sqlInput);
        addInput(paramsInput);
    }

    @Override
    public void configureOutputs() {
        addOutput(rows);
        addOutput(count);
        addOutput(foundValue);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(found);
        addFlowOutput(none);
    }
}
