package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives a node the way the engine would, from outside: set inputs by port name, run
 * {@code process()} with a context that is never cancelled and carries no flow arrival, read
 * outputs by port name. No {@code NodeGraph} is involved, which is the point — nearly every node in
 * this library is a pure function of its inputs, so its whole contract is reachable this way.
 * <p>
 * It lives in the {@code nodes} package rather than in one of the three category subpackages
 * below it because all three drive their nodes the same way; a copy per subpackage is three
 * chances for the three to drift.
 * <p>
 * Going through the <em>ports</em> rather than the fields is deliberate: a port's name is part of
 * a node's saved-graph-visible surface (edges and values are matched by name on load), so a test
 * that names ports is a test that notices when one is renamed.
 */
public final class Nodes {

    private Nodes() {
    }

    /** Runs the node with nothing cancelled and no flow behind it - the pull path. */
    public static void run(BaseNode node) {
        node.process(ProcessContext.uncancelled());
    }

    /** Sets an input by port name, failing the test if the node has no such input. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void set(BaseNode node, String port, Object value) {
        for (NodeVariable variable : node.getInputs()) {
            if (variable.name.equals(port)) {
                variable.setValue(value);
                return;
            }
        }
        throw new AssertionError(node.getName() + " has no input named \"" + port + "\"");
    }

    /** Reads an output by port name, failing the test if the node has no such output. */
    @SuppressWarnings("rawtypes")
    public static Object get(BaseNode node, String port) {
        for (NodeVariable variable : node.getOutputs()) {
            if (variable.name.equals(port)) {
                return variable.getValue();
            }
        }
        throw new AssertionError(node.getName() + " has no output named \"" + port + "\"");
    }

    /** Reads a list-typed output by port name. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(BaseNode node, String port) {
        return (List<Object>) get(node, port);
    }

    /** Reads a map-typed output by port name. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(BaseNode node, String port) {
        return (Map<String, Object>) get(node, port);
    }

    /** Reads a set-typed output by port name. */
    @SuppressWarnings("unchecked")
    public static Set<Object> set(BaseNode node, String port) {
        return (Set<Object>) get(node, port);
    }

    /** The names of a node's input ports, in order. */
    public static List<String> inputNames(BaseNode node) {
        return namesOf(node.getInputs());
    }

    /** The names of a node's output ports, in order. */
    public static List<String> outputNames(BaseNode node) {
        return namesOf(node.getOutputs());
    }

    /** Written as a loop rather than a stream because the API hands back a raw {@code NodeVariable} list. */
    @SuppressWarnings("rawtypes")
    private static List<String> namesOf(List<NodeVariable> variables) {
        List<String> names = new java.util.ArrayList<>();
        for (NodeVariable variable : variables) {
            names.add(variable.name);
        }
        return names;
    }
}
