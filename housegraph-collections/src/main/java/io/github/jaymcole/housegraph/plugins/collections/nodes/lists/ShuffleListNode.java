package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The same entries in a random order. Useful in front of <b>Get Item</b> or a <b>For Each</b> when
 * something should vary — a greeting, a playlist, which light gets the effect first.
 * <p>
 * Randomness comes from {@link ThreadLocalRandom}, so concurrent runs shuffling at the same moment
 * don't contend on one generator and don't share a sequence. There is deliberately no seed input:
 * a reproducible shuffle is a testing tool, and a node that looked random but wasn't would be a
 * trap in a house that runs on it.
 */
@Display.Name("Shuffle")
@Display.Description("The same entries in a random order.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"shuffle", "random", "randomise", "randomize", "mix", "order", "list"})
@Node.Type("collections.ShuffleListNode")
public class ShuffleListNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<List<?>> result = new NodeVariable<>("List", Lists.TYPE);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> shuffled = Lists.mutableCopyOf(list.getValue());
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        result.setValue(Lists.frozen(shuffled));
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }
}
