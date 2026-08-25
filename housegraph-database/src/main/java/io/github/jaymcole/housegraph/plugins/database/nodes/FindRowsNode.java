package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.database.Criterion;
import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Match;
import io.github.jaymcole.housegraph.plugins.database.Rows;
import io.github.jaymcole.housegraph.plugins.database.Sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the rows of a table that match some conditions. <b>Rows</b> is a list of maps — one map per
 * row, keyed by column name — so every map and list node in {@code housegraph-collections} works on
 * it directly: <b>Get Item</b> for the first match, <b>Map Get</b> for one column, <b>List Count</b>,
 * <b>Format Each</b>.
 * <p>
 * <b>Conditions grow as you wire them.</b> Fill in <b>Column 1</b> and wire <b>Value 1</b> and a
 * fresh empty condition appears below, the same growing <b>Build Map</b> does and for the same
 * reason — the node fits what you feed it instead of making you pick a size up front. Conditions are
 * ANDed together. <b>Test</b> is typed as a symbol or a phrase ({@code =}, {@code >=},
 * {@code contains}, {@code starts with}, {@code is empty}); see {@link Match} for the full list, for
 * why equality here also matches rows written before that column existed, and for the one place
 * comparing a number against text does not do what it looks like.
 * <p>
 * <b>A condition with a column but no value is an error, not a skipped condition.</b> Dropping it
 * would widen the query instead of narrowing it — the difference between "the chore named X" and
 * "every chore" — and the failure would be a wrong answer rather than a message. A condition with no
 * column at all is just an unfilled spare and is ignored.
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
public class FindRowsNode extends BaseNode {

    /** Conditions shown before anything is filled in: one spare. A query with none is legitimate. */
    private static final int MINIMUM_CONDITIONS = 1;

    /** A ceiling on growth, so a pathological graph can't grow the node without bound. */
    private static final int MAXIMUM_CONDITIONS = 16;

    private final NodeVariable<Database> databaseInput =
            new NodeVariable<>("Database", Database.class).transientValue().required();
    private final NodeVariable<String> tableInput = new NodeVariable<>("Table", String.class, true).required();

    /**
     * The condition ports, kept across rebuilds rather than recreated. {@code configureInputs} only
     * publishes the first {@code conditions} of them, so shrinking hides ports instead of destroying
     * them — which means a rebuild triggered by one condition's edge cannot wipe the column name
     * someone typed into another.
     */
    private final List<NodeVariable<String>> columns = new ArrayList<>();
    private final List<NodeVariable<String>> tests = new ArrayList<>();
    private final List<NodeVariable<Object>> values = new ArrayList<>();

    private final NodeVariable<String> sortInput = new NodeVariable<>("Sort", String.class, true);
    private final NodeVariable<Integer> limitInput = new NodeVariable<>("Limit", Integer.class, true);

