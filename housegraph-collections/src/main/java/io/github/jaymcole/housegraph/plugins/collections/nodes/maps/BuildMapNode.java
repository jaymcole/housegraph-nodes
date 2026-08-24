package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers separately-authored key/value pairs into one map, in slot order. Type a <b>Key 1</b> and
 * wire something into <b>Value 1</b>, and a fresh empty pair appears below it — the same growing
 * behaviour {@code BuildListNode} uses, for the same reason: the node grows to fit what you feed it
 * instead of making you pick a size up front.
 * <p>
 * The growing logic, the slot-count persistence and the guard against reacting to the node's own
 * rebuild are all identical to {@code BuildListNode}; see that class for why each part exists. The
 * one difference is what counts as "wired": a slot here is kept once either its Key or its Value
 * has something in it, since a key can be typed without ever wiring the value (or the reverse).
 * <p>
 * <b>A slot with no key contributes nothing</b> — a value with nothing to name it cannot become a
 * map entry. Where two slots' keys match ({@link Lists#sameValue forgivingly}), the later slot's
 * value wins, the same way a real map literal would if you wrote the same key twice.
 */
@Display.Name("Build Map")
@Display.Description("Collects separately authored key/value pairs into one map.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"build", "map", "make", "create", "dictionary", "pairs", "key", "value"})
@Node.Type("collections.BuildMapNode")
public class BuildMapNode extends BaseNode {

    /** Slots shown before anything is wired: one to fill, one spare. */
    private static final int MINIMUM_SLOTS = 2;

    /** A ceiling on growth, so a pathological graph can't grow the node without bound. */
    private static final int MAXIMUM_SLOTS = 64;

    private final List<NodeVariable<String>> keys = new ArrayList<>();
    private final List<NodeVariable<Object>> values = new ArrayList<>();
    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);

    /** The current number of key/value slots; persisted, so a loaded node has its ports back. */
    private int slots = MINIMUM_SLOTS;

    /** Guards against reacting to the edge churn our own {@link #rebuildPorts()} triggers. */
    private boolean refreshing;

    @Override
    public void process(ProcessContext ctx) {
        Map<Object, Object> entries = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i).getValue();
            if (key == null) {
                continue;
            }
            entries.put(key, values.get(i).getValue());
        }
        result.setValue(Maps.frozen(entries));
    }

    @Override
    public void configureInputs() {
        keys.clear();
        values.clear();
        for (int i = 1; i <= slots; i++) {
            NodeVariable<String> key = new NodeVariable<>("Key " + i, String.class, true);
            NodeVariable<Object> value = new NodeVariable<>("Value " + i, Object.class);
            keys.add(key);
            values.add(value);
            addInput(key);
            addInput(value);
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

    /** The zero-based index of the last slot with something wired into its Key or Value, or -1 for none. */
    private int highestWiredSlot() {
        int highest = -1;
        for (Edge edge : getIncomingDataEdges()) {
            int keyIndex = keys.indexOf(edge.getTargetVariable());
            int valueIndex = values.indexOf(edge.getTargetVariable());
            highest = Math.max(highest, Math.max(keyIndex, valueIndex));
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
