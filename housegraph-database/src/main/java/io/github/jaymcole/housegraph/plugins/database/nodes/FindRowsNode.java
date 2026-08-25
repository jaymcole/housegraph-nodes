package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.database.Match;
import io.github.jaymcole.housegraph.plugins.database.Rows;
import io.github.jaymcole.housegraph.plugins.database.Sort;

import java.util.List;
import java.util.Map;

/**
 * Reads the rows of a table that match some conditions. <b>Rows</b> is a list of maps — one map per
 * row, keyed by column name — so every map and list node in {@code housegraph-collections} works on
 * it directly: <b>Get Item</b> for the first match, <b>Map Get</b> for one column, <b>List Count</b>,
 * <b>Format Each</b>.
 * <p>
 * <b>Conditions grow as you wire them</b> and are ANDed together; see {@link ConditionsNode}.
 * <b>Test</b> is typed as a symbol or a phrase ({@code =}, {@code >=}, {@code contains},
 * {@code starts with}, {@code is empty}); see {@link Match} for the full list, for why equality here
 * also matches rows written before that column existed, and for the one place comparing a number
 * against text does not do what it looks like.
 * <p>
 * <b>No conditions reads the whole table</b> — unlike <b>Update Rows</b> and <b>Delete Rows</b>,
 * which refuse that, because reading everything is a slow answer and changing everything is a lost
 * one.
 * <p>
 * <b>Sort</b> is a column name, optionally followed by {@code desc} ({@code created_at desc} is the
 * common one). <b>Limit</b> caps the rows returned; 0 means all of them, which is the honest default
 * — a limit that quietly applied itself would return a confidently truncated answer, and a wrong
 * answer is worse than a slow one. Give it a limit when the table is large.
 * <p>
 * <b>Found and None are the outcome of this one query</b>, and exactly one of them fires: wire
 * <b>None</b> to the "nobody has claimed this chore yet" branch and <b>Found</b> to the other. Wire
 * both if you want something to run either way. There is also a <b>Found</b> data output for the
 * same question asked as a value.
 * <p>
 * <b>A table nobody has written to yet reads as no rows</b>, not as an error — on a graph's first
 * run that is the normal state. <b>Being pulled for data runs the query</b>, since reading changes
 * nothing.
 */
@Display.Name("Find Rows")
@Display.Description("Reads the rows of a table matching some conditions, as a list of maps.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"find", "query", "select", "search", "rows", "records", "database", "table", "where", "filter", "read"})
@Node.Type("database.FindRowsNode")
public class FindRowsNode extends ConditionsNode {

    private final NodeVariable<String> sortInput = new NodeVariable<>("Sort", String.class, true);
    private final NodeVariable<Integer> limitInput = new NodeVariable<>("Limit", Integer.class, true);

    private final NodeVariable<List<?>> rows = new NodeVariable<>("Rows", Rows.ROWS_TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> foundValue = new NodeVariable<>("Found", Boolean.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort found = new FlowPort("Found", FlowPort.Direction.OUT);
    private final FlowPort none = new FlowPort("None", FlowPort.Direction.OUT);

    public FindRowsNode() {
        limitInput.setValue(0);
    }

    @Override
    public void process(ProcessContext ctx) {
        List<Map<String, Object>> matches = database().find(
                table(), criteria(), Sort.parse(sortInput.getValue()), ctx.get(limitInput, 0));

        rows.setValue(List.copyOf(matches));
        count.setValue(matches.size());
        foundValue.setValue(!matches.isEmpty());
        activate(matches.isEmpty() ? none : found);
    }

    @Override
    public void configureInputs() {
        addInput(databaseInput);
        addInput(tableInput);
        addConditionInputs();
        addInput(sortInput);
        addInput(limitInput);
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
