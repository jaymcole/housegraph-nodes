package io.github.jaymcole.housegraph.plugins.collections.nodes.maps;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.collections.Maps;

import java.util.Map;

/**
 * The current contents of a named collection — the reading third of the set with
 * {@link PutInMapNode} and {@link ClearMapNode}. Type the same Name and wire <b>Map</b> into
 * whatever needs it: <b>Map Get</b> for one value, <b>Map Entries</b> to loop over it, <b>Join
 * Map</b> to render it.
 * <p>
 * <b>Read a named map through this node, never through Put In Map's or Clear Map's own
 * outputs.</b> Those are action nodes, and pulling one for data has two costs this node does not:
 * <ul>
 *   <li><b>It can cancel the put.</b> The engine runs a node at most once per run, and a pull
 *       counts. If something reads Put In Map's Map before flow reaches that Put In Map in the same
 *       run, the node is already complete when flow arrives, so its {@code process()} never runs
 *       again and the entry is silently never stored — while its flow-out still fires as though it
 *       had been.</li>
 *   <li><b>It re-resolves the Key and Value wiring.</b> Pulling a node resolves its inputs, so a
 *       read would re-run whatever produces the value being stored — an HTTP call, a sensor read.</li>
 * </ul>
 * This node has nothing upstream but a Name, so it is safe to pull from anywhere, at any point in a
 * run, as often as needed.
 * <p>
 * <b>It reads the collection as it stands when this node resolves</b>, which under the pull model
 * is when its first consumer in the run needs it — and it is not re-read later in the same run.
 * A consumer that must see a put made earlier in the same run should sit downstream of that put in
 * the flow, which is where it would naturally be.
 * <p>
 * A name nothing has put into yet reads as empty, not as an error, so a reader can be wired before
 * the writer has ever run. Like every other map output in this library, Map is an unmodifiable
 * snapshot: later puts do not change a value already handed downstream.
 */
@Display.Name("Get Named Map")
@Display.Description("The current contents of the named collection that Put In Map fills.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"get", "read", "named", "map", "collection", "lookup", "stored", "current", "contents"})
@Node.Type("collections.GetNamedMapNode")
public class GetNamedMapNode extends BaseNode {

    private final NodeVariable<String> name = new NodeVariable<>("Name", String.class, true).required()
            .describedAs("Names a shared, memory-only collection, paired with Put In Map and Clear Map.");

    private final NodeVariable<Map<?, ?>> collected = new NodeVariable<>("Map", Maps.TYPE)
            .describedAs("An unmodifiable snapshot taken at pull time, not re-read later in the same run.");
    private final NodeVariable<Integer> count = new NodeVariable<>("Count", Integer.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> snapshot = PutInMapNode.snapshotOf(name.getValue());
        collected.setValue(snapshot);
        count.setValue(snapshot.size());
    }

    @Override
    public void configureInputs() {
        addInput(name);
    }

    @Override
    public void configureOutputs() {
        addOutput(collected);
        addOutput(count);
    }
}
