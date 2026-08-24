package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The same driving helper the sibling {@code nodes} package test suite uses — see that package's
 * {@code Nodes} class for why it exists and why it goes through ports rather than fields. Not
 * shared directly because it is package-private there, and duplicating a dozen lines beats making
 * a test helper part of either package's public surface.
 */
final class Nodes {

    private Nodes() {
    }

    static void run(BaseNode node) {
        node.process(ProcessContext.uncancelled());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void set(BaseNode node, String port, Object value) {
        for (NodeVariable variable : node.getInputs()) {
            if (variable.name.equals(port)) {
                variable.setValue(value);
                return;
            }
        }
        throw new AssertionError(node.getName() + " has no input named \"" + port + "\"");
    }

    @SuppressWarnings("rawtypes")
    static Object get(BaseNode node, String port) {
        for (NodeVariable variable : node.getOutputs()) {
            if (variable.name.equals(port)) {
                return variable.getValue();
            }
        }
        throw new AssertionError(node.getName() + " has no output named \"" + port + "\"");
    }

    @SuppressWarnings("unchecked")
    static Set<Object> members(BaseNode node, String port) {
        return (Set<Object>) get(node, port);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(BaseNode node, String port) {
        return (List<Object>) get(node, port);
    }

    static List<String> inputNames(BaseNode node) {
        return namesOf(node.getInputs());
    }

    @SuppressWarnings("rawtypes")
    private static List<String> namesOf(List<NodeVariable> variables) {
        List<String> names = new ArrayList<>();
        for (NodeVariable variable : variables) {
            names.add(variable.name);
        }
        return names;
    }
}
