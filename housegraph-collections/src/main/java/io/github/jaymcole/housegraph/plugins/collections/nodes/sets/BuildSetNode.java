package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathers separately-wired values into one set, in port order — the same growing behaviour
 * {@code BuildListNode} uses, for the same reason: wire something into <b>Item 1</b> and a fresh
 * empty slot appears below it. See that class for why the growing logic, the slot-count
 * persistence, and the guard against reacting to the node's own rebuild all work the way they do.
 * <p>
 * <b>Repeats collapse.</b> Where two slots' values are the same by {@link Lists#key text-form}
 * identity, only the first is kept — the same rule {@code DistinctListNode} and {@code Sets#fromList}
 * use — so wiring the same upstream value into two slots does not produce a two-member set.
 * <p>
 * <b>Unfilled slots contribute nothing</b>, the same as {@code BuildListNode}: the trailing spare
 * never puts a null in the set.
 */
@Display.Name("Build Set")
@Display.Description("Collects separately wired values into one set, dropping repeats.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"build", "set", "make", "create", "collect", "gather", "distinct", "unique"})
@Node.Type("collections.BuildSetNode")
public class BuildSetNode extends BaseNode {

    /** Slots shown before anything is wired: one to fill, one spare. */
    private static final int MINIMUM_SLOTS = 2;

    /** A ceiling on growth, so a pathological graph can't grow the node without bound. */
    private static final int MAXIMUM_SLOTS = 64;

    private final List<NodeVariable<Object>> items = new ArrayList<>();
    private final NodeVariable<Set<?>> result = new NodeVariable<>("Set", Sets.TYPE);

    /** The current number of item inputs; persisted, so a loaded node has its ports back. */
    private int slots = MINIMUM_SLOTS;

    /** Guards against reacting to the edge churn our own {@link #rebuildPorts()} triggers. */
    private boolean refreshing;

    @Override
    public void process(ProcessContext ctx) {
        HashSet<String> seenKeys = new HashSet<>();
        LinkedHashSet<Object> members = new LinkedHashSet<>();
        for (NodeVariable<Object> item : items) {
            Object value = item.getValue();
            if (value != null && seenKeys.add(Lists.key(value))) {
                members.add(value);
            }
        }
        result.setValue(Sets.frozen(members));
    }

    @Override
    public void configureInputs() {
        items.clear();
        for (int i = 1; i <= slots; i++) {
            NodeVariable<Object> item = new NodeVariable<>("Item " + i, Object.class);
            items.add(item);
            addInput(item);
        }
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        refreshSlots();
    }

    @Override
    protected void onInputEdgeRemoved(Edge edge) {
        refreshSlots();
    }

    /**
     * Grows or shrinks the node to "every wired slot, plus one spare". Read from the live wiring
     * rather than from the hook's edge argument, so it stays correct however the hooks interleave
     * with the rebuild they cause.
     */
    private void refreshSlots() {
        if (refreshing) {
            return;
        }
        int desired = Math.min(MAXIMUM_SLOTS, Math.max(MINIMUM_SLOTS, highestWiredSlot() + 2));
        if (desired == slots) {
            return;
        }
        slots = desired;
        refreshing = true;
        try {
            rebuildPorts();
        } finally {
            refreshing = false;
        }
    }

    /** The zero-based index of the last item input with something wired into it, or -1 for none. */
    private int highestWiredSlot() {
        int highest = -1;
        for (Edge edge : getIncomingDataEdges()) {
            int index = items.indexOf(edge.getTargetVariable());
            if (index > highest) {
                highest = index;
            }
        }
        return highest;
    }

    @Override
    public Map<String, String> saveState() {
        if (slots == MINIMUM_SLOTS) {
            return Map.of();
        }
        Map<String, String> state = new HashMap<>();
        state.put("slots", String.valueOf(slots));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        slots = parseSlots(state.get("slots"));
    }

    /** Reads a persisted slot count, clamped to the node's own bounds; anything unreadable is the minimum. */
    private static int parseSlots(String text) {
        if (text == null) {
            return MINIMUM_SLOTS;
        }
        try {
            return Math.min(MAXIMUM_SLOTS, Math.max(MINIMUM_SLOTS, Integer.parseInt(text.trim())));
        } catch (NumberFormatException e) {
            return MINIMUM_SLOTS;
        }
    }
}