    private final NodeVariable<List<?>> rows = new NodeVariable<>("Rows", Rows.ROWS_TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Boolean> foundValue = new NodeVariable<>("Found", Boolean.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort found = new FlowPort("Found", FlowPort.Direction.OUT);
    private final FlowPort none = new FlowPort("None", FlowPort.Direction.OUT);

    /** The current number of conditions; persisted, so a loaded node has its ports back. */
    private int conditions = MINIMUM_CONDITIONS;

    /** Guards against reacting to the edge churn our own {@link #rebuildPorts()} triggers. */
    private boolean refreshing;

    public FindRowsNode() {
        limitInput.setValue(0);
    }

    @Override
    public void process(ProcessContext ctx) {
        Database database = Inputs.requireDatabase(databaseInput.getValue(), getName());
        String table = Inputs.requireTable(tableInput.getValue(), getName());

        List<Map<String, Object>> matches = database.find(
                table, criteria(), Sort.parse(sortInput.getValue()), ctx.get(limitInput, 0));

        rows.setValue(List.copyOf(matches));
        count.setValue(matches.size());
        foundValue.setValue(!matches.isEmpty());
        activate(matches.isEmpty() ? none : found);
    }

    /**
     * The filled-in conditions, in port order. A condition with no column is an unfilled spare and is
     * skipped here; {@link Criterion#of} is what refuses the half-filled one that names a column but
     * has no value.
     */
    private List<Criterion> criteria() {
        List<Criterion> criteria = new ArrayList<>();
        for (int i = 0; i < conditions && i < columns.size(); i++) {
            String column = columns.get(i).getValue();
            if (column == null || column.isBlank()) {
                continue;
            }
            criteria.add(Criterion.of(column, Match.parse(tests.get(i).getValue()), values.get(i).getValue()));
        }
        return criteria;
    }

    @Override
    public void configureInputs() {
        addInput(databaseInput);
        addInput(tableInput);
        growTo(conditions);
        for (int i = 0; i < conditions; i++) {
            addInput(columns.get(i));
            addInput(tests.get(i));
            addInput(values.get(i));
        }
        addInput(sortInput);
        addInput(limitInput);
    }

    /** Creates condition ports up to {@code wanted}, reusing any that already exist. */
    private void growTo(int wanted) {
        for (int i = columns.size(); i < wanted; i++) {
            int number = i + 1;
            columns.add(new NodeVariable<>("Column " + number, String.class, true));
            tests.add(new NodeVariable<>("Test " + number, String.class, true));
            values.add(new NodeVariable<>("Value " + number, Object.class));
        }
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

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        refreshConditions();
    }

    @Override
    protected void onInputEdgeRemoved(Edge edge) {
        refreshConditions();
    }

    /**
     * Grows or shrinks the node to "every used condition, plus one spare". Read from the live wiring
     * and the live field values rather than from the hook's edge argument, so it stays correct
     * however the hooks interleave with the rebuild they cause.
     */
    private void refreshConditions() {
        if (refreshing) {
            return;
        }
        int desired = Math.min(MAXIMUM_CONDITIONS, Math.max(MINIMUM_CONDITIONS, highestUsedCondition() + 2));
        if (desired == conditions) {
            return;
        }
        conditions = desired;
        refreshing = true;
        try {
            rebuildPorts();
        } finally {
            refreshing = false;
        }
    }

    /**
     * The zero-based index of the last condition with anything in it, or -1 for none. Read from the
     * live wiring <em>and</em> the typed Column fields: only the wiring can grow the node (there is
     * no hook for typing), but counting a typed column is what stops a rebuild triggered by some
     * other condition's edge from shrinking the node out from under one someone had filled in.
     */
    private int highestUsedCondition() {
        int highest = -1;
        for (Edge edge : getIncomingDataEdges()) {
            highest = Math.max(highest, Math.max(
                    columns.indexOf(edge.getTargetVariable()),
                    Math.max(tests.indexOf(edge.getTargetVariable()), values.indexOf(edge.getTargetVariable()))));
        }
        for (int i = 0; i < conditions && i < columns.size(); i++) {
            String column = columns.get(i).getValue();
            if (column != null && !column.isBlank()) {
                highest = Math.max(highest, i);
            }
        }
        return highest;
    }

    @Override
    public Map<String, String> saveState() {
        if (conditions == MINIMUM_CONDITIONS) {
            return Map.of();
        }
        Map<String, String> state = new HashMap<>();
        state.put("conditions", String.valueOf(conditions));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        conditions = parseConditions(state.get("conditions"));
    }

    /** Reads a persisted condition count, clamped to the node's own bounds; anything unreadable is the minimum. */
    private static int parseConditions(String text) {
        if (text == null) {
            return MINIMUM_CONDITIONS;
        }
        try {
            return Math.min(MAXIMUM_CONDITIONS, Math.max(MINIMUM_CONDITIONS, Integer.parseInt(text.trim())));
        } catch (NumberFormatException e) {
            return MINIMUM_CONDITIONS;
        }
    }
}
