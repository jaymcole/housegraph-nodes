package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.plugins.database.Criterion;
import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What <b>Find Rows</b>, <b>Update Rows</b> and <b>Delete Rows</b> have in common: a database, a
 * table, and a set of conditions that grows as you wire it.
 * <p>
 * <b>Conditions grow with the wiring.</b> Fill in <b>Column 1</b> and wire <b>Value 1</b> and a
 * fresh empty condition appears below — the growing <b>Build Map</b> does, for the same reason: the
 * node fits what you feed it instead of making you pick a size up front. Growth is driven by wiring
 * and by typed column names, because the graph offers a node no hook for "someone typed in a field",
 * and a typed column must not be shrunk away by a rebuild some other condition triggered.
 * <p>
 * <b>The condition ports survive a rebuild.</b> They are created once and kept; a shrink stops
 * publishing the tail rather than destroying it, so nothing a person typed is lost when the node's
 * ports are rebuilt — and if the node grows again, what they typed is still there.
 * <p>
 * A subclass decides where the conditions sit among its own ports (see {@link #addConditionInputs}),
 * and what an empty set of them means: {@link FindRowsNode} reads the whole table, while the two
 * that change data refuse it (see {@link Inputs#requireConditions}).
 */
abstract class ConditionsNode extends BaseNode {

    /** Conditions shown before anything is filled in: one spare. */
    private static final int MINIMUM_CONDITIONS = 1;

    /** A ceiling on growth, so a pathological graph can't grow the node without bound. */
    private static final int MAXIMUM_CONDITIONS = 16;

    protected final NodeVariable<Database> databaseInput =
            new NodeVariable<>("Database", Database.class).transientValue().required();
    protected final NodeVariable<String> tableInput = new NodeVariable<>("Table", String.class, true).required();

    private final List<NodeVariable<String>> columns = new ArrayList<>();
    private final List<NodeVariable<String>> tests = new ArrayList<>();
    private final List<NodeVariable<Object>> values = new ArrayList<>();

    /** The current number of conditions; persisted, so a loaded node has its ports back. */
    private int conditions = MINIMUM_CONDITIONS;

    /** Guards against reacting to the edge churn our own {@link #rebuildPorts()} triggers. */
    private boolean refreshing;

    /** The wired database, or a failure naming this node. */
    protected Database database() {
        return Inputs.requireDatabase(databaseInput.getValue(), getName());
    }

    /** The table name, trimmed, or a failure naming this node. */
    protected String table() {
        return Inputs.requireTable(tableInput.getValue(), getName());
    }

    /**
     * The filled-in conditions, in port order. A condition with no column is an unfilled spare and is
     * skipped; {@link Criterion#of} is what refuses the half-filled one that names a column but has
     * nothing wired into its value.
     */
    protected List<Criterion> criteria() {
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

    /** Adds the condition ports, from a subclass's {@code configureInputs} at the position it wants. */
    protected void addConditionInputs() {
        for (int i = columns.size(); i < conditions; i++) {
            int number = i + 1;
            columns.add(new NodeVariable<>("Column " + number, String.class, true));
            tests.add(new NodeVariable<>("Test " + number, String.class, true));
            values.add(new NodeVariable<>("Value " + number, Object.class));
        }
        for (int i = 0; i < conditions; i++) {
            addInput(columns.get(i));
            addInput(tests.get(i));
            addInput(values.get(i));
        }
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

    /** The zero-based index of the last condition with anything in it, or -1 for none. */
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
