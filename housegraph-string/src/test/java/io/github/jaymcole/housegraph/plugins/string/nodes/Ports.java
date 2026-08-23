package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

import java.util.List;

/**
 * Reading and writing a node's ports by name, for the node tests in this package.
 * <p>
 * Every node here is a pure data node, so a test is just "set the inputs, call {@code process()},
 * read the outputs" — no {@code NodeGraph}, no flow, no JavaFX. This holds the port lookup those
 * three steps need, rather than each of fourteen test classes carrying its own copy of the same
 * stream-filter-orElseThrow.
 * <p>
 * Lookup is by the port's display name and fails loudly when it finds nothing, so renaming a port
 * without updating its test is a clear error rather than a silent pass against a stale name.
 */
final class Ports {

    private Ports() {
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

    // The typed readers below exist so an assertion reads as one line without a cast. They also
    // sidestep an overload trap: assertEquals(3, someInteger) is ambiguous between JUnit's
    // (int, int) and (Object, Object), and the fix at every call site would be noisier than this.

    /** Reads a text output. */
    static String textOf(BaseNode node, String name) {
        return get(node, name);
    }

    /** Reads a whole-number output. */
    static int intOf(BaseNode node, String name) {
        return (Integer) get(node, name);
    }

    /** Reads a decimal output. */
    static double doubleOf(BaseNode node, String name) {
        return (Double) get(node, name);
    }

    /** Reads a boolean output. */
    static boolean boolOf(BaseNode node, String name) {
        return (Boolean) get(node, name);
    }

    /** Reads a list output. */
    static List<?> listOf(BaseNode node, String name) {
        return get(node, name);
    }

    /** Runs the node the way the engine does when something downstream pulls it. */
    static void run(BaseNode node) {
        node.process(ProcessContext.uncancelled());
    }

    /** Every input's name, in port order. */
    static List<String> inputNames(BaseNode node) {
        return node.getInputs().stream().map(variable -> variable.name).toList();
    }

    /** Every output's name, in port order. */
    static List<String> outputNames(BaseNode node) {
        return node.getOutputs().stream().map(variable -> variable.name).toList();
    }

    private static NodeVariable<?> find(List<NodeVariable> variables, String name, BaseNode node, String kind) {
        return variables.stream()
                .filter(variable -> variable.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(node.getClass().getSimpleName() + " has no " + kind + " named \""
                        + name + "\" - it has " + variables.stream().map(variable -> variable.name).toList()));
    }
}
