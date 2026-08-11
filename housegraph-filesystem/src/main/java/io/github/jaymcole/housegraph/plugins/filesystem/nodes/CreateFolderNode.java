package io.github.jaymcole.housegraph.plugins.filesystem.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.filesystem.RelativeFolder;
import io.github.jaymcole.housegraph.storage.AppDirectories;

import java.io.IOException;

/**
 * Creates a folder under HouseGraph's default storage location ({@link AppDirectories#root()})
 * if it doesn't already exist. <b>Folder</b> is a path relative to that root, and may name a
 * nested subdirectory (e.g. {@code "photos/2026"}); either {@code /} or {@code \} works as a
 * separator, so the same graph behaves the same way regardless of which OS runs it. A
 * {@code ..} segment is rejected rather than silently resolved, since letting it through would
 * mean creating a folder outside HouseGraph's storage location.
 * <p>
 * <b>Done</b> fires every time this runs, whether or not the folder already existed;
 * <b>Created</b> fires only when this run actually made it, so a "populate it" step chained off
 * <b>Created</b> doesn't re-run against a folder it already filled. <b>Folder Path</b> is set
 * either way, to the folder's resolved absolute path, so a downstream node can read/write inside
 * it without recomputing where it lives.
 */
@Display.Name("Create Folder")
@Node.Type("filesystem.CreateFolderNode")
public class CreateFolderNode extends BaseNode {

    private final NodeVariable<String> folder = new NodeVariable<>("Folder", String.class, true).required();

    private final NodeVariable<String> folderPath = new NodeVariable<>("Folder Path", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort done = new FlowPort("Done", FlowPort.Direction.OUT);
    private final FlowPort created = new FlowPort("Created", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        // Activated before the filesystem call: if it throws below, Created is never activated,
        // and because Done already is, the engine's "activated nothing -> fire everything"
        // default (see BaseNode.activate) can't kick in and make a failed attempt look like a
        // successful creation.
        activate(done);

        RelativeFolder.Result result;
        try {
            result = RelativeFolder.ensure(AppDirectories.get().root(), folder.getValue());
        } catch (IOException e) {
            throw new RuntimeException("Could not create folder " + folder.getValue(), e);
        }

        folderPath.setValue(result.path().toString());
        if (result.created()) {
            activate(created);
        }
    }

    @Override
    public void configureInputs() {
        addInput(folder);
    }

    @Override
    public void configureOutputs() {
        addOutput(folderPath);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(done);
        addFlowOutput(created);
    }
}
