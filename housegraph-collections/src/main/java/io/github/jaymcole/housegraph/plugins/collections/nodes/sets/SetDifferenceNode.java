package io.github.jaymcole.housegraph.plugins.collections.nodes.sets;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;
import io.github.jaymcole.housegraph.plugins.collections.Sets;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What is in one set and not the other — <b>both ways round</b>, because asking one way almost
 * always means wanting the other in the same breath.
 * <ul>
 *   <li><b>Only in A</b> — A's members that B does not hold. Wire yesterday's set into A and
 *       today's into B and this is <em>what went away</em>.</li>
 *   <li><b>Only in B</b> — B's members that A does not hold. Same wiring, and this is <em>what is
 *       new</em>.</li>
 * </ul>
 * That pairing is why this is one node with two outputs rather than a one-directional Difference
 * you place twice with the inputs crossed. Difference is the asymmetric operation of the three, and
 * a graph that wired it backwards would go on producing a plausible, wrong answer — showing both
 * directions, named, takes the trap away.
 * <p>
 * <b>Changed</b> is true when either side has anything in it, which is the whole of "did this set
 * change?" in one boolean, ready for the host's <b>If (Boolean)</b>. It is false exactly when the
 * two sets hold the same members.
 * <p>
 * Members keep their own set's order and objects. Either set may be unwired, which reads as empty.
 * Membership is compared by {@link Sets text form}, so a {@code 3} on one side cancels a
 * {@code "3"} on the other rather than showing up as a change.
 */
@Display.Name("Set Difference")
@Display.Description("What each set holds that the other doesn't, both ways round.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"difference", "set", "minus", "except", "without", "only in", "added", "removed", "changed", "compare"})
@Node.Type("collections.SetDifferenceNode")
public class SetDifferenceNode extends BaseNode {

    private final NodeVariable<Set<?>> a = new NodeVariable<>("A", Sets.TYPE);
    private final NodeVariable<Set<?>> b = new NodeVariable<>("B", Sets.TYPE);

    private final NodeVariable<Set<?>> onlyInA = new NodeVariable<>("Only in A", Sets.TYPE);
    private final NodeVariable<Set<?>> onlyInB = new NodeVariable<>("Only in B", Sets.TYPE);
    private final NodeVariable<Boolean> changed = new NodeVariable<>("Changed", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        Set<Object> left = Sets.copyOf(a.getValue());
        Set<Object> right = Sets.copyOf(b.getValue());

        Set<Object> missingFromB = without(left, Sets.keysOf(right));
        Set<Object> missingFromA = without(right, Sets.keysOf(left));

        onlyInA.setValue(Sets.frozen(missingFromB));
        onlyInB.setValue(Sets.frozen(missingFromA));
        changed.setValue(!missingFromB.isEmpty() || !missingFromA.isEmpty());
    }

    /**
     * The members of {@code from} whose text form is not in {@code exclude}, in order. Adding
     * straight to the {@link LinkedHashSet} rather than through {@link Sets#add} is safe here and
     * only here: {@code from} is already a set, so no two of its members share a text form and
     * there is nothing for {@code add}'s scan to find.
     */
    private static Set<Object> without(Set<Object> from, Set<String> exclude) {
        Set<Object> kept = new LinkedHashSet<>();
        for (Object member : from) {
            if (!exclude.contains(Lists.key(member))) {
                kept.add(member);
            }
        }
        return kept;
    }

    @Override
    public void configureInputs() {
        addInput(a);
        addInput(b);
    }

    @Override
    public void configureOutputs() {
        addOutput(onlyInA);
        addOutput(onlyInB);
        addOutput(changed);
    }
}
