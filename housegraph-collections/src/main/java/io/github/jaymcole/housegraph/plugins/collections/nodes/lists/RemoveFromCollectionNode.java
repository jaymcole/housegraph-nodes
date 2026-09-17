package io.github.jaymcole.housegraph.plugins.collections.nodes.lists;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Lists;

import java.util.List;

/**
 * Removes every copy of Item from the named collection and republishes it — the counterpart to
 * {@link AddToCollectionNode} in the same {@code trigger &rarr; Name} family as
 * {@link ClearCollectionNode}, for whichever's the finer-grained of the two: dropping one entry a
 * later step decided it no longer wants, rather than emptying the whole collection.
 * <p>
 * Matching is the same {@link Lists#sameValue forgiving} comparison {@code RemoveItemNode} uses,
 * so a {@code "3"} typed into Item removes the {@code 3} some upstream node added, and every
 * matching copy goes, not just the first — the same "no such thing as remove the second copy but
 * keep the third" reasoning {@code RemoveItemNode} documents. <b>Item is a text input</b> for the
 * same reason as {@code RemoveItemNode}'s and {@code ListContainsNode}'s: this node is looking an
 * entry <em>up</em> to take it out, not handing in a value to keep, so it follows the text-typed
 * side of the convention {@code AddToCollectionNode}'s {@code Object}-typed Item sits on the other
 * side of (see the {@code lists} package overview).
 * <p>
 * <b>Being pulled for data removes nothing.</b> A downstream node resolving List, Count or Removed
 * without any flow arriving here republishes the current contents and reports zero removed — the
 * same pull-adds-nothing rule {@link AddToCollectionNode} follows, for the same reason: a value
 * read must never be a side effect.
 * <p>
 * <b>The collection is deliberately memory-only and outlives this node</b> — see
 * {@link NamedCollections} and {@link AddToCollectionNode}'s equivalent note. A null or blank Name,
 * or a null Item, leaves the collection unchanged.
 */
@Display.Name("Remove From Collection")
@Display.Description("Removes every copy of Item from the named collection and republishes it.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"collect", "remove", "delete", "drop", "exclude", "without", "item", "list", "loop"})
@Node.Type("collections.RemoveFromCollectionNode")
public class RemoveFromCollectionNode extends BaseNode {

    private final NodeVariable<String> name = new NodeVariable<>("Name", String.class, true).required();
    private final NodeVariable<String> item = new NodeVariable<>("Item", String.class, true).required();

    private final NodeVariable<List<?>> collected = new NodeVariable<>("List", Lists.TYPE);
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);
    private final NodeVariable<Integer> removed = new NodeVariable<>("Removed", Integer.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        int removedCount = 0;
        if (ctx.wasTriggeredVia(in)) {
            removedCount = remove(name.getValue(), item.getValue());
        }
        publish(name.getValue(), removedCount);
    }

    /**
     * Removes every copy of {@code value} from {@code collectionName}, ignoring a null name (an
     * unwired or blank Name must not silently share a collection with every other unnamed node)
     * and a null value (an unwired Item must not remove every null the list happens to hold).
     * Package-private so a test can exercise the removal without a live {@code NodeGraph}, for the
     * same reason as {@link AddToCollectionNode#collect}.
     *
     * @return how many copies were removed
     */
    int remove(String collectionName, Object value) {
        if (collectionName == null || value == null) {
            return 0;
        }
        List<Object> items = NamedCollections.get(collectionName);
        synchronized (items) {
            int before = items.size();
            items.removeIf(entry -> Lists.sameValue(entry, value));
            return before - items.size();
        }
    }

    /** Publishes an unmodifiable snapshot of {@code collectionName}'s current contents. */
    private void publish(String collectionName, int removedCount) {
        List<Object> snapshot = AddToCollectionNode.snapshotOf(collectionName);
        collected.setValue(snapshot);
        count.setValue(snapshot.size());
        removed.setValue(removedCount);
    }

    @Override
    public void configureInputs() {
        addInput(name);
        addInput(item);
    }

    @Override
    public void configureOutputs() {
        addOutput(collected);
        addOutput(count);
        addOutput(removed);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
