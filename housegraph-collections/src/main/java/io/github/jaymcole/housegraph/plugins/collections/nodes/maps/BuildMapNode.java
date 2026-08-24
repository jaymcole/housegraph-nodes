package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers separately-wired key/value pairs into one map, in port order. Wire something into
 * <b>Value 1</b> and a fresh empty pair appears below it, so the node grows to fit what you feed
 * it instead of making you pick a size up front — the same growing that <b>Build List</b> does,
 * and the same reason. Growth is driven by <em>wiring</em>, since the graph gives a node no hook
 * for "someone typed in a field"; typing a key alone will not open the next pair, but wiring its
 * value will, which is the order these get filled in anyway.
 * <p>
 * <b>Keys are text fields</b>, because on the canvas a key is something you type and only
 * {@code String}, {@code Integer} and {@code Float} have registered value editors. <b>Values are
 * {@code Object}</b>, because a value is nearly always something another node produced and an
 * {@code Object} input accepts every type there is. That is the same split <b>Append Item</b>
 * explains at more length.
 * <p>
 * <b>A half-filled pair contributes nothing</b> — the trailing spare never puts an entry in your
 * map, and neither does a key you typed but never wired a value to (see {@link Maps#put}). If a
 * key repeats across two pairs, the later one wins, which is what {@code put} means everywhere
 * else. <b>Count</b> reports how many pairs actually landed, so a graph can tell "I wired four
 * pairs" from "four pairs went in".
 */
@Display.Name("Build Map")
@Display.Description("Collects separately wired key/value pairs into one map.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"build", "map", "make", "create", "dictionary", "lookup", "table", "key", "value", "pairs"})
@Node.Type("collections.BuildMapNode")
public class BuildMapNode extends BaseNode {

    /** Pairs shown before anything is filled in: one to use, one spare. */
    private static final int MINIMUM_PAIRS = 2;

    /** A ceiling on growth, so a pathological graph can't grow the node without bound. */
    private static final int MAXIMUM_PAIRS = 64;

    private final List<NodeVariable<String>> keys = new ArrayList<>();
    private final List<NodeVariable<Object>> values = new ArrayList<>();

    private final NodeVariable<Map<?, ?>> result = new NodeVariable<>("Map", Maps.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    /** The current number of key/value pairs; persisted, so a loaded node has its ports back. */
    private int pairs = MINIMUM_PAIRS;

    /** Guards against reacting to the edge churn our own {@link #rebuildPorts()} triggers. */
    private boolean refreshing;

    @Override
    public void process(ProcessContext ctx) {
        // Driven by the port fields rather than by `pairs`: the two agree once the node has been
        // configured, but configuration is lazy (BaseNode defers it to first port access), and a
        // count that ran ahead of the fields would index past the end of an unconfigured node.
        Map<String, Object> entries = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            Maps.put(entries, keys.get(i).getValue(), values.get(i).getValue());
        }
        result.setValue(Maps.frozen(entries));
        count.setValue(entries.size());
    }

    @Override
    public void configureInputs() {
        keys.clear();
        values.clear();
        for (int i = 1; i <= pairs; i++) {
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
        addOutput(count);
    }

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        refreshPairs();
    }

    @Override
    protected void onInputEdgeRemoved(Edge edge) {
        refreshPairs();
    }

    /**
     * Grows or shrinks the node to "every used pair, plus one spare". Read from the live wiring and
     * the live field values rather than from the hook's edge argument, so it stays correct however
     * the hooks interleave with the rebuild they cause.
     */
    private void refreshPairs() {
        if (refreshing) {
            return;
        }
        int desired = Math.min(MAXIMUM_PAIRS, Math.max(MINIMUM_PAIRS, highestUsedPair() + 2));
        if (desired == pairs) {
            return;
        }
        pairs = desired;
        refreshing = true;
        try {
            rebuildPorts();
        } finally {
            refreshing = false;
        }
    }

    /**
     * The zero-based index of the last pair with anything in it, or -1 for none. Read from the
     * live wiring <em>and</em> the typed Key fields: only the wiring can grow the node (there is no
     * hook for typing), but counting a typed key is what stops a rebuild triggered by some other
     * pair's edge from shrinking the node out from under a key someone had already filled in.
     */
    private int highestUsedPair() {
        int highest = -1;
        for (Edge edge : getIncomingDataEdges()) {
            highest = Math.max(highest, Math.max(
                    keys.indexOf(edge.getTargetVariable()),
                    values.indexOf(edge.getTargetVariable())));
        }
        for (int i = 0; i < keys.size(); i++) {
            if (Maps.key(keys.get(i).getValue()) != null) {
                highest = Math.max(highest, i);
            }
        }
        return highest;
    }

    @Override
    public Map<String, String> saveState() {
        if (pairs == MINIMUM_PAIRS) {
            return Map.of();
        }
        Map<String, String> state = new HashMap<>();
        state.put("pairs", String.valueOf(pairs));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        pairs = parsePairs(state.get("pairs"));
    }

    /** Reads a persisted pair count, clamped to the node's own bounds; anything unreadable is the minimum. */
    private static int parsePairs(String text) {
        if (text == null) {
            return MINIMUM_PAIRS;
        }
        try {
            return Math.min(MAXIMUM_PAIRS, Math.max(MINIMUM_PAIRS, Integer.parseInt(text.trim())));
        } catch (NumberFormatException e) {
            return MINIMUM_PAIRS;
        }
    }
}
