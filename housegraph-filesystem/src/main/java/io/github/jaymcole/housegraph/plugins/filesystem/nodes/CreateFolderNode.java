package io.github.jaymcole.housegraph.plugins.filesystem.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.filesystem.RelativeFolder;
import io.github.jaymcole.housegraph.storage.AppDirectories;

import java.io.IOException;

/**
 * A folder under HouseGraph's default storage location ({@link AppDirectories#root()}), exposed
 * to the graph as a <b>data output</b> — wire <b>Folder Path</b> into whatever downstream node
 * needs to read/write inside it. <b>Folder</b> is a path relative to that root, and may name a
 * nested subdirectory (e.g. {@code "photos/2026"}); either {@code /} or {@code \} works as a
 * separator, so the same graph behaves the same way regardless of which OS runs it. A
 * {@code ..} segment is rejected rather than silently resolved, since letting it through would
 * mean creating a folder outside HouseGraph's storage location.
 * <p>
 * Like the other pure data sources (a data store, an image from disk), this has no flow ports —
 * nothing to trigger, nothing to report. It's pulled whenever something downstream needs its
 * output, and creates the folder on the fly at that point if it doesn't already exist yet;
 * already existing is a no-op.
 */
@Display.Name("Create Folder")
@Node.Type("filesystem.CreateFolderNode")
public class CreateFolderNode extends BaseNode {

    private final NodeVariable<String> folder = new NodeVariable<>("Folder", String.class, true).required();

    private final NodeVariable<String> folderPath = new NodeVariable<>("Folder Path", String.class);

    @Override
    public void process(ProcessContext ctx) {
        RelativeFolder.Result result;
        try {
            result = RelativeFolder.ensure(AppDirectories.get().root(), folder.getValue());
        } catch (IOException e) {
            throw new RuntimeException("Could not create folder " + folder.getValue(), e);
        }

        folderPath.setValue(result.path().toString());
    }

    @Override
    public void configureInputs() {
        addInput(folder);
    }

    @Override
    public void configureOutputs() {
        addOutput(folderPath);
    }
}
