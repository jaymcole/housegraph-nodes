package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers separately-wired values into one list, in port order. Wire something into <b>Item 1</b>
 * and a fresh empty slot appears below it, so the node grows to fit what you feed it instead of
 * making you pick a size up front.
 * <p>
 * The growing is the {@code ObjectDecomposerNode} pattern: {@link #onInputEdgeAdded} /
 * {@link #onInputEdgeRemoved} recompute the wanted slot count from the <em>settled</em> wiring and
 * {@link #rebuildPorts()} only when it actually changed, which keeps the recompute idempotent —
 * rebuilding briefly deletes and recreates the very edges that triggered it, so a version that
 * reacted to its own churn would loop. Slot count is persisted through
 * {@link #saveState()}/{@link #loadState(Map)} so the ports exist again on load before edges are
 * restored onto them.
 * <p>
 * <b>Unfilled slots contribute nothing</b> — the trailing spare never puts a null in your list.
 * The flip side is that a deliberately-null value is dropped too; use a Constant if you need a
 * placeholder entry.
 */
@Display.Name("Build List")
@Display.Description("Collects separately wired values into one list.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"build", "list", "make", "create", "collect", "gather", "combine", "items"})
@Node.Type("collections.BuildListNode")
public class BuildListNode extends BaseNode {

    /** Slots shown before anything is wired: one to fill, one spare. */
    private static final int MINIMUM_SLOTS = 2;

    /** A ceiling on growth, so a pathological graph can't grow the node without bound. */
    private static final int MAXIMUM_SLOTS = 64;

    private final List<NodeVariable<Object>> items = new ArrayList<>();
    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    /** The current number of item inputs; persisted, so a loaded node has its ports back. */
    private int slots = MINIMUM_SLOTS;

    /** Guards against reacting to the edge churn our own {@link #rebuildPorts()} triggers. */
    private boolean refreshing;

    @Override
    public void process(ProcessContext ctx) {
        List<Object> values = new ArrayList<>();
        for (NodeVariable<Object> item : items) {
            Object value = item.getValue();
            if (value != null) {
                values.add(value);
            }
        }
        result.setValue(Lists.frozen(values));
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
