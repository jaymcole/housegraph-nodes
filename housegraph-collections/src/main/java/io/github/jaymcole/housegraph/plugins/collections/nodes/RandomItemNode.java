package io.github.jaymcole.housegraph.plugins.collections.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One entry picked at random, with the index it came from. An empty list yields a null item, an
 * index of {@code -1}, and <b>Found</b> false — the same "nothing to report" shape <b>Get Item</b>
 * uses, so the two branch the same way through the host's <b>If (Boolean)</b>.
 * <p>
 * Picking one entry rather than shuffling the whole list is the cheap path for what is usually the
 * real question ("say one of these things"), and it reports the index so the choice can be
 * recorded or reused. See <b>Shuffle</b> for the note on where the randomness comes from.
 */
@Display.Name("Random Item")
@Display.Description("One entry picked at random, with its index.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"random", "pick", "choose", "any", "sample", "item", "list", "lucky"})
@Node.Type("collections.RandomItemNode")
public class RandomItemNode extends BaseNode {

    private final NodeVariable<List<?>> list = new NodeVariable<>("List", Lists.TYPE).required();

    private final NodeVariable<Object> item = new NodeVariable<>("Item", Object.class);
    private final NodeVariable<Integer> index = new NodeVariable<>("Index", Integer.class);
    private final NodeVariable<Boolean> found = new NodeVariable<>("Found", Boolean.class);

    @Override
    public void process(ProcessContext ctx) {
        List<Object> entries = Lists.copyOf(list.getValue());
        if (entries.isEmpty()) {
            item.setValue(null);
            index.setValue(-1);
            found.setValue(false);
            return;
        }
        int at = ThreadLocalRandom.current().nextInt(entries.size());
        item.setValue(entries.get(at));
        index.setValue(at);
        found.setValue(true);
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(item);
        addOutput(index);
        addOutput(found);
    }
}
