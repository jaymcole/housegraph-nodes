package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.util.List;

/**
 * Reading and writing a node's ports by name, for the node tests in this package. Lookup is by
 * display name and fails loudly when it finds nothing, so renaming a port without updating its test
 * is a clear error rather than a silent pass against a stale name. The same helper
 * {@code housegraph-store}'s node tests use.
 */
final class Nodes {

    private Nodes() {
    }

    /** Sets an input's value, as an upstream edge or a typed-in field would. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void set(BaseNode node, String name, Object value) {
        ((NodeVariable) find(node.getInputs(), name, node, "input")).setValue(value);
    }

    /** Reads an output's value, as a downstream node would. */
    @SuppressWarnings("unchecked")
    static <T> T get(BaseNode node, String name) {
        return (T) find(node.getOutputs(), name, node, "output").getValue();
    }

    /** Reads an input's current value — what a rebuild must not lose. */
    @SuppressWarnings("unchecked")
    static <T> T getInput(BaseNode node, String name) {
        return (T) find(node.getInputs(), name, node, "input").getValue();
    }

    /** Runs the node the way the engine does when something downstream pulls it. */
    static void run(BaseNode node) {
        node.process(ProcessContext.uncancelled());
    }

    /** Every input's name, in port order. */
    static List<String> inputNames(BaseNode node) {
        return node.getInputs().stream().map(variable -> variable.name).toList();
    }

    /** Every flow output's name, in port order. */
    static List<String> flowOutputNames(BaseNode node) {
        return node.getFlowOutputs().stream().map(port -> port.name).toList();
    }

    private static NodeVariable<?> find(List<NodeVariable> variables, String name, BaseNode node, String kind) {
        return variables.stream()
                .filter(variable -> variable.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(node.getClass().getSimpleName() + " has no " + kind + " named \""
                        + name + "\" - it has " + variables.stream().map(variable -> variable.name).toList()));
    }
}
